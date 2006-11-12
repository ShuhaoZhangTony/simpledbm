/***
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *
 *    Project: www.simpledbm.org
 *    Author : Dibyendu Majumdar
 *    Email  : dibyendu@mazumdar.demon.co.uk
 */
package org.simpledbm.rss.api.tuple;

import org.simpledbm.rss.api.tx.Transaction;

/**
 * TupleManager sub-system provides an abstraction for managing
 * data within containers. A TupleContainer is specialized for storing tuples,
 * which correspond to blobs of data. Tuples can be of arbitrary length; although
 * the implementation may impose restrictions on maximum tuple size. Tuples can
 * span multiple pages, however, this is handled by the TupleManager sub-system
 * transparently to the caller.
 * <p>
 * Each tuple is uniquely identified by a Location. When a tuple is first inserted,
 * its Location is defined. The Tuple remains at the same Location for the 
 * rest of its life.
 *   
 * @author Dibyendu Majumdar
 * @since 07-Dec-2005
 */
public interface TupleManager {

    /**
     * Creates a new Tuple Container. 
     */
	void createTupleContainer(Transaction trx, String name, int containerId, int extentSize);

    /**
     * Gets an instance of TupleContainer. Specified container must already exist.
     */
	TupleContainer getTupleContainer(int containerId);
}