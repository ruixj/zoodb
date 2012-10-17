package org.zoodb.profiling.suggestion;

import java.util.ArrayList;
import java.util.Collection;

import org.zoodb.profiling.api.impl.AbstractSuggestion;

public class FieldSuggestion extends AbstractSuggestion {
	
	private Class clazz;
	
	private Collection<String> unusedFields;
	
	public FieldSuggestion(Class clazz) {
		this.clazz = clazz;
		this.unusedFields = new ArrayList<String>();
	}
	
	public void addUnusedFieldName(String fieldName) {
		this.unusedFields.add(fieldName);
	}
	
	public String getText() {
		StringBuilder sb = new StringBuilder();
		
		sb.append("The following fields of class '");
		sb.append(clazz.getName());
		sb.append(" have never been accessed: ");
		
		for (String fieldName : unusedFields) {
			sb.append(fieldName);
			sb.append(",");
		}
		sb.append(". Consider removing them.");
		return sb.toString();
	}
}
