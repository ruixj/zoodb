package org.zoodb.profiling.suggestion;

import java.lang.reflect.Field;

public class CollectionAggregationSuggestion extends CollectionSuggestion {
	
	private final String identifier = "AGGREGATION";
	
	/**
	 * Typename of collection items
	 * Will not be serialized --> serialization would require the class to be present (if we import these suggestions in AgileIS, this class will not be on the classpath)
	 * --> use typeName instead 
	 */
	private String collectionItemTypeName;
	
	/**
	 * Name of field in class that owns the collection (over which the aggregation took place)
	 * Field in owner of collection which holds collection
	 * Will not be serialized (same reason as above, field has reference to class object which is most likely not present upon deserialization)
	 */
	private String ownerCollectionFieldName;
	
	
	
	public String getCollectionItemTypeName() {
		return collectionItemTypeName;
	}

	public void setCollectionItemTypeName(String collectionItemTypeName) {
		this.collectionItemTypeName = collectionItemTypeName;
	}


	public String getOwnerCollectionFieldName() {
		return ownerCollectionFieldName;
	}

	public void setOwnerCollectionFieldName(String ownerCollectionFieldName) {
		this.ownerCollectionFieldName = ownerCollectionFieldName;
	}


	public String getIdentifier() {
		return identifier;
	}



	public String getText() {
		StringBuilder sb = new StringBuilder();
		
		sb.append("Aggregation over: ");
		sb.append(collectionItemTypeName);
		sb.append('.');
		sb.append(ownerCollectionFieldName);
		sb.append(" (via: ");
		sb.append(getClazzName());
		sb.append(".");
		sb.append(getFieldName());
		sb.append(" --> use aggregated field in ");
		sb.append(getClazzName());
		
		return sb.toString();
	}
	

}