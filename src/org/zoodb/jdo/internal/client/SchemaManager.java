/*
 * Copyright 2009-2011 Tilmann Z�schke. All rights reserved.
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
package org.zoodb.jdo.internal.client;

import java.util.ArrayList;
import java.util.Collection;

import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.JDOUserException;

import org.zoodb.api.impl.ZooPCImpl;
import org.zoodb.jdo.api.ZooClass;
import org.zoodb.jdo.internal.SchemaClassProxy;
import org.zoodb.jdo.internal.Node;
import org.zoodb.jdo.internal.ZooClassDef;
import org.zoodb.jdo.internal.ZooFieldDef;
import org.zoodb.jdo.internal.client.session.ClientSessionCache;

/**
 * This class maps schema data between the external Schema/ISchema classes and
 * the internal ZooClassDef class. 
 * 
 * @author Tilmann Z�schke
 *
 */
public class SchemaManager {

	private ClientSessionCache cache;
	private final ArrayList<SchemaOperation> ops = new ArrayList<SchemaOperation>();

	public SchemaManager(ClientSessionCache cache) {
		this.cache = cache;
	}
	
	public boolean isSchemaDefined(Class<?> cls, Node node) {
		return (locateClassDefinition(cls, node) != null);
	}

	/**
	 * Checks class and disk for class definition.
	 * @param cls
	 * @param node
	 * @return Class definition, may return null if no definition is found.
	 */
	private ZooClassDef locateClassDefinition(Class<?> cls, Node node) {
		ZooClassDef def = cache.getSchema(cls, node);
		if (def != null) {
			//return null if deleted
			if (!def.jdoZooIsDeleted()) { //TODO load if hollow???
				return def;
			}
			return null;
		}
		
		return def;
	}

	public SchemaClassProxy locateSchema(Class<?> cls, Node node) {
		ZooClassDef def = locateClassDefinition(cls, node);
		//not in cache and not on disk
		if (def == null) {
			return null;
		}
		//it should now be in the cache
		//return a unique handle, even if called multiple times. There is currently
		//no real reason, other than that it allows == comparison.
		return def.getApiHandle();
	}

	public void refreshSchema(ZooClassDef def) {
		def.jdoZooGetNode().refreshSchema(def);
	} 
	
	private ZooClassDef locateClassDefinition(String clsName, Node node) {
		ZooClassDef def = cache.getSchema(clsName, node);
		if (def != null) {
			//return null if deleted
			if (!def.jdoZooIsDeleted()) { //TODO load if hollow???
				return def;
			}
			return null;
		}
		
		return def;
	}

	public SchemaClassProxy locateSchema(String className, Node node) {
		ZooClassDef def = locateClassDefinition(className, node);
		//not in cache and not on disk
		if (def == null) {
			return null;
		}
		//it should now be in the cache
        return getSchemaProxy(def, node);
	}

	public SchemaClassProxy locateSchemaForObject(long oid, Node node) {
		ZooPCImpl pc = cache.findCoByOID(oid);
		if (pc != null) {
			return pc.jdoZooGetClassDef().getApiHandle();
		}
		
		//object not loaded or instance of virtual class
		//so we don't fully load the object, but only get its schema
		ZooClassDef def = node.getSchemaForObject(oid);
		return getSchemaProxy(def, node);
	}

	private SchemaClassProxy getSchemaProxy(ZooClassDef def, Node node) {
        //return a unique handle, even if called multiple times. There is currently
        //no real reason, other than that it allows == comparison.
        return def.getApiHandle();
	}
	
	public SchemaClassProxy createSchema(Node node, Class<?> cls) {
		if (isSchemaDefined(cls, node)) {
			throw new JDOUserException(
					"Schema is already defined: " + cls.getName());
		}
		//Is this PersistentCapanbleImpl or a sub class?
		if (!(ZooPCImpl.class.isAssignableFrom(cls))) {
			throw new JDOUserException(
						"Class has no persistent capable super class: " + cls.getName());
		}
        if (cls.isMemberClass()) {
        	System.err.println("ZooDB - Found innner class: " + cls.getName());
//            throw new JDOUserException(
//                    "Member (non-static inner) classes are not permitted: " + cls.getName());
        }
        if (cls.isLocalClass()) {
            throw new JDOUserException(
                    "Local classes (defined in a method) are not permitted: " + cls.getName());
        }
        if (cls.isAnonymousClass()) {
            throw new JDOUserException(
                    "Anonymous classes are not permitted: " + cls.getName());
        }
        if (cls.isInterface()) {
            throw new JDOUserException(
                    "Interfaces are currently not supported: " + cls.getName());
        }

		ZooClassDef def;
		long oid = node.getOidBuffer().allocateOid();
		if (cls != ZooPCImpl.class) {
			Class<?> clsSuper = cls.getSuperclass();
			ZooClassDef defSuper = locateClassDefinition(clsSuper, node);
			def = ZooClassDef.createFromJavaType(cls, oid, defSuper, node, cache.getSession()); 
		} else {
			def = ZooClassDef.createFromJavaType(cls, oid, null, node, cache.getSession());
		}
		cache.addSchema(def, false, node);
		ops.add(new SchemaOperation.SchemaDefine(node, def));
		return def.getApiHandle();
	}

	public void deleteSchema(ZooClassDef def, boolean deleteSubClasses) {
		if (!deleteSubClasses && !def.getSubClassesLatestVersions().isEmpty()) {
		    throw new JDOUserException("Can not remove class schema while sub-classes are " +
		            " still defined: " + def.getSubClassesLatestVersions().get(0).getClassName());
		}
		if (def.jdoZooIsDeleted()) {
			throw new JDOObjectNotFoundException("This objects has already been deleted.");
		}
		
		if (deleteSubClasses) {
			while (!def.getSubClassesLatestVersions().isEmpty()) {
				deleteSchema(def.getSubClassesLatestVersions().get(0), true);
			}
		}
		
		//delete instances
		for (ZooPCImpl pci: cache.getAllObjects()) {
			if (pci.jdoZooGetClassDef() == def) {
				pci.jdoZooMarkDeleted();
			}
		}
		ops.add(new SchemaOperation.SchemaDelete(def.jdoZooGetNode(), def));
		def.jdoZooMarkDeleted();
		System.err.println("FIXME: Delete whole version tree!");
	}

	public void defineIndex(String fieldName, boolean isUnique, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		if (f.isIndexed()) {
			throw new JDOUserException("Field is already indexed: " + fieldName);
		}
		ops.add(new SchemaOperation.IndexCreate(node, f, isUnique));
	}

	public boolean removeIndex(String fieldName, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		if (!f.isIndexed()) {
			return false;
		}
		ops.add(new SchemaOperation.IndexRemove(node, f));
		return true;
	}

	public boolean isIndexDefined(String fieldName, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		return f.isIndexed();
	}

	public boolean isIndexUnique(String fieldName, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		if (!f.isIndexed()) {
			throw new JDOUserException("Field has no index: " + fieldName);
		}
		return f.isIndexUnique();
	}
	
	private ZooFieldDef getFieldDef(ZooClassDef def, String fieldName) {
		for (ZooFieldDef f: def.getAllFields()) {
			if (f.getName().equals(fieldName)) {
				return f;
			}
		}
		throw new JDOUserException("Field name not found: " + fieldName + " in " + 
				def.getClassName());
	}

	public void commit() {
		// perform pending operations
		for (SchemaOperation op: ops) {
			op.commit();
		}
		ops.clear();
	}

	public void rollback() {
		// undo pending operations - to be rolled back in reverse order
		for (int i = ops.size()-1; i >= 0; i--) {
			ops.get(i).rollback();
		}
		ops.clear();
	}

	public Object dropInstances(Node node, ZooClassDef def) {
		ops.add(new SchemaOperation.DropInstances(node, def));
		return true;
	}

	public void renameSchema(Node node, ZooClassDef def, String newName) {
		if (cache.getSchema(newName, node) != null) {
			throw new JDOUserException("Class name is already in use: " + newName);
		}
		ops.add(new SchemaOperation.SchemaRename(node, cache, def, newName));
	}

    public Collection<ZooClass> getAllSchemata(Node node) {
        ArrayList<ZooClass> list = new ArrayList<ZooClass>();
        for (ZooClassDef def: cache.getSchemata(node)) {
            if (!def.jdoZooIsDeleted()) {
                list.add( getSchemaProxy(def, node) );
            }
        }
        return list;
    }

	public ZooClass declareSchema(String className, ZooClass superCls, Node node) {
		if (locateClassDefinition(className, node) != null) {
			throw new JDOUserException("This class is already defined: " + className);
		}
		
		long oid = node.getOidBuffer().allocateOid();
		
		ZooClassDef defSuper;
		if (superCls != null) {
			defSuper = ((SchemaClassProxy)superCls).getSchemaDef();
		} else {
			defSuper = locateClassDefinition(ZooPCImpl.class, node);
		}
		ZooClassDef def = ZooClassDef.createFromDatabase(className, oid, defSuper.getOid());
		def.associateSuperDef(defSuper);
		def.associateFields();
		
		cache.addSchema(def, false, node);
		ops.add(new SchemaOperation.SchemaDefine(node, def));
		return def.getApiHandle();
	}

	public ZooFieldDef addField(ZooClassDef def, String fieldName, Class<?> type, Node node) {
		def = ensureNewVersion(def);
		ZooFieldDef field = ZooFieldDef.create(def, fieldName, type);
		ops.add(new SchemaOperation.SchemaFieldDefine(node, def, field));
		return field;
	}

	public ZooFieldDef addField(ZooClassDef def, String fieldName, ZooClassDef typeDef, 
			int arrayDim, Node node) {
		def = ensureNewVersion(def);
		ZooFieldDef field = ZooFieldDef.create(def, fieldName, typeDef, arrayDim);
		ops.add(new SchemaOperation.SchemaFieldDefine(node, def, field));
		return field;
	}

	public ZooClassDef removeField(ZooFieldDef field, Node node) {
		ZooClassDef def = ensureNewVersion(field.getDeclaringType());
		//new version -- new field
		field = def.getField(field.getName()); 
		ops.add(new SchemaOperation.SchemaFieldDelete(node, def, field));
		return def;
	}

	public void renameField(ZooFieldDef field, String fieldName) {
		//We do not create a new version just for renaming.
		ZooClassDef def = field.getDeclaringType();
		Node node = def.jdoZooGetNode();
		ops.add(new SchemaOperation.SchemaFieldRename(node, field, fieldName));
		def.jdoZooMarkDirty();
	}
	
	/**
	 * Check whether to create a new version of the schema.
	 */
	private ZooClassDef ensureNewVersion(ZooClassDef def) {
		while (def.getNextVersion() != null) {
			def = def.getNextVersion();
		}
		if (def.jdoZooIsNew()) {
			//this happens for example when the super-class is modified AFTER the local class got 
			//a new version.
			def.ensureLatestSuper();
		} else {
			ZooClassDef defNew = def.newVersion(cache, null);
			ops.add(new SchemaOperation.SchemaNewVersion(def, defNew, cache));
			//cascade new versions of sub-classes
			for (ZooClassDef sub: def.getSubClassesLatestVersions()) {
				ensureNewVersion(sub);
			}
			def = defNew;
		}
		return def;
	}
}
