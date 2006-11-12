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
package org.simpledbm.rss.impl.im.btree;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.simpledbm.rss.api.bm.BufferAccessBlock;
import org.simpledbm.rss.api.bm.BufferManager;
import org.simpledbm.rss.api.bm.BufferManagerException;
import org.simpledbm.rss.api.fsm.FreeSpaceChecker;
import org.simpledbm.rss.api.fsm.FreeSpaceCursor;
import org.simpledbm.rss.api.fsm.FreeSpaceManager;
import org.simpledbm.rss.api.fsm.FreeSpaceManagerException;
import org.simpledbm.rss.api.fsm.FreeSpaceMapPage;
import org.simpledbm.rss.api.im.Index;
import org.simpledbm.rss.api.im.IndexException;
import org.simpledbm.rss.api.im.IndexKey;
import org.simpledbm.rss.api.im.IndexKeyFactory;
import org.simpledbm.rss.api.im.IndexManager;
import org.simpledbm.rss.api.im.IndexScan;
import org.simpledbm.rss.api.im.UniqueConstraintViolationException;
import org.simpledbm.rss.api.loc.Location;
import org.simpledbm.rss.api.loc.LocationFactory;
import org.simpledbm.rss.api.locking.LockDuration;
import org.simpledbm.rss.api.locking.LockMode;
import org.simpledbm.rss.api.pm.Page;
import org.simpledbm.rss.api.pm.PageId;
import org.simpledbm.rss.api.registry.ObjectRegistry;
import org.simpledbm.rss.api.registry.ObjectRegistryAware;
import org.simpledbm.rss.api.sp.SlottedPage;
import org.simpledbm.rss.api.sp.SlottedPageManager;
import org.simpledbm.rss.api.st.Storable;
import org.simpledbm.rss.api.tx.BaseLoggable;
import org.simpledbm.rss.api.tx.BaseTransactionalModule;
import org.simpledbm.rss.api.tx.Compensation;
import org.simpledbm.rss.api.tx.LoggableFactory;
import org.simpledbm.rss.api.tx.LogicalUndo;
import org.simpledbm.rss.api.tx.MultiPageRedo;
import org.simpledbm.rss.api.tx.Redoable;
import org.simpledbm.rss.api.tx.Savepoint;
import org.simpledbm.rss.api.tx.Transaction;
import org.simpledbm.rss.api.tx.TransactionException;
import org.simpledbm.rss.api.tx.TransactionalModuleRegistry;
import org.simpledbm.rss.api.tx.Undoable;
import org.simpledbm.rss.api.wal.Lsn;
import org.simpledbm.rss.util.ClassUtils;
import org.simpledbm.rss.util.TypeSize;
import org.simpledbm.rss.util.logging.DiagnosticLogger;
import org.simpledbm.rss.util.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * B-link Tree implementation is based upon the algorithms described in <cite>
 * Ibrahim Jaluta, Seppo Sippu and Eljas Soisalon-Soininen. Concurrency control 
 * and recovery for balanced B-link trees. The VLDB Journal, Volume 14, Issue 2 
 * (April 2005), Pages: 257 - 277, ISSN:1066-8888.</cite> There are some variations
 * from the published algorithms - these are noted at appropriate places within the
 * code. 
 * <p>
 * Quick Notes:
 * 1. If we want to maintain left sibling pointers in pages, then during merge operations, we 
 * need to access the page further to the right of right sibling, and update this page
 * as well. 
 * 2. When copying keys from one page to another it is not necessary to instantiate keys. A more efficient way
 * would be to copy raw data. Current method is inefficient.
 * <p>
 * <h2>Structure of the B-link Tree</h2>
 * <ol>
 * <li>The tree is contained in a container of fixed size pages. The
 * first page of the container is a header page. The second page is the
 * first space map page. The third page (pagenumber = 2) is always allocated as 
 * the root page of the tree.
 * </li>
 * <li>Pages at all levels are linked to their right siblings.</li>
 * <li>In leaf pages, an extra item called the high key is present. In index pages,
 * the last key acts as the highkey. All keys in a page are guaranteed to be &lt;= 
 * than the highkey. Note that in leaf pages the highkey may not be the same as
 * the last key in the page.</li>
 * <li>In index pages, each key contains a pointer to a child page. The child page contains
 * keys &lt;= to the key in the index page. The highkey of the child page will match the
 * index key if the child is a direct child. The highkey of the child page will be &lt; than
 * the index key if the child has a sibling that is an indirect child.
 * </li>
 * <li>All pages other than root must have at least two items (excluding highkey in leaf pages). </li>
 * <li>The rightmost key at any level is a special key containing logical INFINITY. Initially, the empty
 * tree contains this key only. As the tree grows through splitting of pages, the INFINITY key is carried
 * forward to the rightmost pages at each level of the tree. This key can never be deleted from the
 * tree.</li> 
 * </ol>
 * 
 * @author Dibyendu Majumdar
 * @since 18-Sep-2005
 */
public final class BTreeIndexManagerImpl extends BaseTransactionalModule implements IndexManager {

	static final String LOG_CLASS_NAME = BTreeIndexManagerImpl.class.getName();
	static final Logger log = Logger.getLogger(BTreeIndexManagerImpl.class.getPackage().getName());

	private static final short MODULE_ID = 4;
	
	private static final short TYPE_BASE = MODULE_ID * 100;
	private static final short TYPE_SPLIT_OPERATION = TYPE_BASE + 1;
	private static final short TYPE_MERGE_OPERATION = TYPE_BASE + 3;
	private static final short TYPE_LINK_OPERATION = TYPE_BASE + 4;
	private static final short TYPE_UNLINK_OPERATION = TYPE_BASE + 5;
	private static final short TYPE_REDISTRIBUTE_OPERATION = TYPE_BASE + 6;
	private static final short TYPE_INCREASETREEHEIGHT_OPERATION = TYPE_BASE + 7;
	private static final short TYPE_DECREASETREEHEIGHT_OPERATION = TYPE_BASE + 8;
	private static final short TYPE_INSERT_OPERATION = TYPE_BASE + 9;
	private static final short TYPE_UNDOINSERT_OPERATION = TYPE_BASE + 10;
	private static final short TYPE_DELETE_OPERATION = TYPE_BASE + 11;
	private static final short TYPE_UNDODELETE_OPERATION = TYPE_BASE + 12;
	private static final short TYPE_LOADPAGE_OPERATION = TYPE_BASE + 13;
	
	/**
	 * Space map value for an unused BTree page.
	 */
	private static final int PAGE_SPACE_FREE = 0;
	/**
	 * Space map value for a used BTree page.
	 */
	private static final int PAGE_SPACE_USED = 1;
	
	/**
	 * Root page is always the third page in a container.
	 */
	private static final int ROOT_PAGE_NUMBER = 2;
	
	final ObjectRegistry objectFactory;
	
	final LoggableFactory loggableFactory;
	
	final FreeSpaceManager spaceMgr;
	
	final BufferManager bufmgr;

    final SlottedPageManager spMgr;
    
    public static int testingFlag = 0;
    
	public BTreeIndexManagerImpl(ObjectRegistry objectFactory, LoggableFactory loggableFactory, FreeSpaceManager spaceMgr, BufferManager bufMgr, SlottedPageManager spMgr, TransactionalModuleRegistry moduleRegistry) {
		this.objectFactory = objectFactory;
		this.loggableFactory = loggableFactory;
		this.spaceMgr = spaceMgr;
		this.bufmgr = bufMgr;
        this.spMgr = spMgr;

		moduleRegistry.registerModule(MODULE_ID, this);
		
		objectFactory.register(TYPE_SPLIT_OPERATION, SplitOperation.class.getName());
		objectFactory.register(TYPE_MERGE_OPERATION, MergeOperation.class.getName());
		objectFactory.register(TYPE_LINK_OPERATION, LinkOperation.class.getName());
		objectFactory.register(TYPE_UNLINK_OPERATION, UnlinkOperation.class.getName());
		objectFactory.register(TYPE_REDISTRIBUTE_OPERATION, RedistributeOperation.class.getName());
		objectFactory.register(TYPE_INCREASETREEHEIGHT_OPERATION, IncreaseTreeHeightOperation.class.getName());	
		objectFactory.register(TYPE_DECREASETREEHEIGHT_OPERATION, DecreaseTreeHeightOperation.class.getName());
		objectFactory.register(TYPE_INSERT_OPERATION, InsertOperation.class.getName());
		objectFactory.register(TYPE_UNDOINSERT_OPERATION, UndoInsertOperation.class.getName());
		objectFactory.register(TYPE_DELETE_OPERATION, DeleteOperation.class.getName());
		objectFactory.register(TYPE_UNDODELETE_OPERATION, UndoDeleteOperation.class.getName());
		objectFactory.register(TYPE_LOADPAGE_OPERATION, LoadPageOperation.class.getName());
	}

	/* (non-Javadoc)
	 * @see org.simpledbm.rss.tm.TransactionalModule#redo(org.simpledbm.rss.pm.Page, org.simpledbm.rss.tm.Redoable)
	 */
	@Override
	public final void redo(Page page, Redoable loggable) throws TransactionException, FreeSpaceManagerException {
		if (loggable instanceof SplitOperation) {
			redoSplitOperation(page, (SplitOperation) loggable);
		}
		else if (loggable instanceof MergeOperation) {
			redoMergeOperation(page, (MergeOperation) loggable);
		}
		else if (loggable instanceof LinkOperation) {
			redoLinkOperation(page, (LinkOperation) loggable);
		}
		else if (loggable instanceof UnlinkOperation) {
			redoUnlinkOperation(page, (UnlinkOperation) loggable);
		}
		else if (loggable instanceof RedistributeOperation) {
			redoRedistributeOperation(page, (RedistributeOperation) loggable);
		}
		else if (loggable instanceof IncreaseTreeHeightOperation) {
			redoIncreaseTreeHeightOperation(page, (IncreaseTreeHeightOperation) loggable);
		}
		else if (loggable instanceof DecreaseTreeHeightOperation) {
			redoDecreaseTreeHeightOperation(page, (DecreaseTreeHeightOperation) loggable);
		}
		else if (loggable instanceof InsertOperation) {
			redoInsertOperation(page, (InsertOperation) loggable);
		}
		else if (loggable instanceof UndoInsertOperation) {
			redoUndoInsertOperation(page, (UndoInsertOperation) loggable);
		}
		else if (loggable instanceof DeleteOperation) {
			redoDeleteOperation(page, (DeleteOperation) loggable);
		}
		else if (loggable instanceof UndoDeleteOperation) {
			redoUndoDeleteOperation(page, (UndoDeleteOperation) loggable);
		}
		else if (loggable instanceof LoadPageOperation) {
			redoLoadPageOperation(page, (LoadPageOperation) loggable);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.simpledbm.rss.tm.TransactionalModule#undo(org.simpledbm.rss.tm.Transaction, org.simpledbm.rss.tm.Undoable)
	 */
	@Override
	public final void undo(Transaction trx, Undoable undoable) throws TransactionException, BufferManagerException, FreeSpaceManagerException {
		if (undoable instanceof InsertOperation) {
			undoInsertOperation(trx, (InsertOperation) undoable);
		}
		else if (undoable instanceof DeleteOperation) {
			undoDeleteOperation(trx, (DeleteOperation) undoable);
		}
	}

	/**
	 * Redo a LoadPageOperation. A LoadPageOperation is used to log the actions of
	 * the XMLLoader which reads an XML file and generates a B-Tree. It is also used when 
	 * initializing a new BTree.
	 * 
	 * @see #createIndex(Transaction, String, int, int, int, int, boolean)
	 * @see XMLLoader 
	 */
	private void redoLoadPageOperation(Page page, LoadPageOperation loadPageOp) throws FreeSpaceManagerException {
		/*
		 * A LoadPageOperation is applied to couple of pages: the BTree page being initialised
		 * and the Space Map page that contains the used/unused status for the BTree page.
		 */
		if (page.getPageId().getPageNumber() == loadPageOp.getSpaceMapPageNumber()) {
			/*
			 * Log record is being applied to space map page.
			 */
			FreeSpaceMapPage smp = (FreeSpaceMapPage) page;
			// update space allocation data
			smp.setSpaceBits(loadPageOp.getPageId().getPageNumber(), PAGE_SPACE_USED);
		} else if (page.getPageId().getPageNumber() == loadPageOp.getPageId().getPageNumber()) {
			/*
			 * Log record is being applied to BTree page.
			 */
			SlottedPage r = (SlottedPage) page;
			r.init();
			formatPage(r, loadPageOp.getKeyFactoryType(), loadPageOp.getLocationFactoryType(), loadPageOp.isLeaf(), loadPageOp.isUnique());
			r.setSpaceMapPageNumber(loadPageOp.getSpaceMapPageNumber());
			BTreeNode node = new BTreeNode(loadPageOp);
			node.wrap(r);
			int k = 1; // 0 is position of header item
			for (IndexItem item : loadPageOp.items) {
				node.replace(k++, item);
			}
			node.header.keyCount = loadPageOp.items.size();
			node.header.leftSibling = loadPageOp.leftSibling;
			node.header.rightSibling = loadPageOp.rightSibling;
			node.updateHeader();

			node.dump();
		}
	}
	
	/**
	 * Redo a page split operation. 
	 * @see BTreeImpl#doSplit(Transaction, BTreeCursor)
	 * @see SplitOperation
	 */
	private void redoSplitOperation(Page page, SplitOperation splitOperation) {
		if (page.getPageId().equals(splitOperation.getPageId())) {
			// q is the page to be split
			SlottedPage q = (SlottedPage) page;
			BTreeNode leftSibling = new BTreeNode(splitOperation);
			leftSibling.wrap(q);
			leftSibling.header.rightSibling = splitOperation.newSiblingPageNumber;
			if (splitOperation.isLeaf()) {
				// update the high key
				leftSibling.replace(splitOperation.newKeyCount, splitOperation.highKey);
			}
			leftSibling.header.keyCount = splitOperation.newKeyCount;
			leftSibling.updateHeader();
			// get rid of the remaining keys
			while (q.getNumberOfSlots() > leftSibling.header.keyCount+1) {
				q.purge(q.getNumberOfSlots()-1);
			}
			
			leftSibling.dump();
		}
		else {
			// r is the newly allocated right sibling of q
			SlottedPage r = (SlottedPage) page;
			r.init();
			formatPage(r, splitOperation.getKeyFactoryType(), splitOperation.getLocationFactoryType(), splitOperation.isLeaf(), splitOperation.isUnique());
			r.setSpaceMapPageNumber(splitOperation.spaceMapPageNumber);
			BTreeNode newSiblingNode = new BTreeNode(splitOperation);
			newSiblingNode.wrap(r);
			newSiblingNode.header.leftSibling = splitOperation.getPageId().getPageNumber();
			newSiblingNode.header.rightSibling = splitOperation.rightSibling;
			int k = 1;		// 0 is position of header item
			for (IndexItem item: splitOperation.items) {
				newSiblingNode.replace(k++, item);
			}
			newSiblingNode.header.keyCount = splitOperation.items.size();
			newSiblingNode.updateHeader();
			
			newSiblingNode.dump();
		}
	}

	/**
	 * Redo a merge operation.
	 * @throws FreeSpaceManagerException 
	 * @see BTreeImpl#doMerge(Transaction, BTreeCursor) 
	 * @see MergeOperation
	 */
	private void redoMergeOperation(Page page, MergeOperation mergeOperation) throws FreeSpaceManagerException {
		if (page.getPageId().getPageNumber() == mergeOperation.rightSiblingSpaceMapPage) {
			FreeSpaceMapPage smp = (FreeSpaceMapPage) page;
			// deallocate
			smp.setSpaceBits(mergeOperation.rightSibling, 0);
		}
		else if (page.getPageId().getPageNumber() == mergeOperation.getPageId().getPageNumber()) {
			// left sibling - this page will aborb contents of right sibling.
			SlottedPage q = (SlottedPage) page;
			BTreeNode leftSibling = new BTreeNode(mergeOperation);
			leftSibling.wrap(q);
			int k;
			if (leftSibling.isLeaf()) {
				// delete the high key
				k = leftSibling.header.keyCount;
				q.delete(leftSibling.header.keyCount);
			}
			else {
				k = leftSibling.header.keyCount+1;
			}
			for (IndexItem item: mergeOperation.items) {
				q.insertAt(k++, item, true);
			}
			leftSibling.header.keyCount += mergeOperation.items.size() - (leftSibling.isLeaf() ? 1 : 0);
			leftSibling.header.rightSibling = mergeOperation.rightRightSibling;
			leftSibling.updateHeader();
			
			leftSibling.dump();
		}
		else if (page.getPageId().getPageNumber() == mergeOperation.rightSibling) {
			// mark right sibling as deallocated
			SlottedPage p = (SlottedPage) page;
			short flags = p.getFlags();
			p.setFlags((short) (flags | BTreeNode.NODE_TREE_DEALLOCATED));
		}
	}

	/**
	 * Redo a link operation
	 * @see BTreeImpl#doLink(Transaction, BTreeCursor)
	 * @see LinkOperation
	 */
	private void redoLinkOperation(Page page, LinkOperation linkOperation) throws TransactionException {
		SlottedPage p = (SlottedPage) page;
		BTreeNode parent = new BTreeNode(linkOperation);
		parent.wrap(p);
		int k = 0;
		for (k = 1; k <= parent.header.keyCount; k++) {
			IndexItem item = parent.getItem(k);
			// Change the index entry of left child to point to right child
			if (item.getChildPageNumber() == linkOperation.leftSibling) {
				item.setChildPageNumber(linkOperation.rightSibling);
				p.insertAt(k, item, true);
				break;
			}
		}
		if (k > parent.header.keyCount) {
			throw new TransactionException();
		}
		// Insert new entry for left child
		IndexItem u = linkOperation.leftChildHighKey;
		p.insertAt(k, u, false);
		parent.header.keyCount = parent.header.keyCount + 1;
		parent.updateHeader();

		parent.dump();
	}

	/**
	 * Redo an unlink operation.
	 * @see BTreeImpl#doUnlink(Transaction, BTreeCursor)
	 * @see UnlinkOperation 
	 */
	private void redoUnlinkOperation(Page page, UnlinkOperation unlinkOperation) throws TransactionException {
		SlottedPage p = (SlottedPage) page;
		BTreeNode parent = new BTreeNode(unlinkOperation);
		parent.wrap(p);
		int k = 0;
		for (k = 1; k <= parent.header.keyCount; k++) {
			IndexItem item = parent.getItem(k);
			if (item.getChildPageNumber() == unlinkOperation.leftSibling) {
				break;
			}
		}
		assert k <= parent.header.keyCount;
		if (k > parent.header.keyCount) {
			throw new TransactionException();
		}
		p.purge(k);
		IndexItem item = parent.getItem(k);
		if (item.getChildPageNumber() == unlinkOperation.rightSibling) {
			item.setChildPageNumber(unlinkOperation.leftSibling);
			p.insertAt(k, item, true);
		}
		else {
			throw new TransactionException();
		}
		parent.header.keyCount = parent.header.keyCount - 1;
		parent.updateHeader();

		parent.dump();
	}
	
	/**
	 * Redo a distribute operation. 
	 * @see BTreeImpl#doRedistribute(Transaction, BTreeCursor)
	 * @see RedistributeOperation
	 */
	private void redoRedistributeOperation(Page page, RedistributeOperation redistributeOperation) {
		SlottedPage p = (SlottedPage) page;
		BTreeNode node = new BTreeNode(redistributeOperation);
		node.wrap(p);
		if (page.getPageId().getPageNumber() == redistributeOperation.leftSibling) {
			// processing Q
			if (redistributeOperation.targetSibling == redistributeOperation.leftSibling) {
				// moving key left
				// the new key will become the high key
				// FIXME Test case
				node.header.keyCount = node.header.keyCount + 1;
				node.updateHeader();
				p.insertAt(node.header.keyCount, redistributeOperation.key, true);
				if (redistributeOperation.isLeaf()) {
					p.insertAt(node.header.keyCount-1, redistributeOperation.key, true);
				}
			}
			else {
				// moving key right
				// delete current high key
				p.purge(node.header.keyCount);
				node.header.keyCount = node.header.keyCount - 1;
				node.updateHeader();
				if (redistributeOperation.isLeaf()) {
					// previous key becomes new high key
					IndexItem prevKey = node.getItem(node.header.keyCount - 1);
					p.insertAt(node.header.keyCount, prevKey, true);
				}
			}
		}
		else {
			// processing R
			if (redistributeOperation.targetSibling == redistributeOperation.leftSibling) {
				// moving key left
				// delete key from position 1
				// FIXME Test case
				p.purge(1);
				node.header.keyCount = node.header.keyCount - 1;
				node.updateHeader();
			}
			else {
				// moving key right
				// insert new key at position 1
				p.insertAt(1, redistributeOperation.key, false);
				node.header.keyCount = node.header.keyCount + 1;
				node.updateHeader();
			}
		}
		
		node.dump();
	}

	/**
	 * Redo an increase tree height operation.
	 * @see BTreeImpl#doIncreaseTreeHeight(Transaction, BTreeCursor)
	 * @see IncreaseTreeHeightOperation 
	 */
	private void redoIncreaseTreeHeightOperation(Page page, IncreaseTreeHeightOperation ithOperation) {
		SlottedPage p = (SlottedPage) page;
		BTreeNode node = new BTreeNode(ithOperation);
		if (p.getPageId().equals(ithOperation.getPageId())) {
			// root page
			// get rid of existing entries by reinitializing page
			int savedPageNumber = p.getSpaceMapPageNumber();
			p.init();
			p.setSpaceMapPageNumber(savedPageNumber);
			formatPage(p, ithOperation.getKeyFactoryType(), ithOperation.getLocationFactoryType(), false, ithOperation.isUnique());
			node.wrap(p);
			node.insert(1, ithOperation.rootItems.get(0));
			node.insert(2, ithOperation.rootItems.get(1));
			node.header.keyCount = 2;
			node.updateHeader();
		}
		else if (p.getPageId().getPageNumber() == ithOperation.leftSibling) {
			// new left sibling
			p.init();
			formatPage(p, ithOperation.getKeyFactoryType(), ithOperation.getLocationFactoryType(), ithOperation.isLeaf(), ithOperation.isUnique());
			p.setSpaceMapPageNumber(ithOperation.spaceMapPageNumber);
			node.wrap(p);
			int k = 1;		// 0 is position of header item
			for (IndexItem item: ithOperation.items) {
				node.replace(k++, item);
			}
			node.header.rightSibling = ithOperation.rightSibling;
			node.header.keyCount = ithOperation.items.size();
			node.updateHeader();
		}
		node.dump();
	}

	/**
	 * Decrease tree height when root page has only one child and that child does not
	 * have a sibling.
	 * @throws FreeSpaceManagerException 
	 * @see BTreeImpl#doDecreaseTreeHeight(org.simpledbm.rss.api.tx.Transaction, org.simpledbm.rss.impl.im.btree.BTreeIndexManagerImpl.BTreeCursor)
	 * @see DecreaseTreeHeightOperation
	 */
	private void redoDecreaseTreeHeightOperation(Page page, DecreaseTreeHeightOperation dthOperation) throws FreeSpaceManagerException {
		if (page.getPageId().getPageNumber() == dthOperation.childPageSpaceMap) {
			// This is not executed if the space map is updated
			// as a separate action. But we leave this code here in case we 
			// wish to update the space map as part of the same action.
			FreeSpaceMapPage smp = (FreeSpaceMapPage) page;
			// deallocate
			smp.setSpaceBits(dthOperation.childPageNumber, 0);
		}
		else if (page.getPageId().getPageNumber() == dthOperation.getPageId().getPageNumber()) {
			// root page 
			// delete contents and absorb contents of only child
			SlottedPage p = (SlottedPage) page;
			BTreeNode node = new BTreeNode(dthOperation);
			int savedPageNumber = p.getSpaceMapPageNumber();
			p.init();
			p.setSpaceMapPageNumber(savedPageNumber);
			// Leaf page status must be replicated from child page
			formatPage(p, dthOperation.getKeyFactoryType(), dthOperation.getLocationFactoryType(), dthOperation.isLeaf(), dthOperation.isUnique());
			node.wrap(p);
			int k = 1;
			// add the keys from child page
			for (IndexItem item: dthOperation.items) {
				p.insertAt(k++, item, true);
			}
			node.header.keyCount = dthOperation.items.size();
			node.updateHeader();
			
			node.dump();
		}
		else if (page.getPageId().getPageNumber() == dthOperation.childPageNumber) {
			// mark child page as deallocated. 
			SlottedPage p = (SlottedPage) page;
			short flags = p.getFlags();
			p.setFlags((short) (flags | BTreeNode.NODE_TREE_DEALLOCATED));
		}	
	}

	private void redoInsertOperation(Page page, InsertOperation insertOp) {
		SlottedPage p = (SlottedPage) page;
		BTreeNode node = new BTreeNode(insertOp);
		node.wrap(p);
		SearchResult sr = node.search(insertOp.getItem());
		assert !sr.exactMatch;
		if (sr.k == -1) {
			// The new key is greater than all keys in the node
			// Must still be <= highkey
			assert node.getHighKey().compareTo(insertOp.getItem()) >= 0;
			sr.k = node.header.keyCount;
		}
		node.insert(sr.k, insertOp.getItem());
		node.header.keyCount = node.header.keyCount + 1;
		node.updateHeader();
		
		node.dump();
	}
	
	private void redoUndoInsertOperation(Page page, UndoInsertOperation undoInsertOp) {
		SlottedPage p = (SlottedPage) page;
		BTreeNode node = new BTreeNode(undoInsertOp);
		node.wrap(p);
		node.page.purge(undoInsertOp.getPosition());
		node.header.keyCount = node.header.keyCount - 1;
		node.updateHeader();

		node.dump();
	}
	
	private void undoInsertOperation(Transaction trx, InsertOperation insertOp) throws BufferManagerException, TransactionException, FreeSpaceManagerException {
//		Undo-insert(T,P,k,m) { X-latch(P);
//		if (P still contains r and will not underflow if r is deleted) { Q = P;
//		} else { unlatch(P); update-mode-traverse(k,Q);
//		upgrade-latch(Q);
//		}delete r from Q;
//		log(n, <T, undo-insert, Q, k, m>);
//		Page-LSN(Q) = n; Undo-Next-LSN(T) = m; unlatch(Q);
//		}
		
		BTreeCursor bcursor = new BTreeCursor();
		bcursor.setP(bufmgr.fixExclusive(insertOp.getPageId(), false, -1, 0));
		try {
			SlottedPage p = (SlottedPage) bcursor.getP().getPage();
			BTreeNode node = new BTreeNode(insertOp);
			node.wrap(p);
			
			/*
			 * FIXME - are these asserts valid? 
			 * Page may have been legitimately deleted by the time the undo operation is executed.
			 */
			assert node.isLeaf();
			assert !node.isDeallocated();
			
			SearchResult sr = node.search(insertOp.getItem());
			if (sr.exactMatch && node.header.keyCount > node.minimumKeys()) {
				/*
				 * Page still contains the key and will not underflow if the key
				 * is deleted
				 */ 
			}
			else {
				/*
				 * We need to traverse the tree to find the leaf page where the key
				 * now lives.
				 */
				bcursor.unfixP();
				BTreeImpl btree = getBTreeImpl(insertOp.getPageId().getContainerId(), insertOp.getKeyFactoryType(),
						insertOp.getLocationFactoryType(), insertOp.isUnique());
				bcursor.setSearchKey(insertOp.getItem());
				//PageId rootPageId = new PageId(insertOp.getPageId().getContainerId(), ROOT_PAGE_NUMBER);
				//bcursor.p = bufmgr.fixForUpdate(rootPageId, 0);
				btree.updateModeTravese(trx, bcursor);
				/* At this point p points to the leaf page where the key is present */
				assert bcursor.getP() != null;
				assert bcursor.getP().isLatchedForUpdate();
				bcursor.getP().upgradeUpdateLatch();
				p = (SlottedPage) bcursor.getP().getPage();
				node = new BTreeNode(insertOp);
				node.wrap(p);
				assert node.isLeaf();
				assert !node.isDeallocated();
				
				sr = node.search(insertOp.getItem());
			}
			assert sr.exactMatch;
			assert node.header.keyCount > node.minimumKeys();

			/*
			 * Now we can remove the key the was inserted. First generate 
			 * a Compensation Log Record.
			 */
			UndoInsertOperation undoInsertOp = (UndoInsertOperation) loggableFactory.getInstance(MODULE_ID, BTreeIndexManagerImpl.TYPE_UNDOINSERT_OPERATION);
			undoInsertOp.copyFrom(insertOp);
			undoInsertOp.setPosition(sr.k);
			undoInsertOp.setUndoNextLsn(insertOp.getPrevTrxLsn());
			Lsn lsn = trx.logInsert(bcursor.getP().getPage(), undoInsertOp);
			redo(bcursor.getP().getPage(), undoInsertOp);
			bcursor.getP().setDirty(lsn);
		} finally {
			bcursor.unfixP();
		}

	}
	
	private void redoDeleteOperation(Page page, DeleteOperation deleteOp) {
		SlottedPage p = (SlottedPage) page;
		BTreeNode node = new BTreeNode(deleteOp);
		node.wrap(p);
		SearchResult sr = node.search(deleteOp.getItem());
		assert sr.exactMatch;
		node.purge(sr.k);
		node.header.keyCount = node.header.keyCount - 1;
		node.updateHeader();
		
		node.dump();
	}
	
	private void redoUndoDeleteOperation(Page page, UndoDeleteOperation undoDeleteOp) {
		SlottedPage p = (SlottedPage) page;
		BTreeNode node = new BTreeNode(undoDeleteOp);
		node.wrap(p);
		node.insert(undoDeleteOp.getPosition(), undoDeleteOp.getItem());
		node.header.keyCount = node.header.keyCount + 1;
		node.updateHeader();

		node.dump();
	}

	private void undoDeleteOperation(Transaction trx, DeleteOperation deleteOp) throws BufferManagerException, TransactionException, FreeSpaceManagerException {
//		X-latch(P);
//		if (P still covers r and there is a room for r in P) { Q = P;
//		} else { unlatch(P); update-mode-traverse(k,Q);
//		if (Q is full) split(Q);
//		upgrade-latch(Q);
//		} insert r into Q;
//		log(n, <T, undo-delete, Q, (k, x), m>);
//		Page-LSN(Q) = n; Undo-Next-LSN(T) = m; unlatch(Q);
		
		BTreeCursor bcursor = new BTreeCursor();
		bcursor.setP(bufmgr.fixExclusive(deleteOp.getPageId(), false, -1, 0));
		try {
			SlottedPage p = (SlottedPage) bcursor.getP().getPage();
			BTreeNode node = new BTreeNode(deleteOp);
			node.wrap(p);
			
			/*
			 * There is no easy way of knowing whether a page still covers r, since pages may
			 * have been merged and split since the key was originally deleted. We take a cautious
			 * approach and retraverse the tree if the page has been updated since it was 
			 * originally modified. 
			 */
			if ((p.getPageLsn() == deleteOp.getLsn() || (!node.isDeallocated() && node.isLeaf() && node.covers(deleteOp.getItem()))) && node.canAccomodate(deleteOp.getItem())) {
				/*
				 * P sill covers r and there is room for r in P. 
				 */
			}
			else {
				/*
				 * We need to traverse the tree to find the leaf page where the key
				 * now lives.
				 */
				bcursor.unfixP();
				BTreeImpl btree = getBTreeImpl(deleteOp.getPageId().getContainerId(), deleteOp.getKeyFactoryType(),
						deleteOp.getLocationFactoryType(), deleteOp.isUnique());
				bcursor.setSearchKey(deleteOp.getItem());
				btree.updateModeTravese(trx, bcursor);
				/* At this point p points to the leaf page where the key should be inserted */
				assert bcursor.getP() != null;
				assert bcursor.getP().isLatchedForUpdate();
				p = (SlottedPage) bcursor.getP().getPage();
				node.wrap(p);
				if (!node.canAccomodate(bcursor.searchKey)) {
					bcursor.setQ(bcursor.removeP());
					btree.doSplit(trx, bcursor);
					bcursor.setP(bcursor.removeQ());
				}
				bcursor.getP().upgradeUpdateLatch();
				p = (SlottedPage) bcursor.getP().getPage();
				node.wrap(p);
				assert node.isLeaf();
				assert !node.isDeallocated();
			}
			SearchResult sr = node.search(deleteOp.getItem());
			assert !sr.exactMatch;
			if (sr.k == -1) {
				// tree is empty
				assert node.getHighKey().compareTo(deleteOp.getItem()) >= 0;
				assert node.header.keyCount == 1;
				assert node.isRoot();
				sr.k = node.header.keyCount;
			}
			assert sr.k != -1;

			/*
			 * Now we can reinsert the key that was deleted. First generate 
			 * a Compensation Log Record.
			 */
			UndoDeleteOperation undoDeleteOp = (UndoDeleteOperation) loggableFactory.getInstance(MODULE_ID, BTreeIndexManagerImpl.TYPE_UNDODELETE_OPERATION);
			undoDeleteOp.copyFrom(deleteOp);
			undoDeleteOp.setPosition(sr.k);
			undoDeleteOp.setUndoNextLsn(deleteOp.getPrevTrxLsn());
			Lsn lsn = trx.logInsert(bcursor.getP().getPage(), undoDeleteOp);
			redo(bcursor.getP().getPage(), undoDeleteOp);
			bcursor.getP().setDirty(lsn);
		} finally {
			bcursor.unfixP();
		}
	}
	
	
	/**
	 * Returns a BTree implementation. 
	 */
	final BTreeImpl getBTreeImpl(int containerId, int keyFactoryType, int locationFactoryType, boolean unique) throws FreeSpaceManagerException {
		return new BTreeImpl(this, containerId, keyFactoryType, locationFactoryType, unique);
	}
	
	/* (non-Javadoc)
	 * @see org.simpledbm.rss.bt.BTreeMgr#getBTree(int)
	 */
	public final Index getIndex(int containerId) throws IndexException {
		try {
			int keyFactoryType = -1;
			int locationFactoryType = -1;
			boolean unique = false;
			
			PageId rootPageId = new PageId(containerId, ROOT_PAGE_NUMBER);
			BufferAccessBlock bab = bufmgr.fixShared(rootPageId, 0);
			try {
				/*
				 * FIXME: This method has knowledge of the BTreeNode and BTreeNodeHeader structures.
				 * Ideally this ought to be encapsulated in BTreeNode
				 */
				SlottedPage page = (SlottedPage) bab.getPage();
				unique = (page.getFlags() & BTreeNode.NODE_TREE_UNIQUE) != 0;
				BTreeNodeHeader header = new BTreeNodeHeader();
				page.get(0, header);
				keyFactoryType = header.getKeyFactoryType();
				locationFactoryType = header.getLocationFactoryType();
			}
			finally {
				bab.unfix();
			}
            return getBTreeImpl(containerId, keyFactoryType, locationFactoryType, unique);
        } catch (FreeSpaceManagerException e) {
            throw new IndexException.SpaceMgrException(e);
        } catch (BufferManagerException e) {
            throw new IndexException.BufMgrException(e);
		}
	}
	

	/**
	 * @see #createIndex(Transaction, String, int, int, int, int, boolean)
	 */
	final void doCreateBTree(Transaction trx, String name, int containerId, int extentSize, int keyFactoryType, int locationFactoryType, boolean unique) throws BufferManagerException, TransactionException, FreeSpaceManagerException {
		
		Savepoint sp = trx.createSavepoint();
		boolean success = false;
		try {
			/*
			 * Create the specified container
			 */
			spaceMgr.createContainer(trx, name, containerId, 1, extentSize, spMgr.getPageType());

			PageId pageid = new PageId(containerId, ROOT_PAGE_NUMBER);
			/*
			 * Initialize the root page, and update space map page. 
			 */
			LoadPageOperation loadPageOp = (LoadPageOperation) loggableFactory.getInstance(MODULE_ID, TYPE_LOADPAGE_OPERATION);
			loadPageOp.setUnique(unique);
			loadPageOp.setLeaf(true);
			loadPageOp.setKeyFactoryType(keyFactoryType);
			loadPageOp.setLocationFactoryType(locationFactoryType);
			loadPageOp.setSpaceMapPageNumber(1);
			loadPageOp.leftSibling = -1;
			loadPageOp.rightSibling = -1;
			loadPageOp.setPageId(spMgr.getPageType(), pageid);

			IndexKey key = loadPageOp.getMaxIndexKey();
			Location location = loadPageOp.getNewLocation();
			loadPageOp.items.add(new IndexItem(key, location, -1, loadPageOp.isLeaf(), loadPageOp.isUnique()));

			BufferAccessBlock bab = bufmgr.fixExclusive(pageid, false, -1, 0);
			try {
				PageId spaceMapPageId = new PageId(pageid.getContainerId(), loadPageOp.getSpaceMapPageNumber());
				BufferAccessBlock smpBab = bufmgr.fixExclusive(spaceMapPageId, false, -1, 0);
				try {
					Lsn lsn = trx.logInsert(bab.getPage(), loadPageOp);
					redo(bab.getPage(), loadPageOp);
					bab.setDirty(lsn);
					redo(smpBab.getPage(), loadPageOp);
					smpBab.setDirty(lsn);
				} finally {
					smpBab.unfix();
				}
			} finally {
				bab.unfix();
			}
			success = true;
		} finally {
			if (!success) {
				trx.rollback(sp);
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see org.simpledbm.rss.bt.BTreeMgr#createBTree(org.simpledbm.rss.tm.Transaction, java.lang.String, int, int, int, int, boolean)
	 */
	public final void createIndex(Transaction trx, String name, int containerId, int extentSize, int keyFactoryType, int locationFactoryType, boolean unique) throws IndexException {
		try {
			doCreateBTree(trx, name, containerId, extentSize, keyFactoryType, locationFactoryType, unique);
		} catch (BufferManagerException e) {
			throw new IndexException.BufMgrException(e);
		} catch (TransactionException e) {
			throw new IndexException.TrxException(e);
		} catch (FreeSpaceManagerException e) {
			throw new IndexException.SpaceMgrException(e);
		}
	}

	/**
	 * Formats a new BTree page.
	 */
	static private void formatPage(SlottedPage page, int keyFactoryType, int locationFactoryType, boolean leaf, boolean isUnique) {
		/*
		 * FIXME: This method has knowledge of the BTreeNode and BTreeNodeHeader structures.
		 * Ideally this ought to be encapsulated in BTreeNode
		 */
		short flags = 0;
		if (leaf) {
			flags |= BTreeNode.NODE_TYPE_LEAF;
		}
		if (isUnique) {
			flags |= BTreeNode.NODE_TREE_UNIQUE;
		}
		page.setFlags(flags);
		BTreeNodeHeader header = new BTreeNodeHeader();
		header.setKeyFactoryType(keyFactoryType);
		header.setLocationFactoryType(locationFactoryType);
		page.insertAt(0, header, true);
	}
	
	static interface IndexItemHelper {
		public Location getNewLocation();
		
		public IndexKey getNewIndexKey();
		
		public IndexKey getMaxIndexKey();		
	}
	
	public static final class BTreeImpl implements IndexItemHelper, Index {
		public final FreeSpaceCursor spaceCursor;
		final BTreeIndexManagerImpl btreeMgr;
		final int containerId;
	
		final int keyFactoryType;
		final int locationFactoryType;
		
		final IndexKeyFactory keyFactory;
		final LocationFactory locationFactory;

		boolean unique;
		
		BTreeImpl(BTreeIndexManagerImpl btreeMgr, int containerId, int keyFactoryType, int locationFactoryType, boolean unique) throws FreeSpaceManagerException {
			this.btreeMgr = btreeMgr;
			this.containerId = containerId;
			this.keyFactoryType = keyFactoryType;
			this.locationFactoryType = locationFactoryType;
			this.keyFactory = (IndexKeyFactory) btreeMgr.objectFactory.getInstance(keyFactoryType);
			this.locationFactory = (LocationFactory) btreeMgr.objectFactory.getInstance(locationFactoryType);
			this.unique = unique;
			spaceCursor = btreeMgr.spaceMgr.getSpaceCursor(containerId);
		}
		
		public final boolean isUnique() {
			return unique;
		}
		
		public final IndexKey getNewIndexKey() {
			return keyFactory.newIndexKey(containerId);
		}
		
		public final IndexKey getMaxIndexKey() {
			return keyFactory.maxIndexKey(containerId);
		}
		
		public final Location getNewLocation() {
			return locationFactory.newLocation();
		}
		
		public final BTreeNode getBTreeNode() {
			return new BTreeNode(this);
		}

		public final void setUnique(boolean unique) {
			this.unique = unique;
		}
		
		/**
		 * Performs page split. The page to be split must be latched in UPDATE mode. After the split,
		 * the page containing the search key will remain latched.
		 * <p>
		 * This differs from the published algorithm in following ways:
		 * 1. It uses nested top action. 
		 * 2. Page allocation is logged as redo-undo.
		 * 3. Space map page latch is released prior to any other exclusive latch.
		 * 4. The split is logged as Compensation record, with undoNextLsn set to the LSN prior to the page allocation log record.
		 * 5. Information about space map page is stored in new page.
		 * 
		 * @see SplitOperation
		 * @param trx Transaction managing the page split operation
		 * @param bcursor bcursor.q must be the page that is to be split.
		 */
		public final void doSplit(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, FreeSpaceManagerException, TransactionException {

			final BTreeImpl btree = this;
			
			Lsn undoNextLsn = trx.getLastLsn();

			int newSiblingPageNumber = btree.spaceCursor.findAndFixSpaceMapPageExclusively(new SpaceCheckerImpl());
			int spaceMapPageNumber = -1;
			try {
				if (newSiblingPageNumber == -1) {
					// FIXME Test case
					btree.btreeMgr.spaceMgr.extendContainer(trx, btree.containerId);
					undoNextLsn = trx.getLastLsn();
					newSiblingPageNumber = btree.spaceCursor.findAndFixSpaceMapPageExclusively(new SpaceCheckerImpl());
					if (newSiblingPageNumber == -1) {
						throw new FreeSpaceManagerException();
					}
				}
				btree.spaceCursor.updateAndLogUndoably(trx, newSiblingPageNumber, 1);
				spaceMapPageNumber = btree.spaceCursor.getCurrentSpaceMapPage().getPageId().getPageNumber(); 
			}
			finally {
				if (newSiblingPageNumber != -1) {
					btree.spaceCursor.unfixCurrentSpaceMapPage();
				}
			}

			BTreeNode leftSiblingNode = btree.getBTreeNode();
			leftSiblingNode.wrap((SlottedPage) bcursor.getQ().getPage());
			bcursor.getQ().upgradeUpdateLatch();
			
			PageId newSiblingPageId = new PageId(btree.containerId, newSiblingPageNumber);
			SplitOperation splitOperation = (SplitOperation) btree.btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_SPLIT_OPERATION);
			splitOperation.setUndoNextLsn(undoNextLsn);
			splitOperation.setKeyFactoryType(btree.keyFactoryType);
			splitOperation.setLocationFactoryType(btree.locationFactoryType);
			splitOperation.newSiblingPageNumber = newSiblingPageNumber;
			splitOperation.rightSibling = leftSiblingNode.header.rightSibling;
			splitOperation.spaceMapPageNumber = spaceMapPageNumber;
			splitOperation.setLeaf(leftSiblingNode.isLeaf());
			splitOperation.setUnique(btree.isUnique());
			short medianKey = leftSiblingNode.getSplitKey();
			for (int k = medianKey; k <= leftSiblingNode.header.keyCount; k++) {
				splitOperation.items.add(leftSiblingNode.getItem(k));
			}
			splitOperation.highKey = leftSiblingNode.getItem(medianKey-1);
			if (leftSiblingNode.isLeaf()) {
				splitOperation.newKeyCount = medianKey;
			}
			else {
				splitOperation.newKeyCount = (short) (medianKey-1);
			}
			
			bcursor.setR(btree.btreeMgr.bufmgr.fixExclusive(newSiblingPageId, true, btree.btreeMgr.spMgr.getPageType(), 0));

			try {
				Lsn lsn = trx.logInsert(leftSiblingNode.page, splitOperation);
				
				btree.btreeMgr.redo(bcursor.getR().getPage(), splitOperation);
				bcursor.getR().setDirty(lsn);
				btree.btreeMgr.redo(leftSiblingNode.page, splitOperation);
				bcursor.getQ().setDirty(lsn);
				
				int comp = splitOperation.highKey.compareTo(bcursor.searchKey);
				if (comp >= 0) {
					// new key will stay in current page
					bcursor.getQ().downgradeExclusiveLatch();
					bcursor.unfixR();
				}
				else {
					// new key will be in the right sibling
					bcursor.getR().downgradeExclusiveLatch();
					bcursor.unfixQ();
					bcursor.setQ(bcursor.removeR());
				}
			}
			finally {
				bcursor.unfixR();
			}
		}
		
		/**
		 * Merges right sibling into left sibling. Right sibling must be an indirect child.
		 * Both pages must be latched in UPDATE mode prior to this call.
		 * After the merge, left sibling will remain latched. 
		 * <p>
		 * This algorithm differs from published algorithm in its management of space map
		 * update. In the interests of high concurrency, the space map page update is
		 * handled as a separate redo only action. 
		 */
		public final void doMerge(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, TransactionException, FreeSpaceManagerException {
			
			final BTreeImpl btree = this;

			assert bcursor.getR() != null;
			assert bcursor.getR().isLatchedForUpdate();

			assert bcursor.getQ() != null;
			assert bcursor.getQ().isLatchedForUpdate();

			BTreeNode leftSiblingNode = btree.getBTreeNode();
			bcursor.getQ().upgradeUpdateLatch();
			leftSiblingNode.wrap((SlottedPage) bcursor.getQ().getPage());

			bcursor.getR().upgradeUpdateLatch();
			BTreeNode rnode = btree.getBTreeNode();
			rnode.wrap((SlottedPage) bcursor.getR().getPage());
			
			assert leftSiblingNode.header.rightSibling == rnode.page.getPageId().getPageNumber();
			
//			SlottedPage rpage = (SlottedPage) bcursor.getR().getPage();
//			PageId spaceMapPageId = new PageId(btree.containerId, rpage.getSpaceMapPageNumber()); 
			
//			BufferAccessBlock smpBab = btree.btreeMgr.bufmgr.fixExclusive(spaceMapPageId, false, "", 0);
			
			MergeOperation mergeOperation = (MergeOperation) btree.btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_MERGE_OPERATION);
			
			mergeOperation.setLeaf(leftSiblingNode.isLeaf());
			mergeOperation.setUnique(btree.isUnique());
			mergeOperation.setKeyFactoryType(btree.keyFactoryType);
			mergeOperation.setLocationFactoryType(btree.locationFactoryType);
			mergeOperation.rightSibling = leftSiblingNode.header.rightSibling;
			mergeOperation.rightRightSibling = rnode.header.rightSibling;
			for (int k = 1; k <= rnode.header.keyCount; k++) {
				mergeOperation.items.add(rnode.getItem(k));
			}
			mergeOperation.rightSiblingSpaceMapPage = rnode.page.getSpaceMapPageNumber();
		
			try {
				Lsn lsn = trx.logInsert(leftSiblingNode.page, mergeOperation);
				btree.btreeMgr.redo(leftSiblingNode.page, mergeOperation);
				bcursor.getQ().setDirty(lsn);
				
				btree.btreeMgr.redo(rnode.page, mergeOperation);
				bcursor.getR().setDirty(lsn);
				
//				btree.btreeMgr.redo(smpBab.getPage(), mergeOperation);
//				smpBab.setDirty(lsn);
			}
			finally {
//				smpBab.unfix();
				
				bcursor.unfixR();
				
				bcursor.getQ().downgradeExclusiveLatch();
			}

			/*
			 * We log the space map operation as a separate discrete action.
			 * If this log record does not survive a system crash, then the page
			 * will end up appearing allocated. However the actual page will be
			 * marked as deallocated, and hence can be reclaimed later on.
			 */
			btree.spaceCursor.fixSpaceMapPageExclusively(mergeOperation.rightSiblingSpaceMapPage, mergeOperation.rightSibling);
			try {
				btree.spaceCursor.updateAndLogRedoOnly(trx, mergeOperation.rightSibling, 0);
			}
			finally {
				btree.spaceCursor.unfixCurrentSpaceMapPage();
			}
		}

		/**
		 * Link the right sibling to the parent, when the right sibling is an 
		 * indirect child. Parent and left child must be latched in UPDATE
		 * mode prior to invoking this method. Both will remain latched at the
		 * end of the operation. 
		 * <p>Note that this differs from published algorithm slightly:
		 * <pre>
		 * v = highkey of R
		 * u = highkey of Q
		 * Link(P, Q, R) {
		 * 	upgrade-latch(P);
		 * 	change the index record (v, Q.pageno) to (v, R.pageno);
		 * 	insert the index record (u, Q.pageno) before (v, R.pageno);
		 * 	lsn = log(<unlink, P, Q.pageno, R.pageno>);
		 * 	P.pageLsn = lsn;
		 * 	downgrade-latch(P);
		 * }
		 * </pre>
		 * @throws FreeSpaceManagerException 
		 */
		public final void doLink(Transaction trx, BTreeCursor bcursor) throws TransactionException, FreeSpaceManagerException {

			final BTreeImpl btree = this;

			assert bcursor.getP() != null;
			assert bcursor.getP().isLatchedForUpdate();

			assert bcursor.getQ() != null;
			assert bcursor.getQ().isLatchedForUpdate();

			bcursor.getP().upgradeUpdateLatch();
			BTreeNode parentNode = btree.getBTreeNode();
			parentNode.wrap((SlottedPage) bcursor.getP().getPage());

			BTreeNode lnode = btree.getBTreeNode();
			lnode.wrap((SlottedPage) bcursor.getQ().getPage());
			SlottedPage lpage = (SlottedPage) bcursor.getQ().getPage();
			
			LinkOperation linkOperation = (LinkOperation) btree.btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_LINK_OPERATION);
			linkOperation.setLeaf(parentNode.isLeaf());	// should be false 
			linkOperation.setUnique(btree.isUnique());
			linkOperation.setKeyFactoryType(btree.keyFactoryType);
			linkOperation.setLocationFactoryType(btree.locationFactoryType);
			linkOperation.leftSibling = lpage.getPageId().getPageNumber();
			linkOperation.rightSibling = lnode.header.rightSibling;
			IndexItem u = lnode.getHighKey();
			u.setChildPageNumber(linkOperation.leftSibling);
			u.setLeaf(false);
			linkOperation.leftChildHighKey = u;
			
			try {
				Lsn lsn = trx.logInsert(parentNode.page, linkOperation);
				btree.btreeMgr.redo(parentNode.page, linkOperation);
				bcursor.getP().setDirty(lsn);
			}
			finally {
				bcursor.getP().downgradeExclusiveLatch();
			}
		}

		/**
		 * Unlink the right child from the parent. The page to the right of the
		 * right child must not be an indirect child. This operation requires
		 * parent, and the two child nodes to be latched in UPDATE mode prior to
		 * invocation. At the end of the operation the parent is released.  
		 * <p>Note that this differs from published algorithm slightly:
		 * <pre>
		 * v = highkey of R
		 * u = highkey of Q
		 * Unlink(P, Q, R) {
		 * 	upgrade-latch(P);
		 * 	delete the index record (u, Q.pageno);
		 * 	change the index record (v, R.pageno) to (v, Q.pageno);
		 * 	lsn = log(<unlink, P, Q.pageno, R.pageno>);
		 * 	P.pageLsn = lsn;
		 * 	unfix(P);
		 * }
		 * </pre>
		 * @throws FreeSpaceManagerException 
		 */
		public final void doUnlink(Transaction trx, BTreeCursor bcursor) throws TransactionException, BufferManagerException, FreeSpaceManagerException {

			final BTreeImpl btree = this;

			assert bcursor.getP() != null;
			assert bcursor.getP().isLatchedForUpdate();

			assert bcursor.getQ() != null;
			assert bcursor.getQ().isLatchedForUpdate();
			
			assert bcursor.getR() != null;
			assert bcursor.getR().isLatchedForUpdate();

			bcursor.getP().upgradeUpdateLatch();
			BTreeNode parentNode = btree.getBTreeNode();
			parentNode.wrap((SlottedPage) bcursor.getP().getPage());

			UnlinkOperation unlinkOperation = (UnlinkOperation) btree.btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_UNLINK_OPERATION);
			unlinkOperation.setLeaf(parentNode.isLeaf());	// should be false
			unlinkOperation.setUnique(btree.isUnique());
			unlinkOperation.setKeyFactoryType(btree.keyFactoryType);
			unlinkOperation.setLocationFactoryType(btree.locationFactoryType);
			unlinkOperation.leftSibling = bcursor.getQ().getPage().getPageId().getPageNumber();
			unlinkOperation.rightSibling = bcursor.getR().getPage().getPageId().getPageNumber();
			try {
				Lsn lsn = trx.logInsert(parentNode.page, unlinkOperation);
				btree.btreeMgr.redo(parentNode.page, unlinkOperation);
				bcursor.getP().setDirty(lsn);
			}
			finally {
				bcursor.unfixP();
			}
		}
		
		/**
		 * Redistribute the keys between sibling nodes when the right child is an
		 * indirect child of parent page. Both pages must be latched in UPDATE
		 * mode prior to calling this method. At the end of this operation,
		 * the child page that covers the search key will remain latched in
		 * UPDATE mode. 
		 * <p>
		 * Unlike the published algorithm we simply transfer one key from the more 
		 * densely populated page to the less populated page.
		 * @param bcursor bcursor.q must point to left page, and bcursor.r to its right sibling
		 * @throws FreeSpaceManagerException 
		 */
		public final void doRedistribute(Transaction trx, BTreeCursor bcursor) throws TransactionException, BufferManagerException, FreeSpaceManagerException {

			final BTreeImpl btree = this;

			assert bcursor.getQ() != null;
			assert bcursor.getQ().isLatchedForUpdate();
			
			assert bcursor.getR() != null;
			assert bcursor.getR().isLatchedForUpdate();

			assert bcursor.searchKey != null;

			bcursor.getQ().upgradeUpdateLatch();
			BTreeNode leftSiblingNode = btree.getBTreeNode();
			leftSiblingNode.wrap((SlottedPage) bcursor.getQ().getPage());

			bcursor.getR().upgradeUpdateLatch();
			BTreeNode rightSiblingNode = btree.getBTreeNode();
			rightSiblingNode.wrap((SlottedPage) bcursor.getR().getPage());
			SlottedPage rpage = (SlottedPage) bcursor.getR().getPage();

			RedistributeOperation redistributeOperation = (RedistributeOperation) btree.btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_REDISTRIBUTE_OPERATION);
			redistributeOperation.setLeaf(leftSiblingNode.isLeaf());
			redistributeOperation.setUnique(btree.isUnique());
			redistributeOperation.setKeyFactoryType(btree.keyFactoryType);
			redistributeOperation.setLocationFactoryType(btree.locationFactoryType);
			redistributeOperation.leftSibling = leftSiblingNode.page.getPageId().getPageNumber();
			redistributeOperation.rightSibling = rpage.getPageId().getPageNumber();
			if (leftSiblingNode.page.getFreeSpace() > rpage.getFreeSpace()) {
				// key moving left
				// FIXME Test case
				redistributeOperation.key = rightSiblingNode.getLastKey();
				redistributeOperation.targetSibling = redistributeOperation.leftSibling;
			}
			else {
				// key moving right
				redistributeOperation.key = leftSiblingNode.getLastKey();
				redistributeOperation.targetSibling = redistributeOperation.rightSibling;
			}
			
			try {
				Lsn lsn = trx.logInsert(leftSiblingNode.page, redistributeOperation);

				btree.btreeMgr.redo(leftSiblingNode.page, redistributeOperation);
				bcursor.getQ().setDirty(lsn);
				
				btree.btreeMgr.redo(rpage, redistributeOperation);
				bcursor.getR().setDirty(lsn);
				
				leftSiblingNode.wrap((SlottedPage) bcursor.getQ().getPage());
				int comp = leftSiblingNode.getHighKey().compareTo(bcursor.searchKey);
				if (comp >= 0) {
					// new key will stay in current page
					bcursor.getQ().downgradeExclusiveLatch();
					bcursor.unfixR();
				}
				else {
					// new key will be in the right sibling
					bcursor.getR().downgradeExclusiveLatch();
					bcursor.unfixQ();
					bcursor.setQ(bcursor.removeR());
				}
			}
			finally {
				bcursor.unfixR();
			}
		}

		/**
		 * Increase tree height when root page as a sibling page. The root page and its
		 * sibling must be latched in UPDATE mode prior to calling this method. After the
		 * operation is complete, the latch on the root page is released, but one of the child
		 * pages (the one that covers the search key) will be left latched in UPDATE mode.
		 * <p>
		 * The implementation differs from the published algorithm as follows:
		 * <ol>
		 * <li>
		 * No need to format the new page, as this is taken care of in the space
		 * management module. New pages are formatted as soon as they are created.
		 * </li>
		 * <li>
		 * We use a nested top action to manage the entire action. This is to improve
		 * concurrency, as it allows the space map page update to be completed before
		 * any other page is latched exclusively. The SMO is logged as a Compensation
		 * record and linked to the log record prior to the space map update. This makes the
		 * SMO redoable, but the space map update will be undon if the SMO log does
		 * not survive.
		 * </li>
		 * </ol>
		 * @param bcursor bcursor.q must point to root page, and bcursor.r to its right sibling
		 */
		public final void doIncreaseTreeHeight(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, FreeSpaceManagerException, TransactionException {

			final BTreeImpl btree = this;
			
			assert bcursor.getQ() != null;
			assert bcursor.getQ().isLatchedForUpdate();
			
			assert bcursor.getR() != null;
			assert bcursor.getR().isLatchedForUpdate();

			assert bcursor.getP() == null;

			assert bcursor.searchKey != null;
			
			Lsn undoNextLsn;

			// Allocate new page. 
			int newSiblingPageNumber = btree.spaceCursor.findAndFixSpaceMapPageExclusively(new SpaceCheckerImpl());
			int spaceMapPageNumber = -1;
			try {
				if (newSiblingPageNumber == -1) {
					// FIXME Test case
					btree.btreeMgr.spaceMgr.extendContainer(trx, btree.containerId);
					newSiblingPageNumber = btree.spaceCursor.findAndFixSpaceMapPageExclusively(new SpaceCheckerImpl());
					if (newSiblingPageNumber == -1) {
						throw new FreeSpaceManagerException();
					}
				}
				// Make a note of current lsn so that we can link the Compensation record to it.
				undoNextLsn = trx.getLastLsn();
				btree.spaceCursor.updateAndLogUndoably(trx, newSiblingPageNumber, 1);
				spaceMapPageNumber = btree.spaceCursor.getCurrentSpaceMapPage().getPageId().getPageNumber(); 
			}
			finally {
				if (newSiblingPageNumber != -1) {
					btree.spaceCursor.unfixCurrentSpaceMapPage();
				}
			}

			bcursor.setP(bcursor.removeQ());
			BTreeNode rootNode = btree.getBTreeNode();
			rootNode.wrap((SlottedPage) bcursor.getP().getPage());
			bcursor.getP().upgradeUpdateLatch();
			
			IncreaseTreeHeightOperation ithOperation = (IncreaseTreeHeightOperation) btree.btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_INCREASETREEHEIGHT_OPERATION);
			ithOperation.setUndoNextLsn(undoNextLsn);
			ithOperation.setKeyFactoryType(btree.keyFactoryType);
			ithOperation.setLocationFactoryType(btree.locationFactoryType);
			ithOperation.rightSibling = rootNode.header.rightSibling;
			ithOperation.leftSibling = newSiblingPageNumber;
			ithOperation.spaceMapPageNumber = spaceMapPageNumber;
			// New child page will inherit the root page leaf attribute
			ithOperation.setLeaf(rootNode.isLeaf());
			ithOperation.setUnique(btree.isUnique());
			for (int k = 1; k <= rootNode.header.keyCount; k++) {
				ithOperation.items.add(rootNode.getItem(k));
			}
			IndexItem leftChildHighKey = rootNode.getItem(rootNode.header.keyCount);
			leftChildHighKey.setLeaf(false);
			leftChildHighKey.setChildPageNumber(ithOperation.leftSibling);
			IndexItem rightChildHighKey = rootNode.getInfiniteKey();
			rightChildHighKey.setLeaf(false);
			rightChildHighKey.setChildPageNumber(ithOperation.rightSibling); 
			ithOperation.rootItems.add(leftChildHighKey);
			ithOperation.rootItems.add(rightChildHighKey);

			// Latch the new page exclusively
			bcursor.setQ(btree.btreeMgr.bufmgr.fixExclusive(new PageId(btree.containerId, ithOperation.leftSibling), true, btree.btreeMgr.spMgr.getPageType(), 0));
			try {
				Lsn lsn = trx.logInsert(rootNode.page, ithOperation);
				
				btree.btreeMgr.redo(rootNode.page, ithOperation);
				bcursor.getP().setDirty(lsn);

				btree.btreeMgr.redo(bcursor.getQ().getPage(), ithOperation);
				bcursor.getQ().setDirty(lsn);

				bcursor.unfixP();
				
				int comp = leftChildHighKey.compareTo(bcursor.searchKey);
				if (comp >= 0) {
					// new key will stay in left child page
					bcursor.getQ().downgradeExclusiveLatch();
					bcursor.unfixR();
				}
				else {
					// new key will be in the right child page
					bcursor.unfixQ();
					bcursor.setQ(bcursor.removeR());
				}
			}
			finally {
				// TODO is this robust enough?
				bcursor.unfixP();
				bcursor.unfixR();
			}
		}

		/**
		 * Decrease tree height when root page has only one child and that child does not have 
		 * a right sibling. The root page and its child must be latched in UPDATE mode prior
		 * to calling this method. At the end of this operation, the root will remain latched in
		 * UPDATE mode.
		 * <p>
		 * Important note:
		 * To increase concurrency, we update the space map page after the SMO as a separate
		 * redo only action. This improves concurrency because we do not hold the space map
		 * page exclusively during the SMO. However, it has the disadvantage that if the SMO 
		 * survives a system crash, and the log for the space map page updates does not survive,
		 * then the page will remain allocated on the space map, even though it is no longer
		 * in use. It is posible to identify deallocated pages by checking the page flags for the
		 * bit BTreeNode.
		 * @param bcursor bcursor.p must point to root page, and bcursor.q to only child
		 */
		public final void doDecreaseTreeHeight(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, FreeSpaceManagerException, TransactionException {

			final BTreeImpl btree = this;
			
			assert bcursor.getP() != null;
			assert bcursor.getP().isLatchedForUpdate();
			
			assert bcursor.getQ() != null;
			assert bcursor.getQ().isLatchedForUpdate();
			
			// root page
			bcursor.getP().upgradeUpdateLatch();
			BTreeNode rootNode = btree.getBTreeNode();
			rootNode.wrap((SlottedPage) bcursor.getP().getPage());
			
			// child page
			bcursor.getQ().upgradeUpdateLatch();
			BTreeNode childNode = btree.getBTreeNode();
			childNode.wrap((SlottedPage) bcursor.getQ().getPage());
			
			DecreaseTreeHeightOperation dthOperation = (DecreaseTreeHeightOperation) btree.btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_DECREASETREEHEIGHT_OPERATION);
			dthOperation.setKeyFactoryType(btree.keyFactoryType);
			dthOperation.setLocationFactoryType(btree.locationFactoryType);
			// root will inherit the leaf status of child node
			dthOperation.setLeaf(childNode.isLeaf());
			dthOperation.setUnique(dthOperation.isUnique());
			for (int k = 1; k <= childNode.header.keyCount; k++) {
				dthOperation.items.add(childNode.getItem(k));
			}
			dthOperation.childPageSpaceMap = childNode.page.getSpaceMapPageNumber();
			dthOperation.childPageNumber = childNode.page.getPageId().getPageNumber();
			
			try {
				Lsn lsn = trx.logInsert(rootNode.page, dthOperation);

				btree.btreeMgr.redo(rootNode.page, dthOperation);
				bcursor.getP().setDirty(lsn);

				btree.btreeMgr.redo(bcursor.getQ().getPage(), dthOperation);
				bcursor.getQ().setDirty(lsn);
			}
			finally {
				// TODO Is this robust enough?
				bcursor.unfixQ();
				bcursor.getP().downgradeExclusiveLatch();
			}
		
			/*
			 * We log the space map operation as a separate discrete action.
			 * If this log record does not survive a system crash, then the page
			 * will end up appearing allocated. However the actual page will be
			 * marked as deallocated, and hence can be reclaimed later on.
			 */
			btree.spaceCursor.fixSpaceMapPageExclusively(dthOperation.childPageSpaceMap, dthOperation.childPageNumber);
			try {
				btree.spaceCursor.updateAndLogRedoOnly(trx, dthOperation.childPageNumber, 0);
			}
			finally {
				btree.spaceCursor.unfixCurrentSpaceMapPage();
			}
		}
		
		/**
		 * Splits the parent node of current node Q. Parent must be latched in 
		 * UPDATE mode prior to the call. After the split is complete, the
		 * parent node or its new sibling node, whichever covers the search key,
		 * will be left latched as bcursor.p. Latches on child nodes will remain
		 * unchanged.  
		 * @throws TransactionException 
		 */
		final void doSplitParent(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, FreeSpaceManagerException, TransactionException {
			/*
			 * doSplit requires Q to point to page that is to be
			 * split, so we need to point Q to P temporarily.
			 */
			BufferAccessBlock savedQ = bcursor.removeQ(); // Save Q
			BufferAccessBlock savedR = bcursor.removeR(); // Save R
			bcursor.setQ(bcursor.removeP()); // Now Q is P
			try {
				doSplit(trx, bcursor);
			} finally {
				bcursor.setP(bcursor.removeQ());
				bcursor.setQ(savedQ);
				bcursor.setR(savedR);
			}
		}
		
		/**
		 * Repairs underflow when an about-to-underflow child is encountered during update
		 * mode traversal. Both the parent page (bcursor.p) and its child page (bcursor.q) must
		 * be latched in UPDATE mode prior to calling this method. When this method returns,
		 * the latch on the parent page will have been released, and the child page that covers the
		 * search key will remain latched in bcursor.q. 
		 * <p>
		 * For this algorithm to work, an index page needs to have at least two children who
		 * are linked the index page.
		 */
		public final boolean doRepairPageUnderflow(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, TransactionException, FreeSpaceManagerException {
			
			assert bcursor.getP() != null;
			assert bcursor.getP().isLatchedForUpdate();
			
			assert bcursor.getQ() != null;
			assert bcursor.getQ().isLatchedForUpdate();
			
			BTreeNode q = getBTreeNode();
			q.wrap((SlottedPage) bcursor.getQ().getPage());

			BTreeNode p = getBTreeNode();
			p.wrap((SlottedPage) bcursor.getP().getPage());

			BTreeNode r = getBTreeNode();
			
			int Q = q.page.getPageId().getPageNumber();
			
			IndexItem u = q.getHighKey();
			IndexItem highkeyP = p.getHighKey();
			/*
			 * If the high key of Q is less than the high key of
			 * P then Q is not the rightmost child of P.
			 */
			if (u.compareTo(highkeyP) < 0) {
				/* Q is not the rightmost child of its parent P 
				 *
				 * There are three possibilities:
				 * a) R is an indirect child of P.
				 * b) R is a direct child of P, and has a sibling S that is an indirect child of P.
				 * c) R is a direct child of P and has a sibling S that is also a direct child of P.
				 */
				/* v = index record associated with Q in P */
				IndexItem v = p.findIndexItem(Q);
				/* R = rightsibling of Q */
				int R = q.header.rightSibling;
				assert R != -1;
				
				bcursor.setR(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, R), 0));
				r.wrap((SlottedPage) bcursor.getR().getPage());
				/*
				 * If the high key of Q is than the index key in P associated with Q,
				 * then R must be an indirect child of Q.
				 */
				if (u.compareTo(v) < 0) {
					/* a) R is an indirect child of P (case 13 in paper)
					 * This means that we can merge with R.
					 *                    P[v|w]
					 *           +----------+ +--------+
					 *           |                     |
					 *          Q[u]------>R[v]------>S[w]
					 */
					bcursor.unfixP();
					if (q.canMergeWith(r)) {
						doMerge(trx, bcursor);
					} else {
						doRedistribute(trx, bcursor);
					}
				} else {
					/* b) or c) R is a direct child of P */
					/* w = index record associated with R in P */
					IndexItem w = p.findIndexItem(R);
					v = r.getHighKey();
					/*
					 * If highkey of R is less than the index key in P associated with R,
					 * then R must have a right sibling S that is an indirect child of
					 * P. 
					 */
					if (v.compareTo(w) < 0) {
						/* b) R has a right sibling S that is indirect child of P (fig 14)
						 * 
						 *                    P[u|w]
						 *           +----------+ +
						 *           |            |
						 *          Q[u]-------->R[v]------>S[w]
						 * 
						 * We cannot unlink R from P until we have S linked to P.
						 * Therefore, we need to link S to P first.
						 * 
						 * If P cannot accomodate the index key v then it will need to be 
						 * split.
						 */
						if (!p.canAccomodate(v)) {
							doSplitParent(trx, bcursor);
							/*
							 * After the split, Q should still be a child of P, but
							 * R may have moved to the sibling of P.
							 */
							assert p.findIndexItem(Q) != null;
							if (p.findIndexItem(R) == null) {
								/* R is not a child of P anymore.
								 * We need to restart the algorithm
								 */
								bcursor.unfixR();
								return true;
							}
						}
						/*
						 * We need to link S to P. Since our cursor is currently
						 * positioned Q, we need to temporarily move right to R,
						 * in order to do the link.
						 */
						BufferAccessBlock savedQ = bcursor.removeQ();	// Save Q
						bcursor.setQ(bcursor.removeR());					// Now Q is R
						try {
							doLink(trx, bcursor); 				// Link S to P
						} finally {
							bcursor.setR(bcursor.removeQ()); 				// Restore R
							bcursor.setQ(savedQ); 				// Restore Q
						}
					}
					/*
					 * At this point any sibling of R (ie, S) is
					 * guaranteed to be linked to parent P. So we can now
					 * unlink R from P to allow merging of Q and R.
					 */
					doUnlink(trx, bcursor); // Now we can unlink R from P
					/*
					 * Merge Q and R
					 */
					q.wrap((SlottedPage) bcursor.getQ().getPage());
					r.wrap((SlottedPage) bcursor.getR().getPage());
					if (q.canMergeWith(r)) {
						doMerge(trx, bcursor);
					} else {
						doRedistribute(trx, bcursor);
					}
				}
			} else {
				/* Q is the rightmost child of its parent P
				 * There are two possibilities.
				 * The leftsibling L of Q is 
				 * a) a direct child of P
				 * b) an indirect child of P.
				 */
				/* Find node L to the left of Q
				 * Note that since every page must have at least 2 items,
				 * we are guaranteed to find L.
				 */
				IndexItem v = p.findPrevIndexItem(Q);
				assert v != null;
				int L = v.getChildPageNumber();
				/*
				 * Since our cursor is positioned on Q, we need to move left.
				 * But to do that we need to unlatch Q first. 
				 */
				bcursor.unfixQ();
				/* Now L becomes Q */
				bcursor.setQ(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, L), 0));
				q.wrap((SlottedPage) bcursor.getQ().getPage());
				/* The node to the right of L is N */
				/* This may or may not be Q depending upon whether N is
				 * an indirect child of P.
				 */
				int N = q.header.rightSibling;
				assert N != -1;
				bcursor.setR(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, N), 0));
				r.wrap((SlottedPage) bcursor.getR().getPage());
				
				/*
				 * Get highkey of L and compare with the index key associated with L in
				 * page P. 
				 */
				u = q.getHighKey();
				if (u.compareTo(v) == 0) {
					/* 
					 * Fig 17.
					 * L is direct child of P 
					 * and the right sibling of L is Q
					 * 
					 *                   P[u|v|w]
					 *           +---------+ + +--------+
					 *           |           |          |
					 *         ?[u]------->L[v]------->Q[w]
					 *  
					 */
					assert q.header.rightSibling == Q;
					if (!r.isAboutToUnderflow()) {
						/* Q is no longer about to overflow (remember R is Q!) */
						// FIXME Test case
						bcursor.unfixP();
						bcursor.unfixQ(); 
						bcursor.setQ(bcursor.removeR());
					}
					else {
						/* In order to merge Q with its left sibling L, we need
						 * to unlink Q from its parent first.
						 * Remember that bcursor.q is positioned on L.
						 */
						/* unlink Q from P */
						// FIXME Test case
						doUnlink(trx, bcursor);
						q.wrap((SlottedPage) bcursor.getQ().getPage());
						r.wrap((SlottedPage) bcursor.getR().getPage());
						if (q.canMergeWith(r)) {
							doMerge(trx, bcursor);
						}
						else {
							doRedistribute(trx, bcursor);
						}
					}
				}
				else {
					/*
					 *  Fig 19 in paper.
					 * The left sibling L of Q has a right sibling N
					 * that is an indirect child of P. Q is the right
					 * sibling of N. 
					 * 
					 *                    P[v|w]
					 *           +----------+ +--------+
					 *           |                     |
					 *          L[u]------>N[v]------>Q[w]
					 * 
					 * 
					 * To merge Q with N, we first need to link
					 * N to parent page P, then unlink Q from P.
					 */
					if (!p.canAccomodate(u)) {
						doSplitParent(trx, bcursor);
						/*
						 * Since Q was the rightmost child of P,
						 * even after the split Q and its left sibling L must 
						 * belong to P (because a minimum of 2 items must be
						 * present in a page).
						 */
						p.wrap((SlottedPage) bcursor.getP().getPage());
						assert p.findIndexItem(Q) != null;
						assert p.findIndexItem(L) != null;
					}
					/* link N to parent P */
					doLink(trx, bcursor);
					/* unlatch L */
					bcursor.unfixQ();
					/* N becomes bcursor.q */
					bcursor.setQ(bcursor.removeR());
					q.wrap((SlottedPage) bcursor.getQ().getPage());
					/* latch Q again, which now becomes bcursor.r */
					assert Q == q.header.rightSibling;
					bcursor.setR(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, Q), 0));
					r.wrap((SlottedPage) bcursor.getR().getPage());
					if (!r.isAboutToUnderflow()) {
						/* Q is no longer about to underflow */
						// FIXME Test case
						bcursor.unfixP();
						bcursor.unfixQ(); 
						bcursor.setQ(bcursor.removeR());
					}
					else {
						/* unlink Q from parent P */
						doUnlink(trx, bcursor);
						q.wrap((SlottedPage) bcursor.getQ().getPage());
						r.wrap((SlottedPage) bcursor.getR().getPage());
						if (q.canMergeWith(r)) {
							/* merge N and Q */
							doMerge(trx, bcursor);
						}
						else {
							doRedistribute(trx, bcursor);
						}
					}
				}
			}
			return false;
		}

		/**
		 * Repairs underflow when an about-to-underflow child is encountered during update
		 * mode traversal. Both the parent page (bcursor.p) and its child page (bcursor.q) must
		 * be latched in UPDATE mode prior to calling this method. When this method returns,
		 * the latch on the parent page will have been released, and the child page that covers the
		 * search key will remain latched in bcursor.q. 
		 * <p>
		 * For this algorithm to work, an index page needs to have at least two children who
		 * are linked the index page.
		 */
		public final void repairPageUnderflow(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, TransactionException, FreeSpaceManagerException {
			boolean tryAgain = doRepairPageUnderflow(trx, bcursor);
			while (tryAgain) {
				// FIXME Test case
				tryAgain = doRepairPageUnderflow(trx, bcursor);
			}
		}

		/**
		 * Walks down the tree using UPDATE mode latching. On the way down, pages may be
		 * split or merged to ensure that the tree maintains its balance. 
		 */
		public final void updateModeTravese(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, FreeSpaceManagerException, TransactionException {
			/*
			 * Fix root page
			 */
			bcursor.setP(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, BTreeIndexManagerImpl.ROOT_PAGE_NUMBER), 0));
			BTreeNode p = getBTreeNode();
			p.wrap((SlottedPage) bcursor.getP().getPage());
			if (p.header.rightSibling != -1) {
				/* 
				 * Root page has a right sibling.
				 * A new child page will be allocated and the root will become
				 * the parent of this new child, and its right sibling. 
				 */
				bcursor.setQ(bcursor.removeP());
				bcursor.setR(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, p.header.rightSibling), 0));
				doIncreaseTreeHeight(trx, bcursor);
				bcursor.setP(bcursor.removeQ());
				p.wrap((SlottedPage) bcursor.getP().getPage());
			}
			if (p.isLeaf()) {
				return;
			}
			int childPageNumber = p.findChildPage(bcursor.searchKey);
			bcursor.setQ(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, childPageNumber), 0));
			BTreeNode q = getBTreeNode();
			q.wrap((SlottedPage) bcursor.getQ().getPage());
			boolean childPageLatched = true;
			if (p.isRoot() && p.header.keyCount == 1 && q.header.rightSibling == -1) {
				/* Q is only child of P and Q has no right sibling */
				/*
				 * Root is underflown as it has only one child and this child does not
				 * have a right sibling. Decrease the height of the tree by
				 * merging the child page into the root.
				 */
				// FIXME Test case
				doDecreaseTreeHeight(trx, bcursor);
				childPageLatched = false;
			}
			p.wrap((SlottedPage) bcursor.getP().getPage());
			while (!p.isLeaf()) {
				if (!childPageLatched) {
					/*
					 * BUG in published algorithm - need to avoid latching
					 * Q if already latched.
					 */
					// FIXME Test case
					childPageNumber = p.findChildPage(bcursor.searchKey);
					bcursor.setQ(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, childPageNumber), 0));
					q.wrap((SlottedPage) bcursor.getQ().getPage());
				}
				else {
					childPageLatched = false;
				}
				if (q.isAboutToUnderflow()) {
					repairPageUnderflow(trx, bcursor);
					bcursor.setP(bcursor.removeQ());
				}
				else {
					IndexItem v = p.findIndexItem(q.page.getPageId().getPageNumber());
					IndexItem u = q.getHighKey();
					if (u.compareTo(v) < 0) {
						/* Q has a right sibling page R which is an
						 * indirect child of P. Also handles the case where R is
						 * the right most page.
						 * Fig 5 in the paper.
						 *                    P[v|w]
						 *           +----------+ +--------+
						 *           |                     |
						 *          Q[u]------>R[v]------>S[w]
						 */
						if (!p.canAccomodate(u)) {
							doSplitParent(trx, bcursor);
						}
						doLink(trx, bcursor);
					}
					q.wrap((SlottedPage) bcursor.getQ().getPage());
					if (q.getHighKey().compareTo(bcursor.searchKey) >= 0) {
						/* Q covers search key */
						// FIXME Test case
						bcursor.unfixP();
						bcursor.setP(bcursor.removeQ());
					}
					else {
						/* move right */
						int rightsibling = q.header.rightSibling;
						bcursor.unfixP();
						assert q.header.rightSibling != -1;
						bcursor.setP(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, rightsibling), 0));
						bcursor.unfixQ(); 
					}
				}
				p.wrap((SlottedPage) bcursor.getP().getPage());
			}
		}

		public final void readModeTraverse(BTreeCursor bcursor) throws BufferManagerException {
			/*
			 * Fix root page
			 */
			bcursor.setP(btreeMgr.bufmgr.fixShared(new PageId(containerId, BTreeIndexManagerImpl.ROOT_PAGE_NUMBER), 0));
			BTreeNode p = getBTreeNode();
			p.wrap((SlottedPage) bcursor.getP().getPage());
			
			do {
				IndexItem v = p.getHighKey();
				if (v.compareTo(bcursor.getSearchKey()) < 0) {
					// Move right as the search key is greater than the highkey
					int rightsibling = p.header.rightSibling;
					bcursor.setQ(btreeMgr.bufmgr.fixShared(new PageId(containerId, rightsibling), 0));
					bcursor.unfixP(); 
					bcursor.setP(bcursor.removeQ());
					p.wrap((SlottedPage) bcursor.getP().getPage());
				}
				if (!p.isLeaf()) {
					// find the child page and move down
					int childPageNumber = p.findChildPage(bcursor.searchKey);
					bcursor.setQ(btreeMgr.bufmgr.fixShared(new PageId(containerId, childPageNumber), 0));
					bcursor.unfixP();
					bcursor.setP(bcursor.removeQ());
					p.wrap((SlottedPage) bcursor.getP().getPage());
				}
			} while (!p.isLeaf());
		}
		
		/**
		 * Traverses a BTree down to the leaf level, and prepares the leaf page
		 * for inserting the new key. bcursor.p must hold the root node
		 * in update mode latch when this is called. When this returns
		 * bcursor.p will point to the page where the insert should take place.
		 * @return SearchResult containing information about where to insert the new key
		 */
		public final SearchResult doInsertTraverse(Transaction trx, BTreeCursor bcursor) throws BufferManagerException, FreeSpaceManagerException, TransactionException {
			updateModeTravese(trx, bcursor);
			/* At this point p points to the leaf page where the key is to be inserted */
			assert bcursor.getP() != null;
			assert bcursor.getP().isLatchedForUpdate();
			BTreeNode node = getBTreeNode();
			node.wrap((SlottedPage) bcursor.getP().getPage());
			assert node.isLeaf();
			assert !node.isDeallocated();
			assert node.getHighKey().compareTo(bcursor.searchKey) >= 0;
			if (!node.canAccomodate(bcursor.searchKey)) {
				bcursor.setQ(bcursor.removeP());
				doSplit(trx, bcursor);
				bcursor.setP(bcursor.removeQ());
			}
			bcursor.getP().upgradeUpdateLatch();
			node.wrap((SlottedPage) bcursor.getP().getPage());
			SearchResult sr = node.search(bcursor.searchKey);
			return sr;
		}
		
		/**
		 * Obtain a lock on the next key. Mode and duration are specified by the caller.
		 * @return True if insert can proceed, false if lock could not be obtained on next key.
		 */
		public final boolean doNextKeyLock(Transaction trx, BTreeCursor bcursor, int nextPageNumber, int nextk, LockMode mode,
				LockDuration duration) throws BufferManagerException, TransactionException {
			SlottedPage nextPage = null;
			IndexItem nextItem = null;
			Lsn nextPageLsn = null;
			// Make a note of current page and page Lsn
			int currentPageNumber = bcursor.getP().getPage().getPageId().getPageNumber();
			Lsn currentPageLsn = bcursor.getP().getPage().getPageLsn();
			
			if (nextPageNumber != -1) {
				// next key is in the right sibling page
				bcursor.setR(btreeMgr.bufmgr.fixShared(new PageId(containerId, nextPageNumber), 0));
				nextPage = (SlottedPage) bcursor.getR().getPage();
				BTreeNode nextNode = getBTreeNode();
				nextNode.wrap(nextPage);
				nextItem = nextNode.getItem(nextk);
				nextPageLsn = nextPage.getPageLsn();
			} else {
				// next key is in the current page
				BTreeNode nextNode = getBTreeNode();
				nextNode.wrap((SlottedPage) bcursor.getP().getPage());
				nextPageLsn = bcursor.getP().getPage().getPageLsn();
				if (nextk == -1) {
					nextItem = nextNode.getHighKey(); // represents infinity
				} else {
					nextItem = nextNode.getItem(nextk);
				}
			}
			try {
				trx.acquireLockNowait(nextItem.getLocation(), mode, duration);
				/*
				 * Instant duration lock succeeded. We can proceed with the insert.
				 */
				return true;
			} catch (TransactionException.LockException e) {
				// FIXME Test case
				if (log.isDebugEnabled()) {
					log.debug(LOG_CLASS_NAME, "doNextKeyLock", "SIMPLEDBM-LOG: Failed to acquire NOWAIT " + mode + " lock on " + nextItem.getLocation());
				}

				/*
				 * Someone else has inserted or deleted a key in the same key range.
				 * We need to unlatch all pages and unconditionally wait for a lock on
				 * the next key.
				 */
				bcursor.unfixP();
				bcursor.unfixR();
				/*
				 * Wait unconditionally for the other transaction to finish
				 */
				trx.acquireLock(nextItem.getLocation(), mode, LockDuration.INSTANT_DURATION);
				/*
				 * Now we have obtained the lock.
				 * We can continue from where we were if nothing has changed in the meantime
				 */
				bcursor.setP(btreeMgr.bufmgr.fixExclusive(new PageId(containerId, currentPageNumber), false, -1, 0));
				if (nextPageNumber != -1) {
					bcursor.setR(btreeMgr.bufmgr.fixShared(new PageId(containerId, nextPageNumber), 0));
				}
				if (currentPageLsn.compareTo(bcursor.getP().getPage().getPageLsn()) == 0) {
					if (nextPageNumber != -1 && nextPageLsn.compareTo(bcursor.getR().getPage().getPageLsn()) == 0) {
						/*
						 * Nothing has changed, so we can carry on as before.
						 */
						return true;
					}
				} else {
					/*
					 * We could avoid a rescan of the tree by checking that the next key
					 * previously identified hasn't changed. For now, we just give up and restart
					 * the insert.
					 */
					bcursor.unfixR();
					bcursor.unfixP();
				}
			}
			/*
			 * Restart insert
			 */
			return false;
		}
		
		/**
		 * @see #insert(Transaction, IndexKey, Location) 
		 */
		public final boolean doInsert(Transaction trx, IndexKey key, Location location) throws BufferManagerException, FreeSpaceManagerException, TransactionException, IndexException {

			BTreeCursor bcursor = new BTreeCursor();
			bcursor.searchKey = new IndexItem(key, location, -1, true, isUnique());

			try {
				/*
				 * Traverse to leaf page
				 */
				SearchResult sr = doInsertTraverse(trx, bcursor);

				int nextKeyPage = -1;
				int nextk = -1;

				if (sr.k == -1) {
					/* next key is in the next page or it is
					 * INFINITY if this is the rightmost page.
					 */
					BTreeNode node = getBTreeNode();
					node.wrap((SlottedPage) bcursor.getP().getPage());
					nextKeyPage = node.header.rightSibling;
					if (nextKeyPage != -1) {
						nextk = 1; // first key of next page
					}
				} else {
					/*
					 * We are positioned on a key that is either equal to
					 * searchkey or greater.
					 */
					if (sr.exactMatch) {
						Savepoint sp = trx.createSavepoint();
						boolean needRollback = false;
						/*
						 * Oops - possibly a unique constraint violation
						 */
						try {
							try {
								trx.acquireLockNowait(sr.item.getLocation(), LockMode.SHARED, LockDuration.MANUAL_DURATION);
							} catch (TransactionException.LockException e) {
								// FIXME Test case
								if (log.isDebugEnabled()) {
									log.debug(LOG_CLASS_NAME, "doInsert", "SIMPLEDBM-LOG: Failed to acquire NOWAIT " + LockMode.SHARED + " lock on " + sr.item.getLocation());
								}
								/*
								 * Failed to acquire conditional lock. 
								 * Need to unlatch page and retry unconditionally. 
								 */
								bcursor.unfixP();
								try {
									trx.acquireLock(sr.item.getLocation(), LockMode.SHARED, LockDuration.MANUAL_DURATION);
								} catch (TransactionException.LockException e1) {
									/*
									 * Deadlock
									 */
									throw new IndexException.LockException("SIMPLEDBM-ERROR: Failed to acquire NOWAIT " + LockMode.SHARED + " lock on " + sr.item.getLocation(), e1);
								}
								/*
								 * We have obtained the lock. We need to double check that the key
								 * still exists.
								 */
								bcursor.setP(btreeMgr.bufmgr.fixForUpdate(new PageId(containerId, BTreeIndexManagerImpl.ROOT_PAGE_NUMBER), 0));
								sr = doInsertTraverse(trx, bcursor);
							}
							if (sr.exactMatch) {
								/*
								 * FIXME
								 * Mohan says that for RR we need a commit duration lock, but
								 * for CS, maybe we should release the lock here??
								 */
								throw new UniqueConstraintViolationException("SIMPLEDBM-ERROR: Unique contraint would be violated by inserting key [" + key + "] and location [" + location + "]");
							}
							/*
							 * Key has been deleted or has been rolled back in the meantime
							 */
							needRollback = true;
							/*
							 * Start again from the beginning
							 */
							return false;
						} finally {
							if (needRollback) {
								trx.rollback(sp);
							}
						}
					} else {
						/*
						 * We are positioned on a key greater than the search key.
						 */
						nextk = sr.k;
					}
				}
				/*
				 * Try to obtain instant lock on next key.
				 */
				if (!doNextKeyLock(trx, bcursor, nextKeyPage, nextk, LockMode.EXCLUSIVE, LockDuration.INSTANT_DURATION)) {
					return false;
				}
				/*
				 * We can finally insert the key and be done with!
				 */
				InsertOperation insertOp = (InsertOperation) btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_INSERT_OPERATION);
				insertOp.setKeyFactoryType(keyFactoryType);
				insertOp.setLocationFactoryType(locationFactoryType);
				insertOp.setItem(bcursor.searchKey);
				insertOp.setUnique(isUnique());

				try {
					Lsn lsn = trx.logInsert(bcursor.getP().getPage(), insertOp);
					btreeMgr.redo(bcursor.getP().getPage(), insertOp);
					bcursor.getP().setDirty(lsn);
				} finally {
					bcursor.unfixP();
				}
			} finally {
				bcursor.unfixAll();
			}
			
			return true;
		}

		/* (non-Javadoc)
		 * @see org.simpledbm.rss.bt.BTree#insert(org.simpledbm.rss.tm.Transaction, org.simpledbm.rss.bt.IndexKey, org.simpledbm.rss.bt.Location)
		 */
		public final void insert(Transaction trx, IndexKey key, Location location) throws IndexException {
			try {
                boolean success = doInsert(trx, key, location);
                while (!success) {
                	success = doInsert(trx, key, location);
                }
            } catch (BufferManagerException e) {
                throw new IndexException.BufMgrException(e);
            } catch (FreeSpaceManagerException e) {
                throw new IndexException.SpaceMgrException(e);
            } catch (TransactionException e) {
                throw new IndexException.TrxException(e);
            }
		}

		/**
		 * @throws FreeSpaceManagerException 
		 * @see #delete(Transaction, IndexKey, Location)
		 */
		public final boolean doDelete(Transaction trx, IndexKey key, Location location) throws BufferManagerException, TransactionException, IndexException, FreeSpaceManagerException {

			BTreeCursor bcursor = new BTreeCursor();
			bcursor.searchKey = new IndexItem(key, location, -1, true, isUnique());
			
			try {
				/*
				 * Traverse to leaf page
				 */
				updateModeTravese(trx, bcursor);
				assert bcursor.getP() != null;
				assert bcursor.getP().isLatchedForUpdate();

				/* 
				 * At this point p points to the leaf page where the key is to be deleted 
				 */
				bcursor.getP().upgradeUpdateLatch();
				BTreeNode node = getBTreeNode();
				node.wrap((SlottedPage) bcursor.getP().getPage());
				assert node.isLeaf();
				assert !node.isDeallocated();
				assert node.getHighKey().compareTo(bcursor.searchKey) >= 0;

				SearchResult sr = node.search(bcursor.searchKey);
				if (!sr.exactMatch) {
					// key not found?? something is wrong
					throw new IndexException(); 
				}

				int nextKeyPage = -1;
				int nextk = -1;
				
				if (sr.k == node.getKeyCount()) {
					// this is the last key on the page
					nextKeyPage = node.header.rightSibling;
					if (nextKeyPage != -1) {
						nextk = 1;
					}
				}
				else {
					nextk = sr.k + 1;
				}
				
				/*
				 * Try to obtain commit duration lock on next key.
				 */
				if (!doNextKeyLock(trx, bcursor, nextKeyPage, nextk, LockMode.EXCLUSIVE, LockDuration.MANUAL_DURATION)) {
					return false;
				}
				/*
				 * Now we can delete the key and be done with!
				 */
				DeleteOperation deleteOp = (DeleteOperation) btreeMgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_DELETE_OPERATION);
				deleteOp.setKeyFactoryType(keyFactoryType);
				deleteOp.setLocationFactoryType(locationFactoryType);
				deleteOp.setItem(bcursor.searchKey);
				deleteOp.setUnique(isUnique());
				
				try {
					Lsn lsn = trx.logInsert(bcursor.getP().getPage(), deleteOp);
					btreeMgr.redo(bcursor.getP().getPage(), deleteOp);
					bcursor.getP().setDirty(lsn);
				}
				finally {
					bcursor.unfixP();
				}
			}
			finally {
				bcursor.unfixAll();
			}
			
			return true;
		}

		/**
		 * Delete specified key and location combination. It is an error if the key is not found.
		 * It is assumed that location is already locked in exclusive mode. At the end of this
		 * operation, the next key will be locked in exclusive mode, and the lock on location may be
		 * released.
		 */
		public final void delete(Transaction trx, IndexKey key,
                Location location) throws IndexException {
            try {
                boolean success = doDelete(trx, key, location);
                while (!success) {
                    success = doDelete(trx, key, location);
                }
            } catch (BufferManagerException e) {
                throw new IndexException.BufMgrException(e);
            } catch (FreeSpaceManagerException e) {
                throw new IndexException.SpaceMgrException(e);
            } catch (TransactionException e) {
                throw new IndexException.TrxException(e);
            }
        }

		final SearchResult doSearch(IndexCursorImpl icursor) throws BufferManagerException {
			BTreeNode node = getBTreeNode();
			node.wrap((SlottedPage) icursor.bcursor.getP().getPage());
			icursor.eof = false;
			SearchResult sr = node.search(icursor.currentKey);
			if (sr.exactMatch && icursor.fetchCount > 0) {
				if (sr.k == node.getKeyCount()) {
					// Key to be fetched is in the next page
					int nextPage = node.header.rightSibling;
					if (nextPage == -1) {
						icursor.eof = true;
						sr.k = sr.k + 1;
						sr.exactMatch = false;
						sr.item = node.getItem(sr.k);
						return sr;
					}
					PageId nextPageId = new PageId(containerId, nextPage);
					icursor.bcursor.setQ(icursor.bcursor.removeP());
					icursor.bcursor.setP(btreeMgr.bufmgr.fixShared(nextPageId, 0));
					node.wrap((SlottedPage) icursor.bcursor.getP().getPage());
					icursor.bcursor.unfixQ();
					sr = node.search(icursor.currentKey);
				} else {
					// move to the next key
					sr.k = sr.k + 1;
					sr.exactMatch = false;
					sr.item = node.getItem(sr.k);
				}
			}
			else if (node.header.keyCount == 1) {
				// only one key
				icursor.eof = true;
				sr.k = 1;
				sr.exactMatch = false;
				sr.item = node.getItem(sr.k);
				return sr;
			}
			return sr;
			
		}
		
		/**
		 * Fetches the next available key, after locking the corresponding Location in the
		 * specified mode. Handles the situation where the current key has been deleted.
		 * 
		 * @param trx Transaction that is managing this fetch
		 * @param cursor The BTreeScan cursor
		 * @return True if successful, fals if operation needs to be tried again 
		 * @throws BufferManagerException
		 * @throws IndexException
		 * @throws TransactionException
		 */
		public final boolean doFetch(Transaction trx, IndexScan cursor) throws BufferManagerException, IndexException, TransactionException {
			IndexCursorImpl icursor = (IndexCursorImpl) cursor;
			try {
				boolean doSearch = false;
				BTreeNode node = getBTreeNode();
				if (icursor.fetchCount > 0) {
					// This is not the first call to fetch
					// Check to see if the BTree should be scanned to locate the key
					icursor.bcursor.setP(btreeMgr.bufmgr.fixShared(icursor.pageId, 0));
					node.wrap((SlottedPage) icursor.bcursor.getP().getPage());
					if (node.isDeallocated() || !node.isLeaf()) {
						// The node that contained current key is no longer part of the tree, hence scan is necessary
						doSearch = true;
						icursor.bcursor.unfixP();
					} else {
						// The node still exists, so we need to check whether the previously returned key is bound to the 
						// node.
						if (!node.getPage().getPageLsn().equals(icursor.pageLsn) && !node.covers(icursor.currentKey)) {
							// The previous key is no longer bound to the node
							doSearch = true;
							icursor.bcursor.unfixP();
						}
					}
				} else {
					// First call to fetch, hence tree must be scanned.
					doSearch = true;
				}

				if (doSearch) {
					icursor.bcursor.setSearchKey(icursor.currentKey);
					readModeTraverse(icursor.bcursor);
				}

				SearchResult sr = doSearch(icursor);

				Savepoint sp = trx.createSavepoint();
				try {
					trx.acquireLockNowait(sr.item.getLocation(), icursor.lockMode, LockDuration.MANUAL_DURATION);
					icursor.currentKey = sr.item;
					icursor.pageId = icursor.bcursor.getP().getPage().getPageId();
					icursor.pageLsn = icursor.bcursor.getP().getPage().getPageLsn();
					icursor.bcursor.unfixP();
					icursor.fetchCount++;
					return true;
				} catch (TransactionException.LockException e) {
					// FIXME Test case
					if (log.isDebugEnabled()) {
						log.debug(LOG_CLASS_NAME, "doFetch", "SIMPLEDBM-LOG: failed to acquire NOWAIT " + icursor.lockMode + " lock on " + sr.item.getLocation());
					}
					/*
					 * Failed to acquire conditional lock. 
					 * Need to unlatch page and retry unconditionally. 
					 */
					icursor.bcursor.unfixP();
					try {
						trx.acquireLock(sr.item.getLocation(), icursor.lockMode, LockDuration.MANUAL_DURATION);
					} catch (TransactionException.LockException e1) {
						/*
						 * Deadlock
						 */
						throw new IndexException.LockException("SIMPLEDBM-ERROR: Failed to acquire " + icursor.lockMode + " lock on " + sr.item.getLocation(), e1);
					}
					/*
					 * We have obtained the lock. We need to double check that the searched key
					 * still exists.
					 */
					// TODO - could avoid the tree traverse here by checking the old page
					
					icursor.bcursor.setSearchKey(icursor.currentKey);
					readModeTraverse(icursor.bcursor);
					SearchResult sr1 = doSearch(icursor);
					if (sr1.item.equals(sr.item)) {
						// we found the same key again
						icursor.currentKey = sr1.item;
						icursor.pageId = icursor.bcursor.getP().getPage().getPageId();
						icursor.pageLsn = icursor.bcursor.getP().getPage().getPageLsn();
						icursor.bcursor.unfixP();
						icursor.fetchCount++;
						return true;
					}
					trx.rollback(sp);
				}
			} finally {
				icursor.bcursor.unfixAll();
			}
			return false;
		}

		public final void fetch(Transaction trx, IndexScan cursor) throws BufferManagerException, IndexException, TransactionException {
			boolean success = doFetch(trx, cursor);
			while (!success) {
				success = doFetch(trx, cursor);
			}
		}
		
		public final IndexScan openScan(IndexKey key, Location location, LockMode mode) {
			IndexCursorImpl icursor = new IndexCursorImpl();
			icursor.searchKey = new IndexItem(key, location, -1, true, isUnique());
			icursor.currentKey = icursor.searchKey;
			icursor.fetchCount = 0;
			icursor.lockMode = mode;
			icursor.btree = this;
			return icursor;
		}
	}
	
	public static final class IndexCursorImpl implements IndexScan {
		PageId pageId;
		
		Lsn pageLsn;
		
		IndexItem searchKey;
		
		IndexItem currentKey;
		
		int fetchCount = 0;
		
		final BTreeCursor bcursor = new BTreeCursor();
		
		LockMode lockMode;
		
		BTreeImpl btree;
		
		boolean eof = false;

		public final boolean fetchNext(Transaction trx) throws IndexException {
			if (!eof) { 
				try {
					btree.fetch(trx, this);
				} catch (BufferManagerException e) {
					throw new IndexException.BufMgrException(e);
				} catch (TransactionException e) {
					throw new IndexException.TrxException(e);
				}
				return true;
			}
			return false;
		}
		
		public final void close() throws IndexException {
			try {
				bcursor.unfixAll();
			} catch (BufferManagerException e) {
				throw new IndexException.BufMgrException(e);
			}
		}
		
		public final IndexKey getCurrentKey() {
			if (currentKey == null) {
				return null;
			}
			return currentKey.getKey();
		}
		
		public final Location getCurrentLocation() {
			if (currentKey == null) {
				return null;
			}
			return currentKey.getLocation();
		}
		
		public final boolean isEof() {
			return eof;
		}
	}
	
	public static final class BTreeCursor {
		
		private BufferAccessBlock q = null;
		
		private BufferAccessBlock r = null;
		
		private BufferAccessBlock p = null;
		
		public IndexItem searchKey = null;
		
		public BTreeCursor() {
		}

		public final BufferAccessBlock getP() {
			return p;
		}

		public final BufferAccessBlock removeP() {
			BufferAccessBlock bab = p;
			p = null;
			return bab;
		}
		
		public final void setP(BufferAccessBlock p) {
			assert this.p == null;
			this.p = p;
		}
		
		public final BufferAccessBlock getQ() {
			return q;
		}

		public final BufferAccessBlock removeQ() {
			BufferAccessBlock bab = q;
			q = null;
			return bab;
		}
		
		public final void setQ(BufferAccessBlock q) {
			assert this.q == null;
			this.q = q;
		}

		public final BufferAccessBlock getR() {
			return r;
		}

		public final BufferAccessBlock removeR() {
			BufferAccessBlock bab = r;
			r = null;
			return bab;
		}

		public final void setR(BufferAccessBlock r) {
			assert this.r == null;
			this.r = r;
		}

		public final IndexItem getSearchKey() {
			return searchKey;
		}

		public final void setSearchKey(IndexItem searchKey) {
			this.searchKey = searchKey;
		}
	
		public final void unfixP() throws BufferManagerException {
			if (p != null) {
				p.unfix();
				p = null;
			}
		}

		public final void unfixQ() throws BufferManagerException {
			if (q != null) {
				q.unfix();
				q = null;
			}
		}

		public final void unfixR() throws BufferManagerException {
			if (r != null) {
				r.unfix();
				r = null;
			}
		}
		
		public final void unfixAll() throws BufferManagerException {
			BufferManagerException e = null;
			try {
				unfixP();
			}
			catch (BufferManagerException e1) {
				e = e1;
			}
			try {
				unfixQ();
			}
			catch (BufferManagerException e1) {
				e = e1;
			}
			try {
				unfixR();
			}
			catch (BufferManagerException e1) {
				e = e1;
			}
			if (e != null) {
				throw e;
			}
		}
	}
	
	public static final class SearchResult {
		int k = -1;
		IndexItem item = null;
		boolean exactMatch = false;
	}
	

	/**
	 * Manages the contents of a B-link tree node. Handles the differences between
	 * leaf nodes and index nodes.
	 * <pre>
	 * Leaf nodes have following structure:
	 * [header] [item1] [item2] ... [itemN] [highkey]
	 * item[0] = header 
	 * item[1,header.KeyCount-1] = keys
	 * item[header.keyCount] = high key 
	 * The highkey in a leaf node is an extra item, and may or may not be  
	 * the same as the last key [itemN] in the page. Operations that change the
	 * highkey in leaf pages are Split, Merge and Redistribute. All keys in the
	 * page are guaranteed to be &lt;= highkey. 
	 * 
	 * Index nodes have following structure:
	 * [header] [item1] [item2] ... [itemN]
	 * item[0] = header 
	 * item[1,header.KeyCount] = keys
	 * The last key is also the highkey.
	 * Note that the rightmost index page at any level has a special
	 * key as the highkey - this key has a value of INFINITY. 
	 * Each item in an index key contains a pointer to a child page.
	 * The child page contains keys that are &lt;= than the item key.
	 * </pre>
	 * @author Dibyendu Majumdar
	 * @since 19-Sep-2005
	 */
	public static final class BTreeNode {
		
		static final short NODE_TYPE_LEAF = 1;
		static final short NODE_TREE_UNIQUE = 2;
		static final short NODE_TREE_DEALLOCATED = 4;
		
		/**
		 * Page being managed.
		 */
		SlottedPage page;
		
		final IndexItemHelper btree;

		/**
		 * Cached header.
		 */
		BTreeNodeHeader header;
		
		BTreeNode(IndexItemHelper btree) {
			this.btree = btree;
		}
		
		public final void dumpAsXml() {
			System.out.println("<page containerId=\"" + page.getPageId().getContainerId() + "\" pageNumber=\"" +
					page.getPageId().getPageNumber() + "\">");
			System.out.println("	<header>");
			System.out.println("		<locationfactory>" + header.locationFactoryType + "</locationfactory>");
			System.out.println("		<keyfactory>" + header.keyFactoryType + "</keyfactory>");
			System.out.println("		<unique>" + (isUnique() ? "yes" : "false") + "</unique>");
			System.out.println("		<leaf>" + (isLeaf() ? "yes" : "false") + "</leaf>");
			System.out.println("		<leftsibling>" + header.leftSibling + "</leftsibling>");
			System.out.println("		<rightsibling>" + header.rightSibling + "</rightsibling>");
			System.out.println("		<smppagenumber>" + page.getSpaceMapPageNumber() + "</smppagenumber>");
			System.out.println("	</header>");
			System.out.println("	<items>");
			for (int k = 1; k < page.getNumberOfSlots(); k++) {
				if (page.isSlotDeleted(k)) {
					continue;
				}
				IndexItem item = getItem(k);
				System.out.println("		<item>");
				System.out.println("			<childpagenumber>" + item.childPageNumber + "</childpagenumber>");
				System.out.println("			<location>" + item.getLocation() + "</location>");
				System.out.println("			<key>" + item.getKey().toString() + "</key>");
				System.out.println("		</item>");
			}
			System.out.println("	</items>");
			System.out.println("</page>");
				
		}
		
		/**
		 * Dumps contents of the BTree node.
		 */
		public final void dump() {
			dumpAsXml();
			page.dump();
			assert page.getSpaceMapPageNumber() != -1;
			if (page.getNumberOfSlots() > 0) {
				BTreeNodeHeader header = (BTreeNodeHeader) page.get(0, new BTreeNodeHeader());
				DiagnosticLogger.log("BTreeNodeHeader=" + header);
				for (int k = 1; k < page.getNumberOfSlots(); k++) {
					if (page.isSlotDeleted(k)) {
						continue;
					}
					IndexItem item = (IndexItem) page.get(k, getNewIndexItem());
					if (k == header.keyCount) {
						DiagnosticLogger.log("IndexItem[" + k + "] (HIGHKEY) = " + item);
					}
					else {
						DiagnosticLogger.log("IndexItem[" + k + "] = " + item);
					}
				}
			}
		}
		
		public final void wrap(SlottedPage page) {
			this.page = page;
			header = (BTreeNodeHeader) page.get(0, new BTreeNodeHeader());
		}

		final BTreeNodeHeader getHeader() {
			return header;
		}
		
		public final IndexItem getNewIndexItem() {
			return new IndexItem(btree.getNewIndexKey(),
					btree.getNewLocation(), -1, isLeaf(), isUnique());
		}
		
		/**
		 * Returns the high key. High key is always the last physical key on the page.
		 */
		final IndexItem getHighKey() {
			return getItem(header.keyCount);
		}

		/**
		 * Returns the largest key on the page.
		 */
		public final IndexItem getLastKey() {
			return getItem(getKeyCount());
		}
		
		/**
		 * Returns specified item. 
		 */
		public final IndexItem getItem(int slotNumber) {
			return (IndexItem) page.get(slotNumber, getNewIndexItem());
		}
		
		public final IndexItem getInfiniteKey() {
			return new IndexItem(btree.getMaxIndexKey(),
					btree.getNewLocation(), -1, isLeaf(), isUnique());
		}
		
		/**
		 * Inserts item at specified position. Existing items are shifted
		 * to the right if necessary.  
		 */
		public final void insert(int slotNumber, IndexItem item) {
			page.insertAt(slotNumber, item, false);
		}
		
		public final void purge(int slotNumber) {
			page.purge(slotNumber);
		}
		
		/**
		 * Replaces the item at specified position. 
		 */
		public final void replace(int slotNumber, IndexItem item) {
			page.insertAt(slotNumber, item, true);
		}
		
		/**
		 * Tests whether the page is part of a unique index.
		 */
		public final boolean isUnique() {
			int flags = page.getFlags();
			return (flags & NODE_TREE_UNIQUE) != 0;
		}

		/**
		 * Sets the unique flag.
		 */
		public final void setUnique() {
			int flags = page.getFlags();
			page.setFlags((short) (flags | NODE_TREE_UNIQUE));
		}

		/**
		 * Resets the unique flag.
		 */
		public final void unsetUnique() {
			int flags = page.getFlags();
			page.setFlags((short) (flags & ~NODE_TREE_UNIQUE));
		}
		
		/**
		 * Tests whether this is a leaf page.
		 */
		public final boolean isLeaf() {
			int flags = page.getFlags();
			return (flags & NODE_TYPE_LEAF) != 0;
		}

		/**
		 * Sets the leaf flag.
		 */
		public final void setLeaf() {
			int flags = page.getFlags();
			page.setFlags((short) (flags | NODE_TYPE_LEAF));
		}

		/**
		 * Clears the leaf flag.
		 */
		public final void unsetLeaf() {
			int flags = page.getFlags();
			page.setFlags((short) (flags & ~NODE_TYPE_LEAF));
		}

		/**
		 * Is this the root page?
		 */
		public final boolean isRoot() {
			return page.getPageId().getPageNumber() == BTreeIndexManagerImpl.ROOT_PAGE_NUMBER; 
		}
		
		/**
		 * Tests whether this page has been marked as deallocated.
		 */
		public final boolean isDeallocated() {
			int flags = page.getFlags();
			return (flags & NODE_TREE_DEALLOCATED) != 0;
		}

		/**
		 * Sets the deallocated flag.
		 */
		public final void setDeallocated() {
			int flags = page.getFlags();
			page.setFlags((short) (flags | NODE_TREE_DEALLOCATED));
		}

		/**
		 * Clears the deallocated flag.
		 */
		public final void unsetDeallocated() {
			int flags = page.getFlags();
			page.setFlags((short) (flags & ~NODE_TREE_DEALLOCATED));
		}
		
		/**
		 * Returns number of keys stored in the page. For leaf pages, the high key is
		 * excluded.
		 */
		final int getKeyCount() {
			if (isLeaf()) {
				return header.keyCount-1;
			}
			return header.keyCount;
		}
		
		/**
		 * Returns the number of physical keys in the page, including the extra
		 * high key in leaf pages.
		 */
		public final int getNumberOfKeys() {
			return header.keyCount;
		}
		
		final Page getPage() {
			return page;
		}
		
		/**
		 * Finds the key that should be used as the median key when
		 * splitting a page. 
		 */
		final short getSplitKey() {
			if (BTreeIndexManagerImpl.testingFlag > 0) {
				return 4;
			}
			int halfSpace = page.getSpace()/2;
			int space = 0;
			for (short k = 1; k <= header.keyCount; k++) {
				space += page.getSlotLength(k);
				if (space > halfSpace) {
					return k;
				}
			}
			throw new IllegalStateException();
		}

		/**
		 * Sets the keycount in the header record.  
		 */
		public final void setNumberOfKeys(int keyCount) {
			header.keyCount = keyCount;
		}
		
		/**
		 * Updates the header stored within the page.
		 */
		public final void updateHeader() {
			page.insertAt(0, header, true);
		}
		
		/**
		 * Searches for the supplied key. If there is an
		 * exact match, SearchResult.exactMatch will be set.
		 * If a key is found &gt;= 0 the supplied key,
		 * SearchResult.k and SearchResult.item will be set to it.
		 * If all keys in the page are &lt; search key then, 
		 * SearchResult.k will be set to -1 and SearchResult.item will
		 * be null. 
		 */
		public final SearchResult search(IndexItem key) {
			SearchResult result = new SearchResult();
			int k = 1;
			// We do not look at the high key when searching
			for (k = 1; k <= getKeyCount(); k++) {
				IndexItem item = getItem(k);
				int comp = item.compareTo(key);
				if (comp >= 0) {
					result.k = k;
					result.item = item;
					if (comp == 0) {
						result.exactMatch = true;
					}
					break;
				}
			}
			return result;
		}
		
		/**
		 * Finds the child page associated with an index item.
		 */
		public final int findChildPage(IndexItem key) {
			if (isLeaf()) {
				// TODO throw exception
			}
			int k = 1;
			for (k = 1; k <= getKeyCount(); k++) {
				IndexItem item = getItem(k);
				if (item.compareTo(key) >= 0) {
					/* Item covers key */
					return item.getChildPageNumber();
				}
			}
			return -1;
		}
		
		/**
		 * Finds the index item associated with a child page.
		 */
		public final IndexItem findIndexItem(int childPageNumber) {
			if (isLeaf()) {
				// TODO throw exception
			}
			for (int k = 1; k <= getKeyCount(); k++) {
				IndexItem item = getItem(k);
				if (item.getChildPageNumber() == childPageNumber) {
					return item;
				}
			}
			return null;
		}

		/**
		 * Finds the index item for left sibling of the
		 * specified child page.
		 */
		public final IndexItem findPrevIndexItem(int childPageNumber) {
			if (isLeaf()) {
				// TODO throw exception
			}
			IndexItem prev = null;
			for (int k = 1; k <= getKeyCount(); k++) {
				IndexItem item = getItem(k);
				if (item.getChildPageNumber() == childPageNumber) {
					break;
				}
				prev = item;
			}
			return prev;
		}

		/**
		 * Tests if the current page can be merged with its right sibling. 
		 */
		public final boolean canMergeWith(BTreeNode rightSibling) {
			if (BTreeIndexManagerImpl.testingFlag > 0) {
				int n = getNumberOfKeys() - (isLeaf() ? 1 : 0) +
					rightSibling.getNumberOfKeys();
				return n <= 8;
			}
			int requiredSpace = 0;
			if (isLeaf()) {
				// delete the high key
				requiredSpace -= page.getSlotLength(header.keyCount);
			}
			for (int k = 1; k <= rightSibling.getNumberOfKeys(); k++) {
				// add all keys from the right sibling
				requiredSpace += rightSibling.page.getSlotLength(k);
			}
			// TODO should we leave some slack here?
            return requiredSpace < page.getFreeSpace();
        }
		
		public final boolean canAccomodate(IndexItem v) {
			if (BTreeIndexManagerImpl.testingFlag > 0) {
				return getNumberOfKeys() < 8;
			}
			// FIXME call a method in slottedpage
			// int requiredSpace = v.getStoredLength() + 6;
            int requiredSpace = v.getStoredLength() + page.getSlotOverhead();
			// TODO should we leave some slack here?
            return requiredSpace <= page.getFreeSpace();
        }
		
		/**
		 * Determines if the specified key "bound" to this page.
		 */
		public final boolean covers(IndexItem v) {
			IndexItem first = getItem(1);
			IndexItem last = getItem(getKeyCount());
			return first.compareTo(v) <= 0 && last.compareTo(v) >= 0;
		}
		
		public final int minimumKeys() {
			return (2 - (isRoot() ? 1 : 0));
		}
		
		/**
		 * Tests whether this page is about to underflow. 
		 * This will be true if it is a root page with only one
		 * child or any other page with only two children/keys. 
		 * <p>
		 */
		public final boolean isAboutToUnderflow() {
			return getKeyCount() == minimumKeys();
		}
		
	}
	
	public static final class SpaceCheckerImpl implements FreeSpaceChecker {

		public final boolean hasSpace(int value) {
			return value == 0;
		}
		
	}

	/**
	 * Every page in the BTree has a header item at slot 0.   
	 */
	public static final class BTreeNodeHeader implements Storable {
		
		static final int SIZE = TypeSize.INTEGER * 5;

		/**
		 * Pointer to left sibling page. Note that although we have this,
		 * this is not kept fully up-to-date because to do so would require extra
		 * work when merging pages. FIXME
		 */
		int leftSibling = -1;
		
		/**
		 * Pointer to right sibling page.
		 */
		int rightSibling = -1;
		
		/**
		 * Total number of keys present in the page. Includes the high key
		 * in leaf pages.
		 */
		int keyCount = 0;

		/**
		 * Type code for the key factory to be used to manipulate index keys.
		 */
		int keyFactoryType = -1;
		
		/**
		 * Typecode of the location factory to be used for generating Location objects.
		 */
		int locationFactoryType = -1;
		
		
		/* (non-Javadoc)
		 * @see org.simpledbm.io.Storable#getStoredLength()
		 */
		public final int getStoredLength() {
			return SIZE;
		}

		/* (non-Javadoc)
		 * @see org.simpledbm.io.Storable#retrieve(java.nio.ByteBuffer)
		 */
		public final void retrieve(ByteBuffer bb) {
			keyFactoryType = bb.getInt();
			locationFactoryType = bb.getInt();
			leftSibling = bb.getInt();
			rightSibling = bb.getInt();
			keyCount = bb.getInt();
		}

		/* (non-Javadoc)
		 * @see org.simpledbm.io.Storable#store(java.nio.ByteBuffer)
		 */
		public final void store(ByteBuffer bb) {
			bb.putInt(keyFactoryType);
			bb.putInt(locationFactoryType);
			bb.putInt(leftSibling);
			bb.putInt(rightSibling);
			bb.putInt(keyCount);
		}
		
		@Override
		public final String toString() {
			return "BTreeNodeHeader(keyFactory=" + keyFactoryType + ", locationFactory=" + locationFactoryType + 
				", leftSibling=" + leftSibling + ", rightSibling=" + rightSibling + 
				", keyCount=" + keyCount + ")";
		}

		final int getKeyCount() {
			return keyCount;
		}

		final void setKeyCount(int keyCount) {
			this.keyCount = keyCount;
		}

		final int getLeftSibling() {
			return leftSibling;
		}

		final void setLeftSibling(int leftSibling) {
			this.leftSibling = leftSibling;
		}

		final int getRightSibling() {
			return rightSibling;
		}

		final void setRightSibling(int rightSibling) {
			this.rightSibling = rightSibling;
		}

		final int getKeyFactoryType() {
			return keyFactoryType;
		}

		final void setKeyFactoryType(int keyFactoryType) {
			this.keyFactoryType = keyFactoryType;
		}

		final int getLocationFactoryType() {
			return locationFactoryType;
		}

		final void setLocationFactoryType(int locationFactoryType) {
			this.locationFactoryType = locationFactoryType;
		}
		
	}

	public static abstract class BTreeLogOperation extends BaseLoggable implements ObjectRegistryAware, IndexItemHelper {

		/**
		 * Is this a leaf level operation.
		 */
		private boolean leaf;

		/**
		 * Is this part of a unique index?
		 */
		private boolean unique;

		/**
		 * Type code for the key factory to be used to manipulate index keys.
		 */
		private int keyFactoryType;
		
		/**
		 * Typecode of the location factory to be used for generating Location objects.
		 */
		private int locationFactoryType;
		
		private transient ObjectRegistry objectFactory;
		
		private transient IndexKeyFactory keyFactory;
		
		private transient LocationFactory locationFactory;

		@Override
		public int getStoredLength() {
			int n = super.getStoredLength();
			n += 2;
			n += TypeSize.INTEGER * 2;
			return n;
		}

		@Override
		public void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			keyFactoryType = bb.getInt();
			locationFactoryType = bb.getInt();
			keyFactory = (IndexKeyFactory) objectFactory.getInstance(keyFactoryType);
			locationFactory = (LocationFactory) objectFactory.getInstance(locationFactoryType);
			leaf = bb.get() == 1;
			unique = bb.get() == 1;
		}

		@Override
		public void store(ByteBuffer bb) {
			super.store(bb);
			bb.putInt(keyFactoryType);
			bb.putInt(locationFactoryType);
			bb.put(leaf ? (byte)1 : (byte)0);
			bb.put(unique ? (byte)1 : (byte)0);
		}
		
		public final void setObjectFactory(ObjectRegistry objectFactory) {
			this.objectFactory = objectFactory;
		}

		public final IndexKey getNewIndexKey() {
			return keyFactory.newIndexKey(getPageId().getContainerId());
		}
		
		public final Location getNewLocation() {
			return locationFactory.newLocation();
		}

		public final IndexKey getMaxIndexKey() {
			return keyFactory.maxIndexKey(getPageId().getContainerId());
		}

		public final IndexItem makeNewItem() {
			return new IndexItem(keyFactory.newIndexKey(getPageId().getContainerId()), locationFactory.newLocation(), -1, leaf, unique); 
		}
		
		public final void setKeyFactoryType(int keyFactoryType) {
			this.keyFactoryType = keyFactoryType;
			keyFactory = (IndexKeyFactory) objectFactory.getInstance(keyFactoryType);
		}

		public final void setLocationFactoryType(int locationFactoryType) {
			this.locationFactoryType = locationFactoryType;
			locationFactory = (LocationFactory) objectFactory.getInstance(locationFactoryType);
		}

		public final int getKeyFactoryType() {
			return keyFactoryType;
		}

		public final int getLocationFactoryType() {
			return locationFactoryType;
		}

		public final boolean isUnique() {
			return unique;
		}

		public final void setUnique(boolean unique) {
			this.unique = unique;
		}

		public final boolean isLeaf() {
			return leaf;
		}

		public final void setLeaf(boolean leaf) {
			this.leaf = leaf;
		}

		final IndexKeyFactory getKeyFactory() {
			return keyFactory;
		}

		final LocationFactory getLocationFactory() {
			return locationFactory;
		}

		protected final ObjectRegistry getObjectFactory() {
			return objectFactory;
		}

		public void copyFrom(BTreeLogOperation other) {
			this.keyFactory = other.keyFactory;
			this.keyFactoryType = other.keyFactoryType;
			this.leaf = other.leaf;
			this.locationFactory = other.locationFactory;
			this.locationFactoryType = other.locationFactoryType;
			this.objectFactory = other.objectFactory;
			this.unique = other.unique;
		}
		
		@Override
		public String toString() {
			return super.toString() + ", isLeaf=" + (isLeaf() ? "true" : "false") +
				", isUnique=" + (isUnique() ? "true": "false") +
				", keyFactoryType=" + getKeyFactoryType() + 
				", locationFactoryType=" + getLocationFactoryType();
		}
	}
	
	public static abstract class KeyUpdateOperation extends BTreeLogOperation {

		private IndexItem item;

		private int position = -1;
		
		public final int getPosition() {
			return position;
		}

		public final void setPosition(int position) {
			this.position = position;
		}

		public final IndexItem getItem() {
			return item;
		}

		public final void setItem(IndexItem item) {
			this.item = item;
			assert item.isLeaf();
		}

		@Override
		public final void copyFrom(BTreeLogOperation other) {
			super.copyFrom(other);
			KeyUpdateOperation o = (KeyUpdateOperation) other;
			this.item = o.item;
			this.position = o.position;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			position = bb.getInt();
			item = new IndexItem(getKeyFactory().newIndexKey(getPageId().getContainerId()), getLocationFactory().newLocation(), -1, true, isUnique()); 
			item.retrieve(bb);
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putInt(position);
			item.store(bb);
		}

		@Override
		public final int getStoredLength() {
			return super.getStoredLength() + TypeSize.INTEGER + item.getStoredLength();
		}
	}
	
	/**
	 * Log record data for inserting a key into the BTree.
	 */
	public static final class InsertOperation extends KeyUpdateOperation implements LogicalUndo {
		@Override
		public final void init() {
		}
	}
	
	public static final class UndoInsertOperation extends KeyUpdateOperation implements Compensation {
		@Override
		public final void init() {
		}
	}

	public static final class DeleteOperation extends KeyUpdateOperation implements LogicalUndo {
		@Override
		public final void init() {
		}
	}
	
	public static final class UndoDeleteOperation extends KeyUpdateOperation implements Compensation {
		@Override
		public final void init() {
		}
	}
	
	/**
	 * Split operation log record.
	 */
	public static final class SplitOperation extends BTreeLogOperation implements Compensation, MultiPageRedo {

		/**
		 * Page Id of new sibling page.
		 */
		int newSiblingPageNumber;
		
		/**
		 * The items that will become part of the new sibling page.
		 * Includes the highkey in leaf pages.
		 */
		LinkedList<IndexItem> items;
		
		/**
		 * The new sibling page will point to current page's right sibling.
		 */
		int rightSibling;
		
		/**
		 * Space map page that owns the new page.
		 */
		int spaceMapPageNumber;
		
		/**
		 * The value of the high key - used only in leaf pages.
		 */
		IndexItem highKey;
		
		/**
		 * After splitting, this is the new keycount of the page.
		 * Includes highkey if this is a leaf page.
		 */
		short newKeyCount;
		
		@Override
		public final void init() {
			items = new LinkedList<IndexItem>();
		}

		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += TypeSize.SHORT;
			for (IndexItem item: items) {
				n += item.getStoredLength();
			}
			if (isLeaf()) {
				n += highKey.getStoredLength();
			}
			n += TypeSize.INTEGER * 3;
			n += TypeSize.SHORT;
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			short numberOfItems = bb.getShort();
			items = new LinkedList<IndexItem>();
			for (int i = 0; i < numberOfItems; i++) {
				IndexItem item = new IndexItem(getKeyFactory().newIndexKey(getPageId().getContainerId()), getLocationFactory().newLocation(), -1, isLeaf(), isUnique()); 
				item.retrieve(bb);
				items.add(item);
			}
			if (isLeaf()) {
				highKey = new IndexItem(getKeyFactory().newIndexKey(getPageId().getContainerId()), getLocationFactory().newLocation(), -1, isLeaf(), isUnique());
				highKey.retrieve(bb);
			}
			newSiblingPageNumber = bb.getInt();
			rightSibling = bb.getInt();
			spaceMapPageNumber = bb.getInt();
			newKeyCount = bb.getShort();
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putShort((short) items.size());
			for (IndexItem item: items) {
				item.store(bb);
			}
			if (isLeaf()) {
				highKey.store(bb);
			}
			bb.putInt(newSiblingPageNumber);
			bb.putInt(rightSibling);
			bb.putInt(spaceMapPageNumber);
			bb.putShort(newKeyCount);
		}

		@Override
		public final String toString() {
			return super.toString();
		}

		/**
		 * Returns pages that are affected by this log.
		 * Included are the page that is being split, and the newly allocated page.
		 */
		public final PageId[] getPageIds() {
			return new PageId[] { getPageId(), new PageId(getPageId().getContainerId(), newSiblingPageNumber) };
		}
	}

	public static final class MergeOperation extends BTreeLogOperation implements Redoable, MultiPageRedo {

		/**
		 * The items that will become part of the new sibling page.
		 * Includes the highkey in leaf pages.
		 */
		LinkedList<IndexItem> items;
		
		/**
		 * The new sibling page will point to current page's right sibling.
		 */
		int rightSibling;
		
		int rightSiblingSpaceMapPage;
		
		int rightRightSibling;
		
		@Override
		public final void init() {
			items = new LinkedList<IndexItem>();
		}

		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += TypeSize.SHORT;
			for (IndexItem item: items) {
				n += item.getStoredLength();
			}
			n += TypeSize.INTEGER * 3;
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			short numberOfItems = bb.getShort();
			items = new LinkedList<IndexItem>();
			for (int i = 0; i < numberOfItems; i++) {
				IndexItem item = new IndexItem(getKeyFactory().newIndexKey(getPageId().getContainerId()), getLocationFactory().newLocation(), -1, isLeaf(), isUnique()); 
				item.retrieve(bb);
				items.add(item);
			}
			rightSibling = bb.getInt();
			rightSiblingSpaceMapPage = bb.getInt();
			rightRightSibling = bb.getInt();
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putShort((short) items.size());
			for (IndexItem item: items) {
				item.store(bb);
			}
			bb.putInt(rightSibling);
			bb.putInt(rightSiblingSpaceMapPage);
			bb.putInt(rightRightSibling);
		}

		public final PageId[] getPageIds() {
			return new PageId[] { getPageId(), new PageId(getPageId().getContainerId(), rightSibling),
						new PageId(getPageId().getContainerId(), rightSiblingSpaceMapPage) };
		}		

		@Override
		public final String toString() {
			return "MergeOperation(" + super.toString() + ", rightSibling=" + rightSibling + ")";
		}

	}
	
	public static final class LinkOperation extends BTreeLogOperation implements Redoable {

		int leftSibling;
		
		int rightSibling;
		
		IndexItem leftChildHighKey;
		
		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += leftChildHighKey.getStoredLength();
			n += TypeSize.INTEGER * 2;
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			leftChildHighKey = makeNewItem(); 
			leftChildHighKey.retrieve(bb);
			rightSibling = bb.getInt();
			leftSibling = bb.getInt();
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			leftChildHighKey.store(bb);
			bb.putInt(rightSibling);
			bb.putInt(leftSibling);
		}

		@Override
		public final void init() {
		}

		@Override
		public final String toString() {
			return "LinkOperation(" + super.toString() + ", leftSibling=" + leftSibling +
				", rightSibling=" + rightSibling + ")";
		}
	
	}
	
	/**
	 * Log record for the Unlink operation. It is applied to the parent page.
	 * @see BTreeImpl#doUnlink(Transaction, BTreeCursor)
	 * @see BTreeIndexManagerImpl#redoUnlinkOperation(Page, UnlinkOperation) 
	 */
	public static final class UnlinkOperation extends BTreeLogOperation implements Redoable {

		/**
		 * Pointer to right child.
		 */
		int leftSibling;
		
		/**
		 * Pointer to left child.
		 */
		int rightSibling;
		
		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += TypeSize.INTEGER * 2;
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			rightSibling = bb.getInt();
			leftSibling = bb.getInt();
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putInt(rightSibling);
			bb.putInt(leftSibling);
		}

		@Override
		public final void init() {
		}

		@Override
		public final String toString() {
			return "UnlinkOperation(" + super.toString() + ", leftSibling=" + leftSibling +
				", rightSibling=" + rightSibling + ")";
		}
	
	}
	
	/**
	 * Unlike the published algorithm we simply transfer one key from the more populated
	 * page to the less populated page. 
	 * 
	 * @author Dibyendu Majumdar
	 * @since 06-Oct-2005
	 */
	public static final class RedistributeOperation extends BTreeLogOperation implements Redoable, MultiPageRedo {

		/**
		 * Pointer to the left sibling.
		 */
		int leftSibling;
		
		/**
		 * Pointer to the right sibling.
		 */
		int rightSibling;
		
		/**
		 * The key that will be moved.
		 */
		IndexItem key;
		
		/**
		 * Pointer to the recipient of the key.
		 */
		int targetSibling;
		
		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += TypeSize.INTEGER * 3;
			n += key.getStoredLength();
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			rightSibling = bb.getInt();
			leftSibling = bb.getInt();
			targetSibling = bb.getInt();
			key = makeNewItem(); 
			key.retrieve(bb);
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putInt(rightSibling);
			bb.putInt(leftSibling);
			bb.putInt(targetSibling);
			key.store(bb);
		}

		@Override
		public final void init() {
		}

		public final PageId[] getPageIds() {
			return new PageId[] { getPageId(), new PageId(getPageId().getContainerId(), rightSibling) };
		}
		
		@Override
		public final String toString() {
			return "RedistributeOperation(" + super.toString() + ", leftSibling=" + leftSibling +
				", rightSibling=" + rightSibling + ", targetSibling=" + targetSibling + ")";
		}
		
	}

	/**
	 * Log record for IncreaseTreeHeight operation.
	 * Must be logged as part of the root page update. The log is applied to the
	 * root page and the new child page. It is defined as a Compensation record so that it
	 * can be linked back in such a way that if this operation completes, it is treated as 
	 * a nested top action.
	 * @see BTreeImpl#doIncreaseTreeHeight(Transaction, BTreeCursor)
	 * @see BTreeIndexManagerImpl#redoIncreaseTreeHeightOperation(Page, IncreaseTreeHeightOperation)
	 */
	public static final class IncreaseTreeHeightOperation extends BTreeLogOperation implements Compensation, MultiPageRedo {

		/**
		 * These items that will become part of the new left child page.
		 * Includes the highkey in leaf pages.
		 */
		LinkedList<IndexItem> items;
		
		/**
		 * Root page will contain 2 index entries. First will point to left child,
		 * while the second will point to right child.
		 */
		LinkedList<IndexItem> rootItems;

		/**
		 * New left child page.
		 */
		int leftSibling;

		/**
		 * Right child page.
		 */
		int rightSibling;
		
		/**
		 * Owner of the newly allocated left sibling (left child) page.
		 */
		int spaceMapPageNumber;
		
		@Override
		public final void init() {
			items = new LinkedList<IndexItem>();
			rootItems = new LinkedList<IndexItem>();
		}

		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += TypeSize.SHORT;
			for (IndexItem item: items) {
				n += item.getStoredLength();
			}
			n += TypeSize.SHORT;
			for (IndexItem item: rootItems) {
				n += item.getStoredLength();
			}
			n += TypeSize.INTEGER * 3;
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			short numberOfItems = bb.getShort();
			items = new LinkedList<IndexItem>();
			for (int i = 0; i < numberOfItems; i++) {
				IndexItem item = makeNewItem(); 
				item.retrieve(bb);
				items.add(item);
			}
			numberOfItems = bb.getShort();
			rootItems = new LinkedList<IndexItem>();
			for (int i = 0; i < numberOfItems; i++) {
				IndexItem item = new IndexItem(getKeyFactory().newIndexKey(getPageId().getContainerId()), getLocationFactory().newLocation(), -1, false, isUnique()); 
				item.retrieve(bb);
				rootItems.add(item);
			}
			leftSibling = bb.getInt();
			rightSibling = bb.getInt();
			spaceMapPageNumber = bb.getInt();
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putShort((short) items.size());
			for (IndexItem item: items) {
				item.store(bb);
			}
			bb.putShort((short) rootItems.size());
			for (IndexItem item: rootItems) {
				item.store(bb);
			}
			bb.putInt(leftSibling);
			bb.putInt(rightSibling);
			bb.putInt(spaceMapPageNumber);
		}

		/**
		 * This log record will be applioed to the root page and its newly allocated left
		 * child.
		 */
		public final PageId[] getPageIds() {
			return new PageId[] { getPageId(), new PageId(getPageId().getContainerId(), leftSibling) };
		}
		
		@Override
		public final String toString() {
			return "IncreaseTreeHeightOperation(" + super.toString() + ", leftChild=" + leftSibling +
				", rightChild=" + rightSibling + ")";
		}
	}

	/**
	 * Decrease of the height of the tree when root page has only one child.
	 * Must be logged as part of the root page update.
	 * @see BTreeImpl#doDecreaseTreeHeight(Transaction, BTreeCursor)
	 * @see BTreeIndexManagerImpl#redoDecreaseTreeHeightOperation(Page, DecreaseTreeHeightOperation)
	 */
	public static final class DecreaseTreeHeightOperation extends BTreeLogOperation implements Redoable, MultiPageRedo {

		/**
		 * The items that will become part of the new root page - copied over from child page.
		 * Includes the highkey in leaf pages.
		 */
		LinkedList<IndexItem> items;
		
		/**
		 * Child page to be deallocated
		 */
		int childPageNumber;
		
		/**
		 * Space map page that owns the child page data
		 */
		int childPageSpaceMap;
		
		@Override
		public final void init() {
			items = new LinkedList<IndexItem>();
		}

		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += TypeSize.SHORT;
			for (IndexItem item: items) {
				n += item.getStoredLength();
			}
			n += TypeSize.INTEGER * 2;
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			short numberOfItems = bb.getShort();
			items = new LinkedList<IndexItem>();
			for (int i = 0; i < numberOfItems; i++) {
				IndexItem item = makeNewItem(); 
				item.retrieve(bb);
				items.add(item);
			}
			childPageNumber = bb.getInt();
			childPageSpaceMap = bb.getInt();
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putShort((short) items.size());
			for (IndexItem item: items) {
				item.store(bb);
			}
			bb.putInt(childPageNumber);
			bb.putInt(childPageSpaceMap);
		}

		/**
		 * This log record will be applied to the root page, and the child page that is to be
		 * deallocated. It can also be applied to the space map page, but we handle the
		 * space map page update separately in the interest of high concurrency.
		 */
		public final PageId[] getPageIds() {
//			return new PageId[] { getPageId(), new PageId(getPageId().getContainerId(), childPageNumber),
//					new PageId(getPageId().getContainerId(), childPageSpaceMap) };
			return new PageId[] { getPageId(), new PageId(getPageId().getContainerId(), childPageNumber) };
		}		
		
		@Override
		public final String toString() {
			return "DecreaseTreeHeightOperation(" + super.toString() + ", nItems=" + items.size() +
				", childPage=" + childPageNumber + ")";
		}
	}
	
	/**
	 * IndexItem represents an item within a BTree Page. Both Index pages and
	 * Leaf pages contain IndexItems. However, the content of IndexItem is somewhat
	 * different in Index pages than the content in Leaf pages.
	 * <p>
	 * In Index pages, an item contains a key, and a child page pointer, plus
	 * a location, if the index is non-unique.
	 * <p>
	 * In leaf pages, an item contains a key and a location.
	 * 
	 * @author Dibyendu Majumdar
	 * @since 18-Sep-2005
	 */
	public static final class IndexItem implements Storable, Comparable<IndexItem> {

		/**
		 * Sortable key
		 */
		private IndexKey key;
		
		/**
		 * Location is an optional field; only present in leaf pages and 
		 * in non-unique index pages.
		 */
		private Location location;
		
		/**
		 * Pointer to child node that has keys <= this key. This is an
		 * optional foeld; only present in index pages. 
		 */
		private int childPageNumber;
		
		/**
		 * A non-persistent flag.
		 */
		private boolean isLeaf;
		
		/**
		 * A non-persistent flag.
		 */
		private boolean isUnique;
		
		/**
		 * Location is an optional field; used if the item is part of a 
		 * non-unique index or if the item belongs to a leaf page. 
		 */
		private boolean isLocationRequired() {
			return !isUnique || isLeaf;
		}
		
		public IndexItem(IndexKey key, Location loc, int childPageNumber, boolean isLeaf, boolean isUnique) {
			this.key = key;
			this.location = loc;
			this.childPageNumber = childPageNumber;
			this.isLeaf = isLeaf;
			this.isUnique = isUnique;
		}

		/* (non-Javadoc)
		 * @see org.simpledbm.io.Storable#retrieve(java.nio.ByteBuffer)
		 */
		public final void retrieve(ByteBuffer bb) {
			key.retrieve(bb);
			if (isLocationRequired()) {
				location.retrieve(bb);
			}
			if (!isLeaf) {
				childPageNumber = bb.getInt();
			}
		}

		/* (non-Javadoc)
		 * @see org.simpledbm.io.Storable#store(java.nio.ByteBuffer)
		 */
		public final void store(ByteBuffer bb) {
			key.store(bb);
			if (isLocationRequired()) {
				location.store(bb);
			}
			if (!isLeaf) {
				bb.putInt(childPageNumber);
			}
		}

		/* (non-Javadoc)
		 * @see org.simpledbm.io.Storable#getStoredLength()
		 */
		public final int getStoredLength() {
			int len = key.getStoredLength();
			if (isLocationRequired()) {
				len += location.getStoredLength();
			}
			if (!isLeaf) {
				len += TypeSize.INTEGER;
			}
			return len;
		}

		public final int compareTo(org.simpledbm.rss.impl.im.btree.BTreeIndexManagerImpl.IndexItem o) {
			int comp = key.compareTo(o.key);
			if (comp == 0 && (isLocationRequired())) {
				return location.compareTo(o.location);
			}
			return comp;
		}

		public final int compareToIgnoreLocation(org.simpledbm.rss.impl.im.btree.BTreeIndexManagerImpl.IndexItem o) {
			return key.compareTo(o.key);
		}
		
		public final int getChildPageNumber() {
			return childPageNumber;
		}

		public final void setChildPageNumber(int childPageNumber) {
			this.childPageNumber = childPageNumber;
		}

		public final boolean isLeaf() {
			return isLeaf;
		}

		public final void setLeaf(boolean isLeaf) {
			this.isLeaf = isLeaf;
		}

		public final boolean isUnique() {
			return isUnique;
		}

		public final void setUnique(boolean isUnique) {
			this.isUnique = isUnique;
		}

		public final IndexKey getKey() {
			return key;
		}

		public final void setKey(IndexKey key) {
			this.key = key;
		}

		public final Location getLocation() {
			return location;
		}

		public final void setLocation(Location location) {
			this.location = location;
		}

		@Override
		public int hashCode() {
			final int PRIME = 31;
			int result = 1;
			result = PRIME * result + childPageNumber;
			result = PRIME * result + (isLeaf ? 1231 : 1237);
			result = PRIME * result + (isUnique ? 1231 : 1237);
			result = PRIME * result + ((key == null) ? 0 : key.hashCode());
			result = PRIME * result + ((location == null) ? 0 : location.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			final IndexItem other = (IndexItem) obj;
			if (childPageNumber != other.childPageNumber)
				return false;
			if (isLeaf != other.isLeaf)
				return false;
			if (isUnique != other.isUnique)
				return false;
			if (key == null) {
				if (other.key != null)
					return false;
			} else if (!key.equals(other.key))
				return false;
			if (location == null) {
				if (other.location != null)
					return false;
			} else if (!location.equals(other.location))
				return false;
			return true;
		}

		@Override
		public final String toString() {
			return "IndexItem(key=[" + key + "], isLeaf=" + isLeaf + ", isUnique=" + isUnique + 
				(isLocationRequired() ? (", Location=" + location) : "") + (isLeaf ? "" : (", ChildPage=" + String.valueOf(childPageNumber))) + ")";
		}
	}

	/**
	 * Represents the redo log data for initializing a single BTree node (page) and the corresponding space map page.
	 * @see BTreeIndexManagerImpl#redoLoadPageOperation(Page, LoadPageOperation)
	 * @see XMLLoader
	 */
	public static final class LoadPageOperation extends BTreeLogOperation implements Redoable, MultiPageRedo {

		/**
		 * The IndexItems that will become part of the new page.
		 */
		LinkedList<IndexItem> items;
		
		/**
		 * Pointer to the sibling node to the left.
		 */
		int leftSibling;
		
		/**
		 * Pointer to the sibling node to the right.
		 */
		int rightSibling;
		
		/**
		 * Space Map page that should be updated to reflect the allocated status of the
		 * new page.
		 */
		private int spaceMapPageNumber;
		
		@Override
		public final void init() {
			items = new LinkedList<IndexItem>();
		}

		@Override
		public final int getStoredLength() {
			int n = super.getStoredLength();
			n += TypeSize.SHORT;
			for (IndexItem item: items) {
				n += item.getStoredLength();
			}
			n += TypeSize.INTEGER * 3;
			return n;
		}

		@Override
		public final void retrieve(ByteBuffer bb) {
			super.retrieve(bb);
			short numberOfItems = bb.getShort();
			items = new LinkedList<IndexItem>();
			for (int i = 0; i < numberOfItems; i++) {
				IndexItem item = makeNewItem(); 
				item.retrieve(bb);
				items.add(item);
			}
			leftSibling = bb.getInt();
			rightSibling = bb.getInt();
			setSpaceMapPageNumber(bb.getInt());
		}

		@Override
		public final void store(ByteBuffer bb) {
			super.store(bb);
			bb.putShort((short) items.size());
			for (IndexItem item: items) {
				item.store(bb);
			}
			bb.putInt(leftSibling);
			bb.putInt(rightSibling);
			bb.putInt(getSpaceMapPageNumber());
		}

		public final PageId[] getPageIds() {
			return new PageId[] { getPageId(), new PageId(getPageId().getContainerId(), getSpaceMapPageNumber()) };
		}
		
		void setSpaceMapPageNumber(int spaceMapPageNumber) {
			this.spaceMapPageNumber = spaceMapPageNumber;
		}

		int getSpaceMapPageNumber() {
			return spaceMapPageNumber;
		}

		@Override
		public final String toString() {
			return "LoadPageOperation(" + super.toString() + "leftSibling=" + leftSibling + 
				", rightSibling=" + rightSibling + "spaceMapPageNumber=" + getSpaceMapPageNumber() + 
				", numberOfItems=" + items.size() + ")";  
		}
		
	}
	
	/**
	 * Helper class that reads an XML document and creates LoadPageOperation records using the
	 * data in the document. Primary purspose is to help with testing of the
	 * BTree algorithms by preparing trees with specific characteristics.
	 */
	public static final class XMLLoader {
		
		final BTreeIndexManagerImpl btreemgr;
		
		ArrayList<LoadPageOperation> records = new ArrayList<LoadPageOperation>();

		public XMLLoader(BTreeIndexManagerImpl btreemgr) {
			this.btreemgr = btreemgr;
		}
		
		public final void parseResource(String filename) throws Exception {
			DocumentBuilderFactory factory =
	            DocumentBuilderFactory.newInstance();
	        try {
	           DocumentBuilder builder = factory.newDocumentBuilder();
	           Document document = builder.parse( ClassUtils.getResourceAsStream(filename) );
	           loadDocument(document);
	        } catch (SAXException sxe) {
	           Exception x = sxe;
	           if (sxe.getException() != null)
	               x = sxe.getException();
	           throw x;
	        }			
		}
		
		public final ArrayList<LoadPageOperation> getPageOperations() {
			return records;
		}
		
		private void loadDocument(Document document) throws Exception {
			NodeList nodelist = document.getChildNodes();

			for (int x = 0; x < nodelist.getLength(); x++) {
				Node rootnode = nodelist.item(x);
				if (rootnode.getNodeType() != Node.ELEMENT_NODE || !rootnode.getNodeName().equals("tree")) {
					continue;
				}
				NodeList nodes = rootnode.getChildNodes();

				for (int i = 0; i < nodes.getLength(); i++) {
					Node node = nodes.item(i);
					if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("page")) {
						LoadPageOperation loadPageOp;
						loadPageOp = (LoadPageOperation) btreemgr.loggableFactory.getInstance(BTreeIndexManagerImpl.MODULE_ID, BTreeIndexManagerImpl.TYPE_LOADPAGE_OPERATION);
						loadPage(loadPageOp, node);
						records.add(loadPageOp);
					}
				}
				break;
			}
		}

		private void loadPage(LoadPageOperation loadPageOp, Node page) throws Exception {
			int containerId = -1;
			int pageNumber = -1;
			NamedNodeMap attrs = page.getAttributes();
			if (attrs != null) {
				Node n = attrs.getNamedItem("containerId");
				if (n != null) {
					containerId = Integer.parseInt(n.getTextContent());
				}
				n = attrs.getNamedItem("pageNumber");
				if (n != null) {
					pageNumber = Integer.parseInt(n.getTextContent());
				}
			}
			if (containerId == -1 || pageNumber == -1) {
				throw new Exception("page element must have containerId and pageNumber attributes");
			}
			loadPageOp.setPageId(btreemgr.spMgr.getPageType(), new PageId(containerId, pageNumber));
			NodeList nodes = page.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Node node = nodes.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("header")) {
					loadHeader(loadPageOp, node);
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("items")) {
					NodeList itemsList = node.getChildNodes();
					for (int j = 0; j < itemsList.getLength(); j++) {
						Node itemNode = itemsList.item(j);
						if (itemNode.getNodeType() == Node.ELEMENT_NODE && itemNode.getNodeName().equals("item")) {
							loadItem(loadPageOp, itemNode);
						}
					}
				}
			}
		}
		
		private void loadItem(LoadPageOperation loadPageOp, Node item) throws Exception {
			String keyValue = null;
			int childPageNumber = -1;
			String locationValue = null;
			
			NodeList nodes = item.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Node node = nodes.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("childpagenumber")) {
					childPageNumber = Integer.parseInt(node.getTextContent());
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("location")) {
					locationValue = node.getTextContent();
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("key")) {
					keyValue = node.getTextContent();
				}
			}
			
			IndexKey key = loadPageOp.getNewIndexKey();
			key.parseString(keyValue);
			
			Location location = loadPageOp.getLocationFactory().newLocation();
			location.parseString(locationValue);
			
			loadPageOp.items.add(new IndexItem(key, location, childPageNumber, loadPageOp.isLeaf(), loadPageOp.isUnique()));
		}
		
		private void loadHeader(LoadPageOperation loadPageOp, Node header) throws Exception {
			NodeList nodes = header.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Node node = nodes.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("unique")) {
					String value = node.getTextContent();
					loadPageOp.setUnique(value.equals("yes"));
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("leaf")) {
					String value = node.getTextContent();
					loadPageOp.setLeaf(value.equals("yes"));
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("leftsibling")) {
					String value = node.getTextContent();
					loadPageOp.leftSibling = Integer.parseInt(value);
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("rightsibling")) {
					String value = node.getTextContent();
					loadPageOp.rightSibling = Integer.parseInt(value);
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("smppagenumber")) {
					String value = node.getTextContent();
					loadPageOp.setSpaceMapPageNumber(Integer.parseInt(value));
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("locationfactory")) {
					String value = node.getTextContent();
					loadPageOp.setLocationFactoryType(Short.parseShort(value));
				}
				else if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("keyfactory")) {
					String value = node.getTextContent();
					loadPageOp.setKeyFactoryType(Short.parseShort(value));
				}
			}
		}
	}
}