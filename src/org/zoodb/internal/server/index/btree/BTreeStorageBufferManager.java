package org.zoodb.internal.server.index.btree;

import org.zoodb.internal.server.DiskIO;
import org.zoodb.internal.server.DiskIO.DATA_TYPE;
import org.zoodb.internal.server.StorageChannel;
import org.zoodb.internal.server.StorageChannelInput;
import org.zoodb.internal.server.StorageChannelOutput;
import org.zoodb.internal.server.index.btree.prefix.PrefixSharingHelper;
import org.zoodb.internal.util.PrimLongMapLI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Observable;

public class BTreeStorageBufferManager implements BTreeBufferManager {

    private int pageSize;
    
	private PrimLongMapLI<PagedBTreeNode> dirtyBuffer;
	private PrimLongMapLI<PagedBTreeNode> cleanBuffer;
	private final int maxCleanBufferElements = -1;

	private int pageIdCounter;
	private final boolean isUnique;

	private final StorageChannel storageFile;
	private final StorageChannelInput storageIn;
	private final StorageChannelOutput storageOut;
	private DATA_TYPE dataType = DATA_TYPE.GENERIC_INDEX;;
	
	private int statNWrittenPages = 0;
	private int statNReadPages = 0;

	public BTreeStorageBufferManager(StorageChannel storage, boolean isUnique) {
		this.dirtyBuffer = new PrimLongMapLI<>();
		this.cleanBuffer = new PrimLongMapLI<>();
		this.pageIdCounter = 0;
		this.isUnique = isUnique;
		this.storageFile = storage;
		this.storageIn = storage.getReader(false);
		this.storageOut = storage.getWriter(false);
		
    	this.pageSize = this.storageFile.getPageSize();
	}

	public BTreeStorageBufferManager(StorageChannel storage, boolean isUnique, DATA_TYPE dataType) {
		this(storage, isUnique);
		this.dataType = dataType;
	}

	/*
	 * Only read pageIds that are known to be in BufferManager,
	 * otherwise the result is undefined
	 */
	@Override
	public PagedBTreeNode read(int pageId) {
		// search node in memory
		PagedBTreeNode node = readNodeFromMemory(pageId);
		if (node != null) {
			return node;
		}

		// search node in storage
		return readNodeFromStorage(pageId);
	}

	public PagedBTreeNode readNodeFromMemory(int pageId) {
		PagedBTreeNode node = dirtyBuffer.get(pageId);
		if(node != null) {
			return node;
		}

		return cleanBuffer.get(pageId);
	}

	public PagedBTreeNode readNodeFromStorage(int pageId) {
        storageIn.seekPageForRead(dataType, pageId);

		PagedBTreeNode node;

		boolean isLeaf = storageIn.readByte() < 0 ? true : false;
		int numKeys = storageIn.readInt();
		
		/* Deal with prefix-sharing encoded keys */
		byte[] metadata = new byte[5];
		storageIn.noCheckRead(metadata);
		int decodedArraySize = PrefixSharingHelper.byteArrayToInt(metadata, 0);
		byte prefixLength = metadata[4];
		int encodedArraySize = PrefixSharingHelper.encodedArraySize(decodedArraySize, prefixLength);
		byte[] encodedArrayWithoutMetadata = new byte[encodedArraySize];
		storageIn.noCheckRead(encodedArrayWithoutMetadata);
		long[] keys = PrefixSharingHelper.decodeArray(encodedArrayWithoutMetadata, decodedArraySize, prefixLength);
		
		if(isLeaf) {
			long[] values = new long[numKeys];
			storageIn.noCheckRead(values);
			node = PagedBTreeNodeFactory.constructLeaf(this, isUnique, false,
								pageSize, pageId, numKeys,
								keys, values);
		} else {
			int[] childrenPageIds = new int[numKeys+1];

			storageIn.noCheckRead(childrenPageIds);
            long[] values = null;
            if (!isUnique) {
                values = new long[numKeys];
                storageIn.noCheckRead(values);
            }
			node = PagedBTreeNodeFactory.constructInnerNode(this, isUnique, false,
								pageSize, pageId, numKeys, keys, values,
								childrenPageIds);
		}

		// node in memory == node in storage
		node.markClean();
		putInCleanBuffer(node.getPageId(), node);
		
		statNReadPages++;

		return node;
	}


	@Override
	public int write(PagedBTreeNode node) {
		if (!node.isDirty()) {
			return node.getPageId();
		}
		if (!node.isLeaf()) /* is inner node */{
			// write children
			int childIndex = 0;
			for (int childPageId : node.getChildrenPageIdList()) {
				PagedBTreeNode child = readNodeFromMemory(childPageId);
                //if child is not in memory, then it can not be dirty
				if(child != null && child.isDirty()) {
                    int newChildPageId = write(child);
                    node.setChildPageId(childIndex, newChildPageId);
				}

				childIndex++;
			}
		}
		// write data to storage and obtain new pageId
		int newPageId = writeNodeDataToStorage(node);

		// update pageId in memory
		dirtyBuffer.remove(node.getPageId());
		putInCleanBuffer(newPageId, node);

		node.setPageId(newPageId);
		node.markClean();
		
		statNWrittenPages++;

		return newPageId;
	}

	private void putInCleanBuffer(int pageId, PagedBTreeNode node) {
		if(maxCleanBufferElements < 0 || cleanBuffer.size() < maxCleanBufferElements - 1) {
			cleanBuffer.put(pageId, node);
		} else {
			cleanBuffer.clear();
		}
	}

	/*
	 * Leaf node page: 
	 * 1 byte -1 
	 * 4 byte numKeys
	 * prefixShareEncoding(keys) 
	 * 8 byte * numKeys for values
	 * 
	 * Inner node page 
	 * 1 byte 0 
	 * 4 byte numKeys
	 * prefixShareEncoding(keys) 
	 * 8 byte * numKeys for values
	 * 4 byte * (numKeys + 1) for childrenPageIds 
	 */

	int writeNodeDataToStorage(PagedBTreeNode node) {

		int previousPageId = node.getPageId() < 0 ? 0 : node.getPageId();
		// if node was not written before (negative "page id") use 0
		// as previous page id
		int pageId = storageOut.allocateAndSeek(dataType, previousPageId);

		if (node.isLeaf()) {
			storageOut.writeByte((byte) -1);
			storageOut.writeInt(node.getNumKeys());
			byte[] encodedKeys = PrefixSharingHelper.encodeArray(Arrays.copyOf(node.getKeys(), node.getNumKeys()));
			storageOut.noCheckWrite(encodedKeys);
			storageOut.noCheckWrite(Arrays.copyOf(node.getValues(), node.getNumKeys()));

		} else {
			storageOut.writeByte((byte) 1);
			storageOut.writeInt(node.getNumKeys());
			byte[] encodedKeys = PrefixSharingHelper.encodeArray(Arrays.copyOf(node.getKeys(), node.getNumKeys()));
			storageOut.noCheckWrite(encodedKeys);
            if (node.getValues() != null) {
				storageOut.noCheckWrite(Arrays.copyOf(node.getValues(), node.getNumKeys()));
            }
			storageOut.noCheckWrite(Arrays.copyOf(node.getChildrenPageIds(), node.getNumKeys()+1));
		}

		storageOut.flush();
		return pageId;
	}

	@Override
	public int save(PagedBTreeNode node) {
		/*
		 * nodes which only reside in memory have a negative
		 * "page id".
		 */
		pageIdCounter--;
		if(node.isDirty()) {
            dirtyBuffer.put(pageIdCounter, node);
		} else {
            putInCleanBuffer(pageIdCounter, node);
		}

		return pageIdCounter;
	}

	@Override
	public void remove(int id) {
		if(dirtyBuffer.remove(id) == null) {
			cleanBuffer.remove(id);
		}
		if(id > 0) {
			// page has been written to storage
			this.storageFile.reportFreePage(id);
		}
	}

	@Override
	public void clear() {
		pageIdCounter = 0;
		for(long id : dirtyBuffer.keySet()) {
            if(id > 0) {
                // page has been written to storage
                this.storageFile.reportFreePage((int)id);
            }
		}
		dirtyBuffer.clear();
		
        for(long id : cleanBuffer.keySet()) {
            if(id > 0) {
                // page has been written to storage
                this.storageFile.reportFreePage((int)id);
            }
		}
		cleanBuffer.clear();
	}

	public PrimLongMapLI<PagedBTreeNode> getMemoryBuffer() {
        PrimLongMapLI<PagedBTreeNode> ret = new PrimLongMapLI<PagedBTreeNode>();
        ret.putAll(cleanBuffer);
        ret.putAll(dirtyBuffer);

		return ret;
	}

	@Override
	public void update(Observable o, Object arg) {
		PagedBTreeNode node = (PagedBTreeNode) o;
		int pageId = node.getPageId();
		if(node.isDirty()) {
			cleanBuffer.remove(pageId);
			dirtyBuffer.put(pageId, node);
		} else {
			dirtyBuffer.remove(pageId);
			putInCleanBuffer(pageId, node);
		}
	}
	
	public PrimLongMapLI<PagedBTreeNode> getDirtyBuffer() {
		return dirtyBuffer;
	}

	public PrimLongMapLI<PagedBTreeNode> getCleanBuffer() {
		return cleanBuffer;
	}
	
    public int getStatNWrittenPages() {
		return statNWrittenPages;
	}

	public int getStatNReadPages() {
		return statNReadPages;
	}
	
	/*
	 * Iterates through tree and returns pageId of every reachable node
	 * that has been written to storage. Every non-reachable node should have
	 * been removed using BTree.remove and thus its page has been reported
	 * as free.
	 */
	public <T extends PagedBTreeNode> List<Integer> debugPageIds(PagedBTree<T> tree) {
		BTreeIterator it = new BTreeIterator(tree);
		ArrayList<Integer> pageIds = new ArrayList<Integer>();
		while(it.hasNext()) {
			PagedBTreeNode node = (PagedBTreeNode) it.next();
			int pageId = node.getPageId();
			if(pageId > 0) {
				pageIds.add(pageId);
			}
		}
		return pageIds;
	}

	public StorageChannel getStorageFile() {
		return this.storageFile;
	}
	
	public int getPageSize() {
		return this.pageSize;
	}
	
	public static int pageHeaderSize() {
		int nodeTypeIndicatorSize = 1;
		int numKeysSize = 4;
		
		int size = 0;
		size += DiskIO.PAGE_HEADER_SIZE;
		size += nodeTypeIndicatorSize;
		size += numKeysSize;
		
		return size;
	}

	@Override
	public int getNodeSizeInStorage(PagedBTreeNode node) {
		int size = 0;
		size += pageHeaderSize();
		size += node.getNonKeyEntrySizeInBytes() + node.getKeyArraySizeInBytes();
		
		return size;
	}

    @Override
    public int getNodeHeaderSizeInStorage(PagedBTreeNode node) {
        //ToDo compute this better, multiplying by 2 seems to work fine, but an exact size would be nice
        return pageHeaderSize();
    }
}
