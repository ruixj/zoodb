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
package org.zoodb.jdo.api.impl;

import org.zoodb.jdo.internal.Session;

public class DBStatistics {

	private final Session s; 
	
	public DBStatistics(Session s) {
		this.s = s;
	}

	/**
	 * 
	 * @return Number of read pages since the session was created.
	 */
	public int getStoragePageReadCount() {
		return s.getPrimaryNode().getStatsPageReadCount();
	}

	/**
	 * 
	 * @return Number of written pages since the session was created. This includes pages that 
	 * are not written yet (commit pending) and pages that have been rolled back.
	 */
	public int getStoragePageWriteCount() {
		return s.getPrimaryNode().getStatsPageWriteCount();
	}

}
