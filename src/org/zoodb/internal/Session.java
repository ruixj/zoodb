/*
 * Copyright 2009-2016 Tilmann Zaeschke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;

import javax.jdo.JDOOptimisticVerificationException;
import javax.jdo.ObjectState;
import javax.jdo.listener.DeleteCallback;
import javax.jdo.listener.InstanceLifecycleListener;
import javax.jdo.listener.StoreCallback;

import org.zoodb.api.ZooInstanceEvent;
import org.zoodb.api.impl.ZooPC;
import org.zoodb.internal.client.SchemaManager;
import org.zoodb.internal.client.session.ClientSessionCache;
import org.zoodb.internal.server.OptimisticTransactionResult;
import org.zoodb.internal.server.TxObjInfo;
import org.zoodb.internal.util.ClientLock;
import org.zoodb.internal.util.DBLogger;
import org.zoodb.internal.util.IteratorRegistry;
import org.zoodb.internal.util.MergingIterator;
import org.zoodb.internal.util.TransientField;
import org.zoodb.internal.util.Util;
import org.zoodb.schema.ZooSchema;
import org.zoodb.tools.DBStatistics;
import org.zoodb.tools.DBStatistics.STATS;
import org.zoodb.tools.ZooHelper;

/**
 * The main session class.
 * 
 * @author ztilmann
 *
 */
public class Session implements IteratorRegistry {

	public static final long OID_NOT_ASSIGNED = -1;
	public static final long TIMESTAMP_NOT_ASSIGNED = -1;

	public static final Class<?> PERSISTENT_SUPER = ZooPC.class;
	
	/** Primary node. Also included in the _nodes list. */
	private Node primary;
	/** All connected nodes. Includes the primary node. */
	private final ArrayList<Node> nodes = new ArrayList<Node>();
	private final SessionParentCallback parentSession;
	private final ClientSessionCache cache;
	private final SchemaManager schemaManager;
	private boolean isOpen = true;
	private boolean isActive = false;
	private final SessionConfig config;
	private final ClientLock lock = new ClientLock();
	private final HashMap<DBStatistics.STATS, Long> stats = new HashMap<>();
	
	private long transactionId = -1;
	
	private final WeakHashMap<Closeable, Object> resources = 
	    new WeakHashMap<Closeable, Object>(); 
	
	public Session(String dbPath, SessionConfig config) {
		this(null, dbPath, config);
	}
	
	public Session(SessionParentCallback parentSession, String dbPath, SessionConfig config) {
		if (dbPath == null || "".equals(dbPath)) {
			throw DBLogger.newUser("No URL or database name given. Please specify, "
					+ "for example via PersistenceManagerFactory.setConnectionURL()");
		}
		dbPath = ZooHelper.getDataStoreManager().getDbPath(dbPath);
		this.parentSession = parentSession;
		this.config = config;
		this.primary = ZooFactory.get().createNode(dbPath, this);
		this.cache = new ClientSessionCache(this);
		this.schemaManager = new SchemaManager(cache, config.getAutoCreateSchema());
		this.nodes.add(primary);
		this.cache.addNode(primary);
		this.primary.connect();
		if (DBLogger.isLoggable(Level.FINE)) {
			DBLogger.LOGGER.fine("Session created (ihc=" + System.identityHashCode(this) + ")");
		}
	}
	
	public boolean isActive() {
		return isActive;
	}
	
	public void begin() {
        try {
        	if (DBLogger.isLoggable(Level.FINE)) {
        		DBLogger.LOGGER.fine("begin(txId=" + transactionId + ")");
        	}
			lock();
    		checkOpen();
            if (isActive) {
                throw DBLogger.newUser("Can't open new transaction inside existing transaction.");
            }
			isActive = true;
			for (Node n: nodes) {
				long txId = n.beginTransaction();
				if (n == primary) {
					transactionId = txId;
				}
			}
		} finally {
			unlock();
		}
	}
	
	/**
	 * Verify optimistic consistency of the current transaction.
	 * 
	 * @throws JDOOptimisticVerificationException containing all failed objects if
	 * any objects fail.
	 */
	public void checkConsistency() {
		try {
			lock();
			processOptimisticVerification(true);
		} finally {
			unlock();
		}
	}

	public void commit(boolean retainValues) {
		long t1 = System.nanoTime();
		try {
			lock();
			checkActive();

			//pre-commit: traverse object tree for transitive persistence
			cache.persistReachableObjects();

			//commit phase #1: prepare, check conflicts, get optimistic locks
			//This needs to happen after OGT (we need the OIDs) and before everything else (avoid
			//any writes in case of conflict AND we need the WLOCK before any updates.
			processOptimisticVerification(false);

			try {
				schemaManager.commit();

				commitInternal();
				//commit phase #2: Updated database properly, release locks
				for (Node n: nodes) {
					n.commit();
				}
				cache.postCommit(retainValues, config.getDetachAllOnCommit());
				schemaManager.postCommit();
			} catch (RuntimeException e) {
				try {
					if (DBLogger.isLoggable(Level.FINE)) {
						DBLogger.LOGGER.fine("commit(txId=" + transactionId + 
								") aborted, rolling back");
					}
					if (DBLogger.isUser(e)) {
						//reset sinks
						for (ZooClassDef cs: cache.getSchemata()) {
							cs.getProvidedContext().getDataSink().reset();
							cs.getProvidedContext().getDataDeleteSink().reset();
						}		
						//allow for retry after user exceptions
						for (Node n: nodes) {
							n.revert();
						}
					}
					rollbackInteral();
				} catch (Throwable t) {
					//YES! Finally a good reason to swallow an exception.
					//Exception 'e' is of course more important than 't', so we swallow it...
					DBLogger.severe("rollback() failed: " + t.getMessage());
					t.printStackTrace();
				}
				throw e;
			}

			closeResources();
			isActive = false;
		} finally {
			unlock();
			if (DBLogger.isLoggable(Level.FINE)) {
				long t2 = System.nanoTime();
				DBLogger.LOGGER.fine("commit(txId=" + transactionId + 
						") finished - Time=" + (t2-t1) + "ns");
			}
		}
	}


	private void getObjectToCommit(ArrayList<TxObjInfo> updates) {
		for (ZooPC pc: cache.getDeletedObjects()) {
			updates.add(new TxObjInfo(pc.jdoZooGetOid(), pc.jdoZooGetTimestamp(), true));
		}
		for (ZooPC pc: cache.getDirtyObjects()) {
			updates.add(new TxObjInfo(pc.jdoZooGetOid(), pc.jdoZooGetTimestamp(), false));
		}
		for (GenericObject pc: cache.getDirtyGenericObjects()) {
			updates.add(new TxObjInfo(pc.getOid(), pc.jdoZooGetTimestamp(), pc.jdoZooIsDeleted()));
		}
		for (ZooClassDef cd: cache.getSchemata()) {
			if (cd.jdoZooIsDeleted() || cd.jdoZooIsNew() || cd.jdoZooIsDirty()) {
				updates.add(new TxObjInfo(cd.jdoZooGetOid(), cd.jdoZooGetTimestamp(), 
						cd.jdoZooIsDeleted()));
			}
		}
	}

	private void processOptimisticVerification(boolean isTrialRun) {
		ArrayList<TxObjInfo> updates = new ArrayList<>();
		getObjectToCommit(updates);
		OptimisticTransactionResult ovrSummary = new OptimisticTransactionResult();
		for (Node n: nodes) {
			if (isTrialRun) {
				//check consistency
				ovrSummary.add( n.checkTxConsistency(updates) );
			} else {
				//proper commit()
				ovrSummary.add( n.beginCommit(updates) );
			}
		}
		
		processOptimisticTransactionResult(ovrSummary);
		
		if (!ovrSummary.getConflicts().isEmpty()) {
			JDOOptimisticVerificationException[] ea = 
					new JDOOptimisticVerificationException[ovrSummary.getConflicts().size()];
			int pos = 0;
			for (Long oid: ovrSummary.getConflicts()) {
				Object failedObj = cache.findCoByOID(oid); 
				if (failedObj == null) {
					//generic object
					failedObj = cache.getGeneric(oid).getOrCreateHandle();
				}
				ea[pos] = new JDOOptimisticVerificationException(Util.oidToString(oid), failedObj);
				pos++;
			}
			if (!isTrialRun) {
				//perform rollback
				rollbackInteral();
			}
			throw new JDOOptimisticVerificationException("Optimistic verification failed", ea);
		}
	}
	
	private void processOptimisticTransactionResult(OptimisticTransactionResult otr) {
		if (otr.requiresReset()) {
			isActive = false;
			closeInternal();
			throw DBLogger.newFatalDataStore(
					"Database schema has changed, please reconnect. ", null);
		}
		if (otr.requiresRefresh()) {
			if (schemaManager.hasChanges()) {
				//remote index update & local schema updates (could be index) --> considered bad!
				throw new JDOOptimisticVerificationException("Optimistic verification failed "
						+ "because schema changes occurred in remote concurrent sessions.");
			}

			// refresh schema, this works only for indexes
			schemaManager.refreshSchemaAll();
		}
	}
	
	private void commitInternal() {
		//create new schemata
		Collection<ZooClassDef> schemata = cache.getSchemata();
		
		//First delete
		for (ZooPC co: cache.getDeletedObjects()) {
		    if (!co.jdoZooIsDirty()) {
		    	throw new IllegalStateException("State=");
		    }
			if (co.jdoZooIsDeleted()) {
				if (co.jdoZooIsNew()) {
					//ignore
					continue;
				}
	            if (co.jdoZooGetClassDef().jdoZooIsDeleted()) {
	                //Ignore instances of deleted classes, there is a dropInstances for them
	                continue;
	            }
				if (co instanceof DeleteCallback) {
					((DeleteCallback)co).jdoPreDelete();
				}
				co.jdoZooGetContext().notifyEvent(co, ZooInstanceEvent.PRE_DELETE);
	            co.jdoZooGetContext().getDataDeleteSink().delete(co);
			} else {
		    	throw new IllegalStateException("State=");
			}
		}
		//generic objects
		if (!cache.getDirtyGenericObjects().isEmpty()) {
    		for (GenericObject go: cache.getDirtyGenericObjects()) {
    			if (go.jdoZooIsDeleted() && !go.jdoZooIsNew()) {
    				if (!go.checkPcDeleted()) {
    					go.jdoZooGetContext().getDataDeleteSink().deleteGeneric(go);
    				}
    			}
    		}
		}
		//flush sinks
        for (ZooClassDef cs: schemata) {
            cs.getProvidedContext().getDataDeleteSink().flush();
        }		

        //Then update. This matters for unique indices where deletion must occur before updates.
		for (ZooPC co: cache.getDirtyObjects()) {
		    if (!co.jdoZooIsDirty()) {
		    	//can happen when object are refreshed after being marked dirty? //TODO
		    	//throw new IllegalStateException("State=");
		        continue;
		    }
			if (!co.jdoZooIsDeleted()) {
				if (co instanceof StoreCallback) {
					((StoreCallback)co).jdoPreStore();
				}
				co.jdoZooGetContext().notifyEvent(co, ZooInstanceEvent.PRE_STORE);
			    co.jdoZooGetContext().getDataSink().write(co);
			}
		}

		//generic objects
		if (!cache.getDirtyGenericObjects().isEmpty()) {
			//TODO we are iterating twice through dirty/deleted objects... is that necessary?
    		for (GenericObject go: cache.getDirtyGenericObjects()) {
    			if (!go.jdoZooIsDeleted()) {
    				go.verifyPcNotDirty();
    				//TODO
    				//TODO
    				//TODO
    				//TODO What is this for ?????
    				//TODO
    				//TODO
    				//TODO
	    		    go.toStream();
	                go.jdoZooGetContext().getDataSink().writeGeneric(go);
    			}
    		}
		}
		
		//flush sinks
        for (ZooClassDef cs: schemata) {
            cs.getProvidedContext().getDataSink().flush();
        }		
	}

	public void rollback() {
		try {
			if (DBLogger.isLoggable(Level.FINE)) {
				DBLogger.LOGGER.fine("rollback(txId=" + transactionId + ")");
			}
			lock();
			checkActive();
			rollbackInteral();
		} finally {
			unlock();
		}
	}
	
	public void rollbackInteral() {
		schemaManager.rollback();

		OptimisticTransactionResult otr = new OptimisticTransactionResult();
		for (Node n: nodes) {
			//drop the DB-locks
			otr.add( n.rollbackTransaction() );
		}
		cache.rollback();
		isActive = false;

		processOptimisticTransactionResult(otr);
	}
	
	public void makePersistent(ZooPC pc) {
		try {
			lock();
			checkActive();
			if (pc.jdoZooIsPersistent()) {
				if (pc.jdoZooGetContext().getSession() != this) {
					throw DBLogger.newUser("The object belongs to a different persistence manager.");
				}
				if (pc.jdoZooIsDeleted()) {
					throw DBLogger.newUser("The object has been deleted!");
				}
				//nothing to do, is already persistent
				return; 
			}
			primary.makePersistent(pc);
		} finally {
			unlock();
		}
	}

	public void makeTransient(ZooPC pc) {
		try {
			lock();
			checkActive();
			if (!pc.jdoZooIsPersistent()) {
				//already transient
				return;
			}
			if (pc.jdoZooGetContext().getSession() != this) {
				throw DBLogger.newUser("The object belongs to a different persistence manager.");
			}
			if (pc.jdoZooIsDirty()) {
				throw DBLogger.newUser(
						"Dirty objects cannot be made transient: " + Util.getOidAsString(pc));
			}
			//remove from cache
			cache.makeTransient((ZooPC) pc);
		} finally {
			unlock();
		}
	}

	public static void assertOid(long oid) {
		if (oid == OID_NOT_ASSIGNED) {
			throw DBLogger.newUser("Invalid OID: " + oid);
		}
	}

	/**
	 * INTERNAL !!!!
	 * @param cls
	 * @param subClasses
	 * @param loadFromCache
	 * @return An extent over a class
	 */
	public MergingIterator<ZooPC> loadAllInstances(Class<?> cls, 
			boolean subClasses, boolean loadFromCache) {
		checkActiveRead();
		MergingIterator<ZooPC> iter = 
				new MergingIterator<ZooPC>(this, config.getFailOnClosedQueries());
        ZooClassDef def = cache.getSchema(cls, primary);
		loadAllInstances(def.getVersionProxy(), subClasses, iter, loadFromCache);
		if (loadFromCache) {
			//also add 'new' instances
			iter.add(cache.iterator(def, subClasses, ObjectState.PERSISTENT_NEW));
		}
		return iter;
	}

	/**
	 * This method avoids nesting MergingIterators. 
	 * @param def
	 * @param subClasses
	 * @param iter
	 */
	private void loadAllInstances(ZooClassProxy def, boolean subClasses, 
			MergingIterator<ZooPC> iter, boolean loadFromCache) {
		for (Node n: nodes) {
			iter.add(n.loadAllInstances(def, loadFromCache));
		}
		
		if (subClasses) {
			for (ZooClassProxy sub: def.getSubProxies()) {
				loadAllInstances(sub, true, iter, loadFromCache);
			}
		}
	}


	public ZooHandleImpl getHandle(long oid) {
		try {
			lock();
			checkActiveRead();
			GenericObject gob = cache.getGeneric(oid);
			if (gob != null) {
				return gob.getOrCreateHandle();
			}

			ZooPC co = cache.findCoByOID(oid);
			if (co != null) {
				if (co.jdoZooIsNew() || co.jdoZooIsDirty()) {
					//TODO  the problem here is the initialisation of the GO, which would require
					//a way to serialize PCs into memory and deserialize them into an GO
					throw new UnsupportedOperationException("Handles on new or dirty Java PC objects " +
							"are not allowed. Please call commit() first or create handles with " +
							"ZooClass.newInstance() instead. OID: " + Util.getOidAsString(co));
				}
				ZooClassDef schema = co.jdoZooGetClassDef();
				GenericObject go = co.jdoZooGetNode().readGenericObject(schema, oid);
				return go.getOrCreateHandle();
			}

			try {
				for (Node n: nodes) {
					//We should load the object only as byte[], if at all...
					ZooClassProxy schema = getSchemaManager().locateSchemaForObject(oid, n);
					GenericObject go = n.readGenericObject(schema.getSchemaDef(), oid);
					return go.getOrCreateHandle();
				}
			} catch (RuntimeException e) {
				if (!DBLogger.isObjectNotFoundException(e)) {
					throw e;
				}
				//ignore, return null
			}
			return null;
		} finally {
			unlock();
		}
	}

	public ZooHandleImpl getHandle(Object pc) {
		try {
			lock();
			checkActiveRead();
			ZooPC pci = checkObject(pc);
			long oid = pci.jdoZooGetOid();
			GenericObject gob = cache.getGeneric(oid);
			if (gob != null) {
				return gob.getOrCreateHandle();
			}

			if (pci.jdoZooIsNew() || pci.jdoZooIsDirty()) {
				//TODO  the problem here is the initialisation of the GO, which would require
				//a way to serialize PCs into memory and deserialize them into an GO
				throw new UnsupportedOperationException("Handles on new or dirty Java PC objects " +
						"are not allowed. Please call commit() first or create handles with " +
						"ZooClass.newInstance() instead. OID: " + Util.getOidAsString(pci));
			}
			ZooClassDef schema = pci.jdoZooGetClassDef();
			GenericObject go = pci.jdoZooGetNode().readGenericObject(schema, oid);
			return go.getOrCreateHandle();
		} finally {
			unlock();
		}
	}

	/**
	 * Refresh an Object. If the object has been deleted locally, it will
	 * get the state of the object on disk. 
	 * @param pc
	 */
	public void refreshObject(Object pc) {
		try{
			lock();
			checkActiveRead();
			refreshObjectInternal(pc);
		} finally {
			unlock();
		}
 	}
	

	private void refreshObjectInternal(Object pc) {
		ZooPC co = checkObjectForRefresh(pc);
		if (co.jdoZooIsPersistent()) {
			co.jdoZooGetNode().refreshObject(co);
		}
 	}
	

	public void refreshAll() {
		try {
			lock();
			checkActiveRead();
			ArrayList<ZooPC> objs = new ArrayList<>();
			for ( ZooPC pc: cache.getAllObjects() ) {
				ZooPC co = checkObjectForRefresh(pc);
				if (co.jdoZooIsPersistent()) {
					objs.add(co);
				}
			}
			//We use a separate loop here to avoid concurrent-mod exceptions in cases where a 
			//remotely deleted object has to be removed from the local cache.
			for (ZooPC pc: objs) {
				try {
					refreshObjectInternal(pc);
				} catch (RuntimeException t) {
					if (DBLogger.OBJ_NOT_FOUND_EXCEPTION.isAssignableFrom(t.getClass())) {
						//okay, ignore, this happens if an object was delete remotely
						continue;
					}
					throw t;
				}
			}
		} finally {
			unlock();
		}
	}


	public void refreshAll(Collection<?> arg0) {
		checkActiveRead();
		for ( Object obj: arg0 ) {
			refreshObject(obj);
		}
	}


	/**
	 * Check for base class, persistence state and PM affiliation. 
	 * @param pc
	 * @return CachedObject
	 */
	private ZooPC checkObject(Object pc) {
        return checkObject(pc, false);
	}

	private ZooPC checkObject(Object pc, boolean ignoreForRefresh) {
        if (!(pc instanceof ZooPC)) {
        	throw DBLogger.newUser("The object is not persistent capable: " + pc.getClass());
        }
        
        ZooPC pci = (ZooPC) pc;
        if (!ignoreForRefresh && !pci.jdoZooIsPersistent()) {
        	throw DBLogger.newUser("The object has not been made persistent yet.");
        }
        if (!ignoreForRefresh && pci.jdoZooIsDeleted()) {
        	throw DBLogger.newUser("The object has alerady been deleted.");
        }

        if (pci.jdoZooGetContext().getSession() != this) {
        	throw DBLogger.newUser("The object belongs to a different PersistenceManager.");
        }
        return pci;
	}


	/**
	 * For refresh, we can ignore things like deletion or transience.
	 * @param pc
	 * @return the refreshed object
	 */
	private ZooPC checkObjectForRefresh(Object pc) {
        if (!(pc instanceof ZooPC)) {
        	throw DBLogger.newUser("The object is not persistent capable: " + pc.getClass());
        }
        
        ZooPC pci = (ZooPC) pc;
        if (!pci.jdoZooIsPersistent()) {
        	return pci;
        }

        if (pci.jdoZooGetContext().getSession() != this) {
        	throw DBLogger.newUser("The object belongs to a different PersistenceManager.");
        }
        return pci;
	}


	public Object getObjectById(Object arg0) {
		try {
			lock();
			checkActiveRead();
			long oid = (Long) arg0;
			ZooPC co = cache.findCoByOID(oid);
			if (co != null) {
				if (co.jdoZooIsStateHollow() && !co.jdoZooIsDeleted()) {
					co.jdoZooGetNode().refreshObject(co);
				}
				return co;
			}

			//find it
			for (Node n: nodes) {
				co = n.loadInstanceById(oid);
				if (co != null) {
					break;
				}
			}

			return co;
		} finally {
			unlock();
		}
	}
	
	public Object[] getObjectsById(Collection<? extends Object> arg0) {
		checkActiveRead();
		Object[] res = new Object[arg0.size()];
		int i = 0;
		for ( Object obj: arg0 ) {
			res[i] = getObjectById(obj);
			i++;
		}
		return res;
	}

	/**
	 * @param oid
	 * @return Whether the object exists
	 */
	public boolean isOidUsed(long oid) {
		try {
			lock();
			checkActiveRead();
			//TODO we could also just compare it with max-value in the OID manager...
	        ZooPC co = cache.findCoByOID(oid);
	        if (co != null) {
	        	return true;
	        }
	        GenericObject go = cache.getGeneric(oid);
	        if (go != null) {
	        	return true;
	        }
	        //find it
	        for (Node n: nodes) {
	        	if (n.checkIfObjectExists(oid)) {
	        		return true;
	        	}
	        }
	        return false;
		} finally {
			unlock();
		}
	}
	

	public void deletePersistent(Object pc) {
		try {
			lock();
			checkActive();
			ZooPC co = checkObject(pc);
			co.jdoZooMarkDeleted();
		} finally {
			unlock();
		}
	}


	public SchemaManager getSchemaManager() {
		checkOpen();
		return schemaManager;
	}


	public void close() {
		if (!isOpen) {
			throw DBLogger.newUser("This session is closed.");
		}
		try {
			lock();
			for (Node n: nodes) {
				n.closeConnection();
			}
			cache.close();
			closeResources();
			TransientField.deregisterPm(this);
			isOpen = false;
		} finally {
			unlock();
		}
		if (DBLogger.isLoggable(Level.FINE)) {
			DBLogger.LOGGER.fine("Session closed (ihc=" + System.identityHashCode(this) + ")");
		}
	}
	
	private void closeInternal() {
		if (parentSession != null) {
			parentSession.close();
		} else {
			close();
		}
	}
	
    public Object getExternalSession() {
		checkOpen();
        return parentSession;
    }


    public SessionConfig getConfig() {
		checkOpen();
        return config;
    }


    public void evictAll() {
		try {
			lock();
			checkActiveRead();
			cache.evictAll();
		} finally {
			unlock();
		}
    }


    public void evictAll(Object[] pcs) {
		try {
			lock();
			checkActiveRead();
			for (Object obj: pcs) {
				ZooPC pc = (ZooPC) obj;
				if (!pc.jdoZooIsDirty()) {
					pc.jdoZooEvict();
				}
			}
		} finally {
			unlock();
		}
    }


    public void evictAll(boolean subClasses, Class<?> cls) {
		try {
			lock();
			checkActiveRead();
			cache.evictAll(subClasses, cls);
		} finally {
			unlock();
		}
    }


	public Node getPrimaryNode() {
		return primary;
	}
	
	/**
	 * INTERNAL !!!!
	 * Iterators to be refreshed upon commit().
	 * @param it
	 */
	@Override
    public void registerResource(Closeable it) {
		resources.put(it, null);
    }


    @Override
    public void deregisterResource(Closeable iter) {
    	resources.remove(iter);
    }

	private void closeResources() {
		try {
			for (Closeable c: resources.keySet().toArray(new Closeable[0])) {
				c.close();
			}
		} catch (IOException e) {
			//This can currently not happen
			DBLogger.newFatal("Failed closing resource", e);
		}
		//TODO Why is this currently not done?
		//resources.clear();
	}


    public Set<ZooPC> getCachedObjects() {
		try {
			lock();
			checkActiveRead();
			//We have to create a copy here to avoid users seeing
			//ConcurrentModificationExceptions while traversing the
			//list. Side-effect: we can return a modifiable collection.
			HashSet<ZooPC> ret = new HashSet<ZooPC>();
			for (ZooPC o: cache.getAllObjects()) {
				ret.add(o);
			}
			return ret;
		} finally {
			unlock();
		}
    }


    /**
     * Internal, don't call from outside!
     * @return The cache
     */
	public ClientSessionCache internalGetCache() {
		return cache;
	}


	public void addInstanceLifecycleListener(InstanceLifecycleListener listener,
			Class<?>[] classes) {
		try {
			lock();
			checkOpen();
			if (classes == null) {
				classes = new Class[]{null};
			}
			for (Class<?> cls: classes) {
				if (cls == null) {
					cls = ZooPC.class;
				}
				ZooClassDef def = cache.getSchema(cls, primary);
				if (def == null) {
					throw DBLogger.newUser("Cannot define listener for unknown class: " + cls);
				}
				def.getProvidedContext().addLifecycleListener(listener);
			}
		} finally {
			unlock();
		}
	}


	public void removeInstanceLifecycleListener(InstanceLifecycleListener listener) {
		try {
			lock();
			checkActiveRead();
			for (ZooClassDef def: cache.getSchemata()) {
				def.getProvidedContext().removeLifecycleListener(listener);
			}
		} finally {
			unlock();
		}
	}

	private void checkActive() {
    	checkOpen();
    	if (!isActive) {
    		throw DBLogger.newUser("Transaction is not active. Missing 'begin()'?");
    	}
	}
	
	public void checkActiveRead() {
    	checkOpen();
    	if (!isActive && !config.getNonTransactionalRead()) {
    		throw DBLogger.newUser("Transaction is not active. Missing 'begin()'?");
    	}
	}
	
	public void checkOpen() {
		if (!isOpen) {
			throw DBLogger.newUser("This session is closed.");
		}
	}

	public boolean isClosed() {
		return !isOpen;
	}

	public static long getObjectId(Object o) {
		if (o instanceof ZooPC) {
			DBLogger.newUser("The object is not persistence capable: " + o.getClass());
		}
		ZooPC zpc = (ZooPC) o;
		return zpc.jdoZooGetOid();
	}
	
	public static Session getSession(Object o) {
		if (o instanceof ZooPC) {
			DBLogger.newUser("The object is not persistence capable: " + o.getClass());
		}
		ZooPC zpc = (ZooPC) o;
		if (zpc.jdoZooGetContext() == null) {
			return null;
		}
		return zpc.jdoZooGetContext().getSession();
	}
	
	/**
	 * Get access to schema management.
	 * @return Schema management API
	 */
	public ZooSchema schema() {
		return new ZooSchemaImpl(this, schemaManager);
	}
	
	public long getTransactionId() {
		return transactionId;
	}
	
	public void lock() {
		lock.lock();
	}
	
	public void unlock() {
		lock.unlock();
	}

	public ClientLock getLock() {
		return lock;
	}

	public boolean getMultithreaded() {
		return lock.isLockingEnabled();
	}

	public void setMultithreaded(boolean arg0) {
		lock.enableLocking(arg0);
	}

	public long getStats(STATS stat) {
		Long s = stats.get(stat);
		if (s == null) {
			return 0;
		}
		return (long) s;
	}

	public void statsInc(STATS stat) {
		Long cnt = stats.get(stat);
		if (cnt == null) {
			cnt = 1L;
		} else {
			cnt++;
		}
		stats.put(stat, cnt);
	}
}
