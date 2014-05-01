/*
 * Copyright 2009-2014 Tilmann Zaeschke. All rights reserved.
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
package org.zoodb.internal.server.index;

import org.zoodb.internal.server.StorageChannel;


/**
 * Abstract base class for indices.
 * 
 * @author Tilmann Zaeschke
 */
abstract class AbstractIndex {

	protected final StorageChannel file;
	private boolean isDirty;
	private final boolean isUnique; 
	
	public AbstractIndex(StorageChannel file, boolean isNew, boolean isUnique) {
		this.file = file;
		this.isDirty = isNew;
		this.isUnique = isUnique;
	}
	
    public final boolean isDirty() {
        return isDirty;
    }
    
    protected final boolean isUnique() {
        return isUnique;
    }
    
	protected final void markDirty() {
		isDirty = true;
	}
	
	protected final void markClean() {
		isDirty = false;
	}
	
	public final StorageChannel getStorageChannel() {
		return file;
	}
}
