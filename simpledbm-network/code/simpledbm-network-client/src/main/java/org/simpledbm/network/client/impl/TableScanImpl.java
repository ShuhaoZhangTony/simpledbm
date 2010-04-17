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
 *    Linking this library statically or dynamically with other modules 
 *    is making a combined work based on this library. Thus, the terms and
 *    conditions of the GNU General Public License cover the whole
 *    combination.
 *
 *    As a special exception, the copyright holders of this library give 
 *    you permission to link this library with independent modules to 
 *    produce an executable, regardless of the license terms of these 
 *    independent modules, and to copy and distribute the resulting 
 *    executable under terms of your choice, provided that you also meet, 
 *    for each linked independent module, the terms and conditions of the 
 *    license of that module.  An independent module is a module which 
 *    is not derived from or based on this library.  If you modify this 
 *    library, you may extend this exception to your version of the 
 *    library, but you are not obligated to do so.  If you do not wish 
 *    to do so, delete this exception statement from your version.
 *
 *    Project: www.simpledbm.org
 *    Author : Dibyendu Majumdar
 *    Email  : d dot majumdar at gmail dot com ignore
 */
package org.simpledbm.network.client.impl;

import org.simpledbm.network.client.api.Session;
import org.simpledbm.network.client.api.TableScan;
import org.simpledbm.network.common.api.CloseScanMessage;
import org.simpledbm.network.common.api.DeleteRowMessage;
import org.simpledbm.network.common.api.FetchNextRowMessage;
import org.simpledbm.network.common.api.FetchNextRowReply;
import org.simpledbm.network.common.api.OpenScanMessage;
import org.simpledbm.network.common.api.RequestCode;
import org.simpledbm.network.common.api.UpdateRowMessage;
import org.simpledbm.network.nio.api.Response;
import org.simpledbm.typesystem.api.Row;
import org.simpledbm.typesystem.api.TableDefinition;

public class TableScanImpl implements TableScan {

    private final SessionImpl session;
    final TableDefinition tableDefinition;

    /**
     * Index to use for the scan.
     */
    final int indexNo;

    /**
     * Initial search row, may be null.
     */
    final Row startRow;

    /**
     * Was the scan opened for update?
     */
    final boolean forUpdate;

    /**
     * Handle for the scan.
     */
    int scanId;

    /**
     * The current row as returned by fetchNext()
     */
    Row currentRow;

    /**
     * Have we reached eof?
     */
    boolean eof;

    public TableScanImpl(SessionImpl session, TableDefinition tableDefinition,
            int indexNo, Row startRow, boolean forUpdate) {
        super();
        this.session = session;
        this.tableDefinition = tableDefinition;
        this.indexNo = indexNo;
        this.startRow = startRow;
        this.forUpdate = forUpdate;
        eof = false;
        this.scanId = open();
    }

    /**
     * Opens the scan, preparing for data to be fetched.
     * 
     * @return
     */
    int open() {
        OpenScanMessage message = new OpenScanMessage(tableDefinition
                .getContainerId(), indexNo, startRow, forUpdate);
        Response response = session.sendMessage(RequestCode.OPEN_TABLESCAN,
                message);
        int scanNo = response.getData().getInt();
        //        System.err.println("Scan id = " + scanNo);
        return scanNo;
    }

    public Row fetchNext() {
        if (eof) {
            return null;
        }
        FetchNextRowMessage message = new FetchNextRowMessage(scanId);
        Response response = session.sendMessage(RequestCode.FETCH_NEXT_ROW,
                message);
        FetchNextRowReply reply = new FetchNextRowReply(getSession()
                .getSessionManager().getRowFactory(), response.getData());
        if (reply.isEof()) {
            eof = true;
            return null;
        }
        //        System.err.println("Scan row = " + reply.getRow());
        return reply.getRow();
    }

    public void updateCurrentRow(Row tableRow) {
        if (eof) {
            throw new RuntimeException("Scan has reached EOF");
        }
        UpdateRowMessage message = new UpdateRowMessage(scanId, tableDefinition.getContainerId(), tableRow);
        session.sendMessage(RequestCode.UPDATE_CURRENT_ROW, message);
    }

    public void deleteRow() {
        if (eof) {
            throw new RuntimeException("Scan has reached EOF");
        }
        DeleteRowMessage message = new DeleteRowMessage(scanId);
        session.sendMessage(RequestCode.DELETE_CURRENT_ROW, message);
    }

    public void close() {
        CloseScanMessage message = new CloseScanMessage(scanId);
        session.sendMessage(RequestCode.CLOSE_TABLESCAN, message);
    }

    public Session getSession() {
        return session;
    }
}
