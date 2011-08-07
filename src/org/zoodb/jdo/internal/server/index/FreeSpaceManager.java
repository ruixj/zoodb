package org.zoodb.jdo.internal.server.index;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.zoodb.jdo.internal.server.PageAccessFile;
import org.zoodb.jdo.internal.server.index.AbstractPagedIndex.AbstractIndexPage;
import org.zoodb.jdo.internal.server.index.PagedUniqueLongLong.LLEntry;
import org.zoodb.jdo.internal.server.index.PagedUniqueLongLong.ULLIterator;

/**
 * The free space manager.  
 * 
 * Uses separate index for free pages. Does not use a BitMap, that would only pay out if more 
 * than 1/32 of all pages would be free (based on 4KB page size?).
 * The manager should only return pages that were freed up during previous transactions, but not
 * in the current one. To do so, in the freespace manager, create a new iterator(MIN_INT/MAX_INT) 
 * for every new transaction. The iterator will return only free pages from previous transactions.
 * If (iter.hasNext() == false), use atomic page counter to allocate additional pages.
 * 
 * @author Tilmann Z�schke
 *
 */
public class FreeSpaceManager {
	
	private static final int PID_DO_NOT_USE = -1;
	private static final int PID_OK = 0;
	
	private transient PagedUniqueLongLong idx;
	private final AtomicInteger lastPage = new AtomicInteger(-1);
	private ULLIterator iter;
	
	private final ArrayList<Integer> toAdd = new ArrayList<Integer>();
	private final ArrayList<Integer> toDelete = new ArrayList<Integer>();
	
	/**
	 * Constructor for free space manager.
	 */
	public FreeSpaceManager() {
		//
	}

	/**
	 * Constructor for creating new index. 
	 * @param raf
	 */
	public void initBackingIndexNew(PageAccessFile raf) {
		if (idx != null) {
			throw new IllegalStateException();
		}
		//TODO 8/1
		idx = new PagedUniqueLongLong(raf, 8, 8);
		iter = (ULLIterator) idx.iterator(1, Long.MAX_VALUE);
	}
	
	/**
	 * Constructor for creating new index. 
	 * @param raf
	 */
	public void initBackingIndexLoad(PageAccessFile raf, int pageId, int pageCount) {
		if (idx != null) {
			throw new IllegalStateException();
		}
		//TODO 8/1
		idx = new PagedUniqueLongLong(raf, pageId, 8, 8);
		lastPage.set(pageCount);
		iter = (ULLIterator) idx.iterator(1, Long.MAX_VALUE);//pageCount);
	}
	
	
	public int write() {
		for (Integer l: toDelete) {
			idx.removeLong(l);
		}
		toDelete.clear();
		for (Integer l: toAdd) {
			idx.insertLong(l, PID_OK);
		}
		toAdd.clear();
		Map<AbstractIndexPage, Integer> map = new IdentityHashMap<AbstractIndexPage, Integer>();
		int n = -1;
		//repeat until we don't need any more new pages
		while (n != map.size()) {
			n = map.size();
			idx.preallocatePagesForWriteMap(map, this);
		}
		
		int pageId = idx.writeToPreallocated(map);
		return pageId;
	}

	public int getPageCount() {
		return lastPage.get() + 1;
	}

	/**
	 * This should only be called once, directly after reading the root pages.
	 * @param pageCount
	 */
	public void setPageCount(int pageCount) {
		lastPage.set(pageCount-1);
	}

	public int getNextPage(int prevPage) {
		reportFreePage(prevPage);
		
		if (iter.hasNextULL()) {
			LLEntry e = iter.nextULL();
			long pageId = e.getKey();
			long pageIdValue = e.getValue();
			
			// do not return pages that are PID_DO_NOT_USE.
			while (pageIdValue == PID_DO_NOT_USE && iter.hasNextULL()) {
				idx.removeLong(pageId);
				e = iter.nextULL();
				pageId = e.getKey();
				pageIdValue = e.getValue();
			}
			if (pageIdValue != PID_DO_NOT_USE) {
				toDelete.add((int) pageId);
				return (int) pageId;
			}
		}
		
		//If we didn't find any we allocate a new page.
		return lastPage.addAndGet(1);
	}

	/**
	 * This method returns a free page without removing it from the FSM. Instead it is labeled
	 * as 'invalid' and will be removed when it is encountered through the normal getNextPage()
	 * method.
	 * Now we make sure that all element of the map are still in the FSM.
	 * Why? Because writing the FSM is tricky because it modifies itself during the process
	 * when it allocates new pages. In theory, it could end up as a infinite loop, when it
	 * repeatedly does the following:
	 * a) allocate page; b) allocating results in page delete and removes it from the FSM;
	 * c) page is returned to the FSM; d) FSM requires a new page and therefore starts over
	 * with a).
	 * Solution: we do not remove pages, but only tag them. More precisely the alloc() in
	 * the index gets them from the FSM, but we don't remove them here, but only later when
	 * they are encountered in the normal getNextPage() method.
	 * 
	 * @param prevPage
	 * @return free page ID
	 */
	public int getNextPageWithoutDeletingIt(int prevPage) {
		reportFreePage(prevPage);
		
		if (iter.hasNextULL()) {
			LLEntry e = iter.nextULL();
			long pageId = e.getKey();
			long pageIdValue = e.getValue();
			
			// do not return pages that are PID_DO_NOT_USE.
			while (pageIdValue == PID_DO_NOT_USE && iter.hasNextULL()) {
				//don't delete these pages here, we just ignore them
				e = iter.nextULL();
				pageId = e.getKey();
				pageIdValue = e.getValue();
			}
			if (pageIdValue != PID_DO_NOT_USE) {
				//label the page as invalid
				idx.insertLong(pageId, PID_DO_NOT_USE);
				return (int) pageId;
			}
		}
		
		//If we didn't find any we allocate a new page.
		return lastPage.addAndGet(1);
	}

	public void reportFreePage(int prevPage) {
		if (prevPage > 10) {
			toAdd.add(prevPage);
		}
	}
	
	public void notifyCommit() {
		iter.close();
		//TODO use pageCount i.o. MAX_VALUE???
		//-> No cloning of pages that refer to new allocated disk space
		//-> But checking for isInterestedInPage is also expensive...
		iter = (ULLIterator) idx.iterator(1, Long.MAX_VALUE);
		
		//TODO optimization:
		//do not create an iterator. Instead implement special method that deletes and returns the
		//first element.
		//This avoids the iterator and the toDelete list. Especially when many many pages are
		//removed, the memory consumption shrinks instead of grows when using an iterator.
		//BUT: The iterator may be faster to return following elements because it knows their 
		//position
	}
}
