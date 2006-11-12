/*
 * Created on: 12-Dec-2005
 * Author: Dibyendu Majumdar
 */
package org.simpledbm.rss.impl.tuple;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;

import junit.framework.TestCase;

import org.simpledbm.rss.api.bm.BufferManager;
import org.simpledbm.rss.api.fsm.FreeSpaceManager;
import org.simpledbm.rss.api.latch.LatchFactory;
import org.simpledbm.rss.api.loc.Location;
import org.simpledbm.rss.api.locking.LockManager;
import org.simpledbm.rss.api.locking.LockMgrFactory;
import org.simpledbm.rss.api.locking.LockMode;
import org.simpledbm.rss.api.pm.Page;
import org.simpledbm.rss.api.pm.PageFactory;
import org.simpledbm.rss.api.pm.PageId;
import org.simpledbm.rss.api.registry.ObjectRegistry;
import org.simpledbm.rss.api.sp.SlottedPageManager;
import org.simpledbm.rss.api.st.Storable;
import org.simpledbm.rss.api.st.StorageContainer;
import org.simpledbm.rss.api.st.StorageContainerFactory;
import org.simpledbm.rss.api.st.StorageManager;
import org.simpledbm.rss.api.tuple.TupleContainer;
import org.simpledbm.rss.api.tuple.TupleInserter;
import org.simpledbm.rss.api.tuple.TupleManager;
import org.simpledbm.rss.api.tuple.TupleScan;
import org.simpledbm.rss.api.tx.LoggableFactory;
import org.simpledbm.rss.api.tx.Transaction;
import org.simpledbm.rss.api.tx.TransactionalModuleRegistry;
import org.simpledbm.rss.api.tx.TransactionException;
import org.simpledbm.rss.api.wal.LogManager;
import org.simpledbm.rss.impl.bm.BufferManagerImpl;
import org.simpledbm.rss.impl.fsm.FreeSpaceManagerImpl;
import org.simpledbm.rss.impl.im.btree.BTreeIndexManagerImpl;
import org.simpledbm.rss.impl.latch.LatchFactoryImpl;
import org.simpledbm.rss.impl.locking.LockManagerFactoryImpl;
import org.simpledbm.rss.impl.pm.PageFactoryImpl;
import org.simpledbm.rss.impl.registry.ObjectRegistryImpl;
import org.simpledbm.rss.impl.sp.SlottedPageManagerImpl;
import org.simpledbm.rss.impl.st.FileStorageContainerFactory;
import org.simpledbm.rss.impl.st.StorageManagerImpl;
import org.simpledbm.rss.impl.tuple.TupleManagerImpl;
import org.simpledbm.rss.impl.tx.LoggableFactoryImpl;
import org.simpledbm.rss.impl.tx.TransactionalModuleRegistryImpl;
import org.simpledbm.rss.impl.tx.TransactionManagerImpl;
import org.simpledbm.rss.impl.wal.LogFactoryImpl;
import org.simpledbm.rss.util.ByteString;
import org.simpledbm.rss.util.logging.Logger;

public class TestTupleManager extends TestCase {

    public TestTupleManager() {
        super();
    }

    public TestTupleManager(String arg0) {
        super(arg0);
    }
    
	private Properties getLogProperties() {
		Properties properties = new Properties();
		properties.setProperty("log.ctl.1", "log/control1/ctl.a");
		properties.setProperty("log.ctl.2", "log/control2/ctl.b");
		properties.setProperty("log.groups.1.path", "log/current");
		properties.setProperty("log.archive.path", "log/archive");
		properties.setProperty("log.group.files", "3");
		properties.setProperty("log.file.size", "65536");
		properties.setProperty("log.buffer.size", "65536");
		properties.setProperty("log.buffer.limit", "4");
		properties.setProperty("log.flush.interval", "30");
		properties.setProperty(FileStorageContainerFactory.BASE_PATH, "/temp/test");
		return properties;
	}    

    /**
     * Initialize the test harness. New log is created, and the test container
     * initialized. The container is allocated an extent of 64 pages which ought
     * to be large enough for all the test cases.
     */
    public void testCase1() throws Exception {

		final TupleDB db = new TupleDB(getLogProperties(), true);

        try {
            StorageContainer sc = db.storageFactory.create("dual");
            db.storageManager.register(0, sc);
            Page page = db.pageFactory.getInstance(db.pageFactory.getRawPageType(),
                    new PageId(0, 0));
            db.pageFactory.store(page);

            db.trxmgr.start();
            Transaction trx = db.trxmgr.begin();
            db.spacemgr.createContainer(trx, "testctr.dat", 1, 2, 20, db.spmgr
                    .getPageType());
            trx.commit();
            db.trxmgr.checkpoint();
        } finally {
        	db.shutdown();
        }
    }

    public void testCase2() throws Exception {

    	final TupleDB db = new TupleDB(getLogProperties(), false);

        try {
            db.trxmgr.start();
            Transaction trx = db.trxmgr.begin();
            TupleContainer tcont = db.tuplemgr.getTupleContainer(1);
            StringTuple t = new StringTuple();
            t.parseString("hello", 16524);
            TupleInserter inserter = tcont.insert(trx, t);
            Location location = inserter.getLocation();
            inserter.completeInsert();
            trx.commit();
            trx = db.trxmgr.begin();
            byte[] data = tcont.read(location);
            assertEquals(data.length, 16526);
            assertTrue(t.toString().equals("hello"));
            tcont.delete(trx, location);
            trx.abort();
            t = new StringTuple();
            t.parseString("updated hello", 18000);
            trx = db.trxmgr.begin();
            data = tcont.read(location);
            assertEquals(data.length, 16526);
            tcont.update(trx, location, t);
            data = tcont.read(location);
            t = new StringTuple();
            ByteBuffer bb = ByteBuffer.wrap(data);
            t.retrieve(bb);
            trx.commit();
            assertEquals(t.getStoredLength(), 18002);
            assertTrue(t.toString().equals("updated hello"));
        }
        finally {
        	db.shutdown();
        }       
        
    }    

    public void testCase3() throws Exception {

    	final TupleDB db = new TupleDB(getLogProperties(), false);

        try {
            db.trxmgr.start();
            
            TupleContainer tcont = db.tuplemgr.getTupleContainer(1);
            /*
             * First insert a few rows.
             */
            int[] tlens = new int[] { 18000, 15, 95, 138, 516, 1700, 4500, 13000 };
            for (int i = 1; i < tlens.length; i++) {
                Transaction trx = db.trxmgr.begin();
                StringTuple t = new StringTuple();
                t.parseString("rec" + i, tlens[i]);
                TupleInserter inserter = tcont.insert(trx, t);
                inserter.getLocation();
                inserter.completeInsert();
                trx.commit();
            }
            
            Transaction trx = db.trxmgr.begin();
            TupleScan scan = tcont.openScan(trx, LockMode.SHARED);
            int i = 0;
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
            	System.err.println("len=" + data.length);
            	assertEquals(tlens[i]+2, data.length);
                StringTuple t = new StringTuple();
                ByteBuffer bb = ByteBuffer.wrap(data);
                t.retrieve(bb);
            	System.err.println("Location=" + scan.getCurrentLocation() + ", tupleData=[" + t.toString() + "]");
            	i++;
            }
            trx.commit();
        }
        finally {
        	db.shutdown();
        }       
    }    

    Location location = null;
    
    /**
     * This test case uses two threads. The first thread creates a
     * new tuple. The second thread starts a scan and should wait for the
     * first thread to commit or abort. The first thread aborts and the 
     * second thread completes the scan.
     */
    void doTestCase4(final boolean commit) throws Exception {

    	final TupleDB db = new TupleDB(getLogProperties(), false);

        try {
            db.trxmgr.start();

            Thread thr = new Thread(new Runnable() {
				public void run() {
					TupleContainer tcont = db.tuplemgr.getTupleContainer(1);
					Transaction trx = db.trxmgr.begin();
					boolean ok = false;
					try {
						StringTuple t = new StringTuple();
						t.parseString("sample", 10000);
						TupleInserter inserter = tcont.insert(trx, t);
						inserter.getLocation();
						inserter.completeInsert();
						System.err.println("Inserted new tuple - going to sleep");
						Thread.sleep(1000);
						ok = true;
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						if (trx != null) {
							try {
								if (!commit) {
									System.err.println("Aborting tuple insert");
									trx.abort();
								}
								else {
									System.err.println("Committing tuple insert");
									trx.commit();
								}
							} catch (TransactionException e) {
								e.printStackTrace();
							}
						}
					}
				}
			});
            
            TupleContainer tcont = db.tuplemgr.getTupleContainer(1);
            /*
             * First insert a few rows.
             */
            int[] tlens;
            if (commit) {
            	tlens = new int[] { 18000, 15, 95, 138, 516, 1700, 4500, 13000, 10000 };
            }
            else {
            	tlens = new int[] { 18000, 15, 95, 138, 516, 1700, 4500, 13000 };
            }
            Transaction trx = db.trxmgr.begin();
            TupleScan scan = tcont.openScan(trx, LockMode.SHARED);
            
            thr.start();
            Thread.sleep(100);
            
            int i = 0;
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
            	System.err.println("len=" + data.length);
            	assertEquals(tlens[i]+2, data.length);
                StringTuple t = new StringTuple();
                ByteBuffer bb = ByteBuffer.wrap(data);
                t.retrieve(bb);
            	System.err.println("Location=" + scan.getCurrentLocation() + ", tupleData=[" + t.toString() + "]");
            	if (location == null && i > (tlens.length/2)) {
            		location = scan.getCurrentLocation();
            	}
            	i++;
            	System.err.println("Fetching next tuple");
            }
        	System.err.println("Scan completed");
            trx.commit();
            
            thr.join(2000);
            assertTrue(!thr.isAlive());
        }
        finally {
        	db.shutdown();
        }       
    }    

    public void testCase4() throws Exception {
    	doTestCase4(false);
    }
    
    public void testCase5() throws Exception {
    	doTestCase4(true);
    }

    public void doTestUndoUpdate() throws Exception {
    	final TupleDB db = new TupleDB(getLogProperties(), false);

        try {
            db.trxmgr.start();
            Transaction trx = db.trxmgr.begin();
            TupleContainer tcont = db.tuplemgr.getTupleContainer(1);
            TupleScan scan = tcont.openScan(trx, LockMode.UPDATE);
            int i = 0;
            ArrayList<Integer> lens = new ArrayList<Integer>();
            ArrayList<Integer> newLens = new ArrayList<Integer>();
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
            	System.err.println("len=" + data.length);
        		lens.add(data.length);
            	if (data.length < 100) {
            		Location location = scan.getCurrentLocation();
                    StringTuple t = new StringTuple();
                    t.parseString("updating tuple " + location.toString(), 16524);
                    tcont.update(trx, location, t);
                    newLens.add(16524+2);
            	}
            	else {
            		newLens.add(data.length);
            	}
            }
            scan.close();
            
            scan = tcont.openScan(trx, LockMode.UPDATE);
            i = 0;
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
            	System.err.println("len=" + data.length);
        		assertEquals(new Integer(data.length), newLens.get(i));
        		i++;
            }
            scan.close();
            trx.abort();

            trx = db.trxmgr.begin();
            scan = tcont.openScan(trx, LockMode.SHARED);
            i = 0;
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
            	System.err.println("len=" + data.length);
        		assertEquals(new Integer(data.length), lens.get(i));
        		i++;
            }
            scan.close();
            trx.commit();
        }
        finally {
        	db.shutdown();
        }       
    }

    public void testCase6() throws Exception {
    	doTestUndoUpdate();
    }

    /**
     * This test opens an UPDATE scan and deletes tuples that exceed 100 bytes.
     * It then does another scan to verify that the transaction does not see the deleted tuples.
     * The transaction is committed and another scan is performed to verify.
     * 20 new tuples are added, increasing in size. This is meant to exercise the
     * reclaiming of deleted tuples, as well as trigger a container extension.
     * Transaction is committed and a scan performed to verify the data. 
     */
    public void doTestDeleteInsertScan() throws Exception {
    	final TupleDB db = new TupleDB(getLogProperties(), false);

        try {
            db.trxmgr.start();
            Transaction trx = db.trxmgr.begin();
            TupleContainer tcont = db.tuplemgr.getTupleContainer(1);
            TupleScan scan = tcont.openScan(trx, LockMode.UPDATE);
            int n_total = 0;
            int n_deleted = 0;
            ArrayList<Integer> lens = new ArrayList<Integer>();
            HashMap<Location, Integer> map = new HashMap<Location, Integer>();
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
        		Location location = scan.getCurrentLocation();
            	System.err.println("len=" + data.length);
            	if (data.length > 100) {
            		tcont.delete(trx, location);
                    System.err.println("Deleted tuple at location " + location);
            		n_deleted++;
            	}
            	else {
            		lens.add(data.length);
            		map.put(location, data.length);
            	}
            	n_total++;
            }
            scan.close();
            
            System.err.println("Map = " + map);
            
            scan = tcont.openScan(trx, LockMode.SHARED);
            int j = 0;
            while (scan.fetchNext()) {
        		Location location = scan.getCurrentLocation();
                System.err.println("After delete: Location " + location);
                assertTrue(map.get(location) != null);
                j++;
            }
            scan.close();
            trx.commit();
            assertEquals(j, n_total-n_deleted);
            
            trx = db.trxmgr.begin();
            scan = tcont.openScan(trx, LockMode.SHARED);
            int i = 0;
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
            	location = scan.getCurrentLocation();
            	System.err.println("len=" + data.length);
                assertTrue(map.get(location) != null);
        		assertEquals(new Integer(data.length), lens.get(i));
        		i++;
            }
            scan.close();
            trx.commit();
            assertEquals(i, n_total-n_deleted);
            
            n_total -= n_deleted;
            
            trx = db.trxmgr.begin();
            int len = 100;
            for (i = 0; i < 20; i++) {
                StringTuple t = new StringTuple();
                t.parseString("hello " + i, len);
                TupleInserter inserter = tcont.insert(trx, t);
                Location location = inserter.getLocation();
                map.put(location, len+2);
                System.err.println("Tuple [" + t.toString() + "] of length " + len + " inserted at location " + location);
                inserter.completeInsert();
                n_total++;
                len += 1000;
            }
            trx.commit();
            
            trx = db.trxmgr.begin();
            scan = tcont.openScan(trx, LockMode.SHARED);
            i = 0;
            while (scan.fetchNext()) {
            	byte[] data = scan.getCurrentTuple();
            	location = scan.getCurrentLocation();
            	System.err.println("Tuple " + location + ", length=" + data.length);
            	assertTrue(map.get(location) != null);
        		i++;
            }
            scan.close();
            trx.commit();
            assertEquals(i, n_total);
        }
        finally {
        	db.shutdown();
        }       
    }
    
    public void testCase7() throws Exception {
    	doTestDeleteInsertScan();
    }
    
    
    public static class StringTuple implements Storable {

        ByteString string = new ByteString();

        public void setString(String s) {
            parseString(s);
        }

        public void setBytes(byte[] bytes) {
            string = new ByteString(bytes);
        }

        @Override
        public String toString() {
            return string.toString().trim();
        }

        public void parseString(String string) {
            byte data[] = new byte[16524];
            Arrays.fill(data, (byte) ' ');
            byte[] srcdata = string.getBytes();
            System.arraycopy(srcdata, 0, data, 0, srcdata.length);
            this.string = new ByteString(data);
        }

        public void parseString(String string, int padLength) {
            byte data[] = new byte[padLength];
            Arrays.fill(data, (byte) ' ');
            byte[] srcdata = string.getBytes();
            System.arraycopy(srcdata, 0, data, 0, srcdata.length);
            this.string = new ByteString(data);
        }

        public int getStoredLength() {
            return string.getStoredLength();
        }

        public void retrieve(ByteBuffer bb) {
            string = new ByteString();
            string.retrieve(bb);
        }

        public void store(ByteBuffer bb) {
            string.store(bb);
        }
    }

    public static class TupleDB {
        final LogFactoryImpl logFactory;
        final ObjectRegistry objectFactory;
        final StorageContainerFactory storageFactory;
        final StorageManager storageManager;
        final LatchFactory latchFactory;
        final PageFactory pageFactory;
        final SlottedPageManager spmgr;
        final LockMgrFactory lockmgrFactory;
        final LockManager lockmgr;
        final LogManager logmgr;
        final BufferManager bufmgr;
        final LoggableFactory loggableFactory;
        final TransactionalModuleRegistry moduleRegistry;
		final TransactionManagerImpl trxmgr;
        final FreeSpaceManager spacemgr;
        final BTreeIndexManagerImpl btreeMgr;
        final TupleManager tuplemgr;

        public TupleDB(Properties props, boolean create) throws Exception {

        	Logger.configure("logging.properties");
			storageFactory = new FileStorageContainerFactory(props);
        	logFactory = new LogFactoryImpl();
        	if (create) {
        		logFactory.createLog(storageFactory, props);
        	}
			objectFactory = new ObjectRegistryImpl();
			storageManager = new StorageManagerImpl();
			latchFactory = new LatchFactoryImpl();
			pageFactory = new PageFactoryImpl(objectFactory, storageManager, latchFactory);
			spmgr = new SlottedPageManagerImpl(objectFactory);
			lockmgrFactory = new LockManagerFactoryImpl();
			lockmgr = lockmgrFactory.create(null);
			logmgr = logFactory.getLog(storageFactory, props);
			logmgr.start();
			bufmgr = new BufferManagerImpl(logmgr, pageFactory, 5, 11);
			bufmgr.start();
			loggableFactory = new LoggableFactoryImpl(objectFactory);
			moduleRegistry = new TransactionalModuleRegistryImpl();
			trxmgr = new TransactionManagerImpl(logmgr, storageFactory, storageManager, bufmgr, lockmgr, loggableFactory, latchFactory, objectFactory, moduleRegistry);
			spacemgr = new FreeSpaceManagerImpl(objectFactory, pageFactory, logmgr, bufmgr, storageManager, storageFactory, loggableFactory, trxmgr, moduleRegistry);
			btreeMgr = new BTreeIndexManagerImpl(objectFactory, loggableFactory, spacemgr, bufmgr, spmgr, moduleRegistry);
	        tuplemgr = new TupleManagerImpl(objectFactory, loggableFactory, spacemgr, bufmgr, spmgr, moduleRegistry, pageFactory);
		}
        
        public void shutdown() {
        	trxmgr.shutdown();
        	bufmgr.shutdown();
			logmgr.shutdown();
			storageManager.shutdown();
        }        
    }
    
}