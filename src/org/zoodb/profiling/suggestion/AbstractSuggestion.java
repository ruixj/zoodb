package org.zoodb.profiling.suggestion;

/**
 * @author tobiasg
 *
 */
public abstract class AbstractSuggestion {
	
	/**
	 * Class to which this suggestion belongs to
	 */
	private String clazzName;
	
	/**
	 * text description of this suggestion
	 */
	private String description;
	

	
	public String getText() {
		return description;
	}
	
	public void setText(String description) {
		this.description = description;
	}

	public String getClazzName() {
		return clazzName;
	}

	public void setClazzName(String clazzName) {
		this.clazzName = clazzName;
	}
	
		
	/**
	 * Applies suggestion to model object
	 * @param model
	 */
	public abstract void apply(Object model);
	
}