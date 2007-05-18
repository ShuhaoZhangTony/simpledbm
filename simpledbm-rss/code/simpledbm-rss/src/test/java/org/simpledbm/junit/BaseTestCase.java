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
package org.simpledbm.junit;

import java.util.Vector;

import junit.framework.TestCase;

import org.simpledbm.rss.util.logging.Logger;

public abstract class BaseTestCase extends TestCase {

	Vector<ThreadFailure> threadFailureExceptions;
	
	public BaseTestCase() {
	}

	public BaseTestCase(String arg0) {
		super(arg0);
	}

	public final void setThreadFailed(Thread thread, Exception exception) {
		threadFailureExceptions.add(new ThreadFailure(thread, exception));
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		threadFailureExceptions = new Vector<ThreadFailure>();
		Logger.configure("classpath:logging.properties");
	}

	@Override
	protected void tearDown() throws Exception {
		threadFailureExceptions = null;
		super.tearDown();
	}

	public final void checkThreadFailures() throws Exception {
		for (ThreadFailure tf: threadFailureExceptions) {
			System.err.println("Thread [" + tf.threadName + " failed");
			tf.exception.printStackTrace();
		}
		if (threadFailureExceptions.size() > 0) {
			fail(threadFailureExceptions.size() + " number of threads have failed the test");
		}
	}
	
	final static class ThreadFailure {
		Exception exception;
		String threadName;
		
		public ThreadFailure(Thread thread, Exception exception) {
			this.threadName = thread.getName();
			this.exception = exception;
		}
	}
	
}
