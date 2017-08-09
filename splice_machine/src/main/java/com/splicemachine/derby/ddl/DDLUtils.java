/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.derby.ddl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.carrotsearch.hppc.BitSet;
import com.splicemachine.db.iapi.services.loader.ClassFactory;
import com.splicemachine.db.iapi.sql.dictionary.*;
import com.splicemachine.si.api.txn.IsolationLevel;
import com.splicemachine.storage.RecordScan;
import org.apache.log4j.Logger;

import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.concurrent.Clock;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.io.FormatableBitSet;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.depend.DependencyManager;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.impl.services.uuid.BasicUUID;
import com.splicemachine.db.impl.sql.compile.ColumnDefinitionNode;
import com.splicemachine.db.impl.sql.execute.ColumnInfo;
import com.splicemachine.ddl.DDLMessage;
import com.splicemachine.derby.DerbyMessage;
import com.splicemachine.derby.impl.sql.execute.actions.DropAliasConstantOperation;
import com.splicemachine.derby.impl.store.access.SpliceTransactionManager;
import com.splicemachine.derby.jdbc.SpliceTransactionResourceImpl;
import com.splicemachine.pipeline.ErrorState;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.protobuf.ProtoUtil;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnLifecycleManager;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.stream.Stream;
import com.splicemachine.stream.StreamException;
import com.splicemachine.utils.SpliceLogUtils;

/**
 * Created by jleach on 11/12/15.
 *
 */
public class DDLUtils {
    private static final Logger LOG = Logger.getLogger(DDLUtils.class);

    public static DDLMessage.DDLChange performMetadataChange(DDLMessage.DDLChange ddlChange) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"performMetadataChange ddlChange=%s",ddlChange);
        notifyMetadataChangeAndWait(ddlChange);
        return ddlChange;
    }

    public static String notifyMetadataChange(DDLMessage.DDLChange ddlChange) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"notifyMetadataChange ddlChange=%s",ddlChange);
        return DDLDriver.driver().ddlController().notifyMetadataChange(ddlChange);
    }

    public static void finishMetadataChange(String changeId) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"finishMetadataChange changeId=%s",changeId);
        DDLDriver.driver().ddlController().finishMetadataChange(changeId);
    }


    public static void notifyMetadataChangeAndWait(DDLMessage.DDLChange ddlChange) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"notifyMetadataChangeAndWait ddlChange=%s",ddlChange);
        String changeId = notifyMetadataChange(ddlChange);
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"notifyMetadataChangeAndWait changeId=%s",changeId);
        DDLDriver.driver().ddlController().finishMetadataChange(changeId);
    }

    public static Txn getLazyTransaction(long txnId) throws StandardException {
        try {
            return SIDriver.driver().getTxnStore().getTransaction(txnId);
        } catch (IOException ioe) {
            throw StandardException.plainWrapException(ioe);
        }
    }

    public static String outIntArray(int[] values) {
        return values==null?"null":Arrays.toString(values);
    }

    public static String outBoolArray(boolean[] values) {
        return values==null?"null":Arrays.toString(values);
    }



    public static Txn getIndexTransaction(TransactionController tc, Txn tentativeTransaction, long tableConglomId, String indexName) throws StandardException {
        final Txn wrapperTxn = ((SpliceTransactionManager)tc).getActiveStateTxn();
        throw new UnsupportedOperationException("Implement");
        /*
         * We have an additional waiting transaction that we use to ensure that all elements
         * which commit after the demarcation point are committed BEFORE the populate part.
         */
        /*
        byte[] tableBytes = Bytes.toBytes(Long.toString(tableConglomId));
        TxnLifecycleManager tlm = SIDriver.driver().lifecycleManager();
        Txn waitTxn;
        try{
            waitTxn = tlm.chainTransaction(wrapperTxn,tentativeTransaction);
        }catch(IOException ioe){
            LOG.error("Could not create a wait transaction",ioe);
            throw Exceptions.parseException(ioe);
        }

        //get the absolute user transaction
        Txn uTxn = wrapperTxn;
        Txn n = uTxn.getParentTxnView();
        while(n.getTxnId()>=0){
            uTxn = n;
            n = n.getParentTxnView();
        }
        // Wait for past transactions to die
        long oldestActiveTxn;
        try {
            oldestActiveTxn = waitForConcurrentTransactions(waitTxn, uTxn,tableConglomId);
        } catch (IOException e) {
            LOG.error("Unexpected error while waiting for past transactions to complete", e);
            throw Exceptions.parseException(e);
        }
        if (oldestActiveTxn>=0) {
            throw ErrorState.DDL_ACTIVE_TRANSACTIONS.newException("CreateIndex("+indexName+")",oldestActiveTxn);
        }
        Txn indexTxn;
        try{
            /*
             * We need to make the indexTxn a child of the wrapper, so that we can be sure
             * that the write pipeline is able to see the conglomerate descriptor. However,
             * this makes the SI logic more complex during the populate phase.
             */

        /*
            indexTxn = tlm.chainTransaction(wrapperTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION, true, tableBytes,waitTxn);
        } catch (IOException e) {
            LOG.error("Couldn't commit transaction for tentative DDL operation");
            // TODO must cleanup tentative DDL change
            throw Exceptions.parseException(e);
        }
        return indexTxn;
        */
    }

    /**
     * Make sure that the table exists and that it isn't a system table. Otherwise, KA-BOOM
     */
    public static  void validateTableDescriptor(TableDescriptor td,String indexName, String tableName) throws StandardException {
        if (td == null)
            throw StandardException.newException(SQLState.LANG_CREATE_INDEX_NO_TABLE, indexName, tableName);
        if (td.getTableType() == TableDescriptor.SYSTEM_TABLE_TYPE)
            throw StandardException.newException(SQLState.LANG_CREATE_SYSTEM_INDEX_ATTEMPTED, indexName, tableName);
    }

    /**
     *
     * Create a table scan for old conglomerate. Make sure to create a NonSI table scan. Txn filtering
     * will happen at client side
     * @return
     */
    public static RecordScan createFullScan() {
        RecordScan scan = SIDriver.driver().getOperationFactory().newDataScan();
        scan.startKey(SIConstants.EMPTY_BYTE_ARRAY).stopKey(SIConstants.EMPTY_BYTE_ARRAY);
        return scan;
    }

    public static int[] getMainColToIndexPosMap(int[] indexColsToMainColMap, BitSet indexedCols) {
        int[] mainColToIndexPosMap = new int[(int) indexedCols.length()];
        for (int i = 0 ; i < indexedCols.length(); ++i) {
            mainColToIndexPosMap[i] = -1;
        }
        for (int indexCol = 0; indexCol < indexColsToMainColMap.length; indexCol++) {
            int mainCol = indexColsToMainColMap[indexCol];
            mainColToIndexPosMap[mainCol - 1] = indexCol;
        }
        return mainColToIndexPosMap;
    }

    public static BitSet getIndexedCols(int[] indexColsToMainColMap) {
        BitSet indexedCols = new BitSet();
        for (int indexCol : indexColsToMainColMap) {
            indexedCols.set(indexCol - 1);
        }
        return indexedCols;
    }

    public static byte[] getIndexConglomBytes(long indexConglomerate) {
        return Bytes.toBytes(Long.toString(indexConglomerate));
    }

    public static byte[] serializeColumnInfoArray(ColumnInfo[] columnInfos) throws StandardException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeInt(columnInfos.length);
            for (int i =0; i< columnInfos.length;i++) {
                oos.writeObject(columnInfos[i]);
            }
            oos.flush();
            oos.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static ColumnInfo[] deserializeColumnInfoArray(byte[] bytes) {
        ObjectInputStream oos = null;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            ObjectInputStream is = new ObjectInputStream(bis);
            ColumnInfo[] columnInfos = new ColumnInfo[is.readInt()];
            for (int i =0; i< columnInfos.length;i++) {
                columnInfos[i] = (ColumnInfo) is.readObject();
            }
            is.close();
            return columnInfos;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     *
     * Prepare all dependents to invalidate.  (There is a chance
     * to say that they can't be invalidated.  For example, an open
     * cursor referencing a table/view that the user is attempting to
     * drop.) If no one objects, then invalidate any dependent objects.
     * We check for invalidation before we drop the table descriptor
     * since the table descriptor may be looked up as part of
     * decoding tuples in SYSDEPENDS.
     *
     *
     * @param change
     * @param dd
     * @param dm
     * @throws StandardException
     */
    public static void preDropTable(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropTable with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared=transactionResource.marshallTransaction(txn);
                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(change.getDropTable().getTableId()));
                if(td==null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td,DependencyManager.DROP_TABLE,transactionResource.getLcc());
            }finally{
               if(prepared)
                   transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preAlterStats(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preAlterStats with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared=transactionResource.marshallTransaction(txn);
                List<DerbyMessage.UUID> tdUIDs=change.getAlterStats().getTableIdList();
                for(DerbyMessage.UUID uuuid : tdUIDs){
                    TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid));
                    if(td==null) // Table Descriptor transaction never committed
                        return;
                    dm.invalidateFor(td,DependencyManager.DROP_STATISTICS,transactionResource.getLcc());
                }
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preDropSchema(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropSchema with change=%s",change);
        dd.getDataDictionaryCache().schemaCacheRemove(change.getDropSchema().getSchemaName());
    }

    public static void preCreateIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        preIndex(change, dd, dm, DependencyManager.CREATE_INDEX, change.getTentativeIndex().getTable().getTableUuid());
    }

    public static void preDropIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropIndex with change=%s",change);
        boolean prepared = false;
        SpliceTransactionResourceImpl transactionResource = null;
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            transactionResource = new SpliceTransactionResourceImpl();
            //transactionResource.prepareContextManager();
            prepared = transactionResource.marshallTransaction(txn);
            TransactionController tc = transactionResource.getLcc().getTransactionExecute();
            DDLMessage.DropIndex dropIndex =  change.getDropIndex();
            SchemaDescriptor sd = dd.getSchemaDescriptor(dropIndex.getSchemaName(),tc,true);
            TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(dropIndex.getTableUUID()));
            ConglomerateDescriptor cd = dd.getConglomerateDescriptor(dropIndex.getIndexName(), sd, true);
            if (td!=null) { // Table Descriptor transaction never committed
                dm.invalidateFor(td, DependencyManager.ALTER_TABLE, transactionResource.getLcc());
            }
            if (cd!=null) {
                dm.invalidateFor(cd, DependencyManager.DROP_INDEX, transactionResource.getLcc());
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        } finally {
            if (prepared)
                transactionResource.close();
        }
    }

    private static void preIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, int action, DerbyMessage.UUID uuid) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preIndex with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean initializedTxn = false;
            try {
                initializedTxn = transactionResource.marshallTransaction(txn);
                TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuid));
                if (td==null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td, action, transactionResource.getLcc());
            } finally {
                if (initializedTxn)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preRenameTable(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRenameTable with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared = transactionResource.marshallTransaction(txn);
                DerbyMessage.UUID uuuid=change.getRenameTable().getTableId();
                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid));
                if(td==null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td,DependencyManager.RENAME,transactionResource.getLcc());
    		/* look for foreign key dependency on the table. If found any,
	    	use dependency manager to pass the rename action to the
		    dependents. */
                ConstraintDescriptorList constraintDescriptorList=dd.getConstraintDescriptors(td);
                for(int index=0;index<constraintDescriptorList.size();index++){
                    ConstraintDescriptor constraintDescriptor=constraintDescriptorList.elementAt(index);
                    if(constraintDescriptor instanceof ReferencedKeyConstraintDescriptor)
                        dm.invalidateFor(constraintDescriptor,DependencyManager.RENAME,transactionResource.getLcc());
                }
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preRenameColumn(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRenameColumn with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared=transactionResource.marshallTransaction(txn);
                DerbyMessage.UUID uuuid=change.getRenameColumn().getTableId();
                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid));
                if(td==null) // Table Descriptor transaction never committed
                    return;
                ColumnDescriptor columnDescriptor=td.getColumnDescriptor(change.getRenameColumn().getColumnName());
                if(columnDescriptor.isAutoincrement())
                    columnDescriptor.setAutoinc_create_or_modify_Start_Increment(
                            ColumnDefinitionNode.CREATE_AUTOINCREMENT);

                int columnPosition=columnDescriptor.getPosition();
                FormatableBitSet toRename=new FormatableBitSet(td.getColumnDescriptorList().size()+1);
                toRename.set(columnPosition);
                td.setReferencedColumnMap(toRename);
                dm.invalidateFor(td,DependencyManager.RENAME,transactionResource.getLcc());

                //look for foreign key dependency on the column.
                ConstraintDescriptorList constraintDescriptorList=dd.getConstraintDescriptors(td);
                for(int index=0;index<constraintDescriptorList.size();index++){
                    ConstraintDescriptor constraintDescriptor=constraintDescriptorList.elementAt(index);
                    int[] referencedColumns=constraintDescriptor.getReferencedColumns();
                    int numRefCols=referencedColumns.length;
                    for(int j=0;j<numRefCols;j++){
                        if((referencedColumns[j]==columnPosition) &&
                                (constraintDescriptor instanceof ReferencedKeyConstraintDescriptor))
                            dm.invalidateFor(constraintDescriptor,DependencyManager.RENAME,transactionResource.getLcc());
                    }
                }
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preRenameIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRenameIndex with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared=transactionResource.marshallTransaction(txn);
                DerbyMessage.UUID uuuid=change.getRenameIndex().getTableId();
                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid));
                if(td==null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td,DependencyManager.RENAME_INDEX,transactionResource.getLcc());
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preDropAlias(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropAlias with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared=transactionResource.marshallTransaction(txn);
                DDLMessage.DropAlias dropAlias=change.getDropAlias();
                AliasDescriptor ad=dd.getAliasDescriptor(dropAlias.getSchemaName(),dropAlias.getAliasName(),dropAlias.getNamespace().charAt(0));
                if(ad==null) // Table Descriptor transaction never committed
                    return;
                DropAliasConstantOperation.invalidate(ad,dm,transactionResource.getLcc());
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preNotifyJarLoader(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preNotifyJarLoader with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared = transactionResource.marshallTransaction(txn);
                dd.invalidateAllSPSPlans(); // This will break other nodes, must do ddl
                ClassFactory cf = transactionResource.getLcc().getLanguageConnectionFactory().getClassFactory();
                cf.notifyModifyJar(change.getNotifyJarLoader().getReload());
                if (change.getNotifyJarLoader().getDrop()) {
                    SchemaDescriptor sd = dd.getSchemaDescriptor(change.getNotifyJarLoader().getSchemaName(), null, true);
                    if (sd ==null)
                        return;
                    FileInfoDescriptor fid = dd.getFileInfoDescriptor(sd,change.getNotifyJarLoader().getSqlName());
                    if (fid==null)
                        return;
                    dm.invalidateFor(fid, DependencyManager.DROP_JAR, transactionResource.getLcc());

                }
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void postNotifyJarLoader(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preNotifyJarLoader with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared = transactionResource.marshallTransaction(txn);
                ClassFactory cf = transactionResource.getLcc().getLanguageConnectionFactory().getClassFactory();
                cf.notifyModifyJar(true);
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }

    }

    public static void preNotifyModifyClasspath(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropView with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared = transactionResource.marshallTransaction(txn);
                transactionResource.getLcc().getLanguageConnectionFactory().getClassFactory().notifyModifyClasspath(change.getNotifyModifyClasspath().getClasspath());
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }



    public static void preDropView(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropView with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared = transactionResource.marshallTransaction(txn);
                DDLMessage.DropView dropView=change.getDropView();

                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(dropView.getTableId()));
                if(td==null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td,DependencyManager.DROP_VIEW,transactionResource.getLcc());
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preDropSequence(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropSequence with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared = transactionResource.marshallTransaction(txn);
                DDLMessage.DropSequence dropSequence=change.getDropSequence();
                TransactionController tc = transactionResource.getLcc().getTransactionExecute();
                SchemaDescriptor sd = dd.getSchemaDescriptor(dropSequence.getSchemaName(),tc,true);
                if(sd==null) // Table Descriptor transaction never committed
                    return;
                SequenceDescriptor seqDesc = dd.getSequenceDescriptor(sd,dropSequence.getSequenceName());
                if (seqDesc==null)
                    return;
                dm.invalidateFor(seqDesc, DependencyManager.DROP_SEQUENCE, transactionResource.getLcc());
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }



    public static void preCreateTrigger(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preCreateTrigger with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared=transactionResource.marshallTransaction(txn);
                DerbyMessage.UUID uuuid=change.getCreateTrigger().getTableId();
                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid));
                if(td==null)
                    return;
                dm.invalidateFor(td,DependencyManager.CREATE_TRIGGER,transactionResource.getLcc());
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preDropTrigger(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropTrigger with change=%s",change);
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl();
            boolean prepared = false;
            try{
                prepared = transactionResource.marshallTransaction(txn);
                DerbyMessage.UUID tableuuid=change.getDropTrigger().getTableId();
                DerbyMessage.UUID triggeruuid=change.getDropTrigger().getTriggerId();
                SPSDescriptor spsd=dd.getSPSDescriptor(ProtoUtil.getDerbyUUID(change.getDropTrigger().getSpsDescriptorUUID()));

                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(tableuuid));
                TriggerDescriptor triggerDescriptor=dd.getTriggerDescriptor(ProtoUtil.getDerbyUUID(triggeruuid));
                if(td!=null)
                    dm.invalidateFor(td,DependencyManager.DROP_TRIGGER,transactionResource.getLcc());
                if(triggerDescriptor!=null){
                    dm.invalidateFor(triggerDescriptor,DependencyManager.DROP_TRIGGER,transactionResource.getLcc());
//                dm.clearDependencies(transactionResource.getLcc(), triggerDescriptor);
                    if(triggerDescriptor.getWhenClauseId()!=null){
                        SPSDescriptor whereDescriptor=dd.getSPSDescriptor(triggerDescriptor.getWhenClauseId());
                        if(whereDescriptor!=null){
                            dm.invalidateFor(whereDescriptor,DependencyManager.DROP_TRIGGER,transactionResource.getLcc());
//                        dm.clearDependencies(transactionResource.getLcc(), whereDescriptor);
                        }
                    }
                }
                if(spsd!=null){
                    dm.invalidateFor(spsd,DependencyManager.DROP_TRIGGER,transactionResource.getLcc());
                    //               dm.clearDependencies(transactionResource.getLcc(), spsd);
                }
                // Remove all TECs from trigger stack. They will need to be rebuilt.
                transactionResource.getLcc().popAllTriggerExecutionContexts();
            }finally{
                if(prepared)
                    transactionResource.close();
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }



    public static void preAlterTable(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preAlterTable with change=%s",change);
        boolean prepared = false;
        SpliceTransactionResourceImpl transactionResource = null;
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            transactionResource = new SpliceTransactionResourceImpl();
            prepared = transactionResource.marshallTransaction(txn);
            for (DerbyMessage.UUID uuid : change.getAlterTable().getTableIdList()) {
                TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuid));
                if (td==null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td, DependencyManager.ALTER_TABLE, transactionResource.getLcc());
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        } finally {
            if (prepared)
                transactionResource.close();
        }
    }

    public static void preDropRole(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropRole with change=%s",change);
        SpliceTransactionResourceImpl transactionResource = null;
        boolean prepared = false;
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            transactionResource = new SpliceTransactionResourceImpl();
            prepared = transactionResource.marshallTransaction(txn);
            String roleName = change.getDropRole().getRoleName();
            RoleClosureIterator rci =
                dd.createRoleClosureIterator
                    (transactionResource.getLcc().getTransactionCompile(),
                     roleName, false);

            String role;
            while ((role = rci.next()) != null) {
                RoleGrantDescriptor r = dd.getRoleDefinitionDescriptor(role);
                if (r!=null) {
                    dm.invalidateFor(r, DependencyManager.REVOKE_ROLE, transactionResource.getLcc());
                }
            }

            dd.getDataDictionaryCache().roleCacheRemove(change.getDropRole().getRoleName());
        } catch (Exception e) {
            e.printStackTrace();
            throw StandardException.plainWrapException(e);
        } finally {
            if (prepared) {
                transactionResource.close();
            }
        }
    }

    public static void preTruncateTable(DDLMessage.DDLChange change,
                                        DataDictionary dd,
                                        DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preTruncateTable with change=%s",change);
        boolean prepared = false;
        SpliceTransactionResourceImpl transactionResource = null;
        try {
            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            transactionResource = new SpliceTransactionResourceImpl();
            //transactionResource.prepareContextManager();
            prepared = transactionResource.marshallTransaction(txn);
            BasicUUID uuid = ProtoUtil.getDerbyUUID(change.getTruncateTable().getTableId());
            TableDescriptor td = dd.getTableDescriptor(uuid);
            dm.invalidateFor(td, DependencyManager.TRUNCATE_TABLE, transactionResource.getLcc());
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        } finally {
            if (prepared)
                transactionResource.close();
        }
    }

    public static void preRevokePrivilege(DDLMessage.DDLChange change,
                                          DataDictionary dd,
                                          DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRevokePrivilege with change=%s",change);
        boolean prepared = false;
        SpliceTransactionResourceImpl transactionResource = null;
        try {
            DDLMessage.RevokePrivilege revokePrivilege = change.getRevokePrivilege();
            DDLMessage.RevokePrivilege.Type type = revokePrivilege.getType();

            Txn txn = DDLUtils.getLazyTransaction(change.getTxnId());
            transactionResource = new SpliceTransactionResourceImpl();
            //transactionResource.prepareContextManager();
            prepared = transactionResource.marshallTransaction(txn);
            LanguageConnectionContext lcc = transactionResource.getLcc();
            if (type == DDLMessage.RevokePrivilege.Type.REVOKE_TABLE_PRIVILEGE) {
                preRevokeTablePrivilege(revokePrivilege.getRevokeTablePrivilege(), dd, dm, lcc);
            }
            else if (type == DDLMessage.RevokePrivilege.Type.REVOKE_COLUMN_PRIVILEGE) {
                preRevokeColumnPrivilege(revokePrivilege.getRevokeColumnPrivilege(), dd, dm, lcc);
            }
            else if (type == DDLMessage.RevokePrivilege.Type.REVOKE_ROUTINE_PRIVILEGE) {
                preRevokeRoutinePrivilege(revokePrivilege.getRevokeRoutinePrivilege(), dd, dm, lcc);
            }
            else if (type == DDLMessage.RevokePrivilege.Type.REVOKE_GENERIC_PRIVILEGE) {
                preRevokeGenericPrivilege(revokePrivilege.getRevokeGenericPrivilege(), dd, dm, lcc);
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        } finally {
            if (prepared)
                transactionResource.close();
        }
    }

    private static void preRevokeTablePrivilege(DDLMessage.RevokeTablePrivilege revokeTablePrivilege,
                                                DataDictionary dd,
                                                DependencyManager dm,
                                                LanguageConnectionContext lcc) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeTablePrivilege.getTableId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeTablePrivilege.getPermObjectId());
        TableDescriptor td = dd.getTableDescriptor(uuid);

        TablePermsDescriptor tablePermsDesc =
            new TablePermsDescriptor(
                dd,
                revokeTablePrivilege.getGrantee(),
                revokeTablePrivilege.getGrantor(),
                uuid,
                revokeTablePrivilege.getSelectPerm(),
                revokeTablePrivilege.getDeletePerm(),
                revokeTablePrivilege.getInsertPerm(),
                revokeTablePrivilege.getUpdatePerm(),
                revokeTablePrivilege.getReferencesPerm(),
                revokeTablePrivilege.getTriggerPerm());
        tablePermsDesc.setUUID(objectId);
        dd.getDataDictionaryCache().permissionCacheRemove(tablePermsDesc);
        dm.invalidateFor(tablePermsDesc, DependencyManager.REVOKE_PRIVILEGE, lcc);
        dm.invalidateFor(td, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
    }

    private static void preRevokeColumnPrivilege(DDLMessage.RevokeColumnPrivilege revokeColumnPrivilege,
                                                 DataDictionary dd,
                                                 DependencyManager dm,
                                                 LanguageConnectionContext lcc) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeColumnPrivilege.getTableId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeColumnPrivilege.getPermObjectId());
        TableDescriptor td = dd.getTableDescriptor(uuid);
        ColPermsDescriptor colPermsDescriptor =
            new ColPermsDescriptor(
                dd,
                revokeColumnPrivilege.getGrantee(),
                revokeColumnPrivilege.getGrantor(),
                uuid,
                revokeColumnPrivilege.getType(),
                new FormatableBitSet(revokeColumnPrivilege.getColumns().toByteArray()));
        colPermsDescriptor.setUUID(objectId);
        dd.getDataDictionaryCache().permissionCacheRemove(colPermsDescriptor);
        dm.invalidateFor(colPermsDescriptor, DependencyManager.REVOKE_PRIVILEGE, lcc);
        dm.invalidateFor(td, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
    }

    private static void preRevokeRoutinePrivilege(DDLMessage.RevokeRoutinePrivilege revokeRoutinePrivilege,
                                                  DataDictionary dd,
                                                  DependencyManager dm,
                                                  LanguageConnectionContext lcc) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeRoutinePrivilege.getRountineId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeRoutinePrivilege.getPermObjectId());
        RoutinePermsDescriptor routinePermsDescriptor =
            new RoutinePermsDescriptor(
                dd,
                revokeRoutinePrivilege.getGrantee(),
                revokeRoutinePrivilege.getGrantor(),
                uuid);
        routinePermsDescriptor.setUUID(objectId);

        dd.getDataDictionaryCache().permissionCacheRemove(routinePermsDescriptor);
        dm.invalidateFor(routinePermsDescriptor, DependencyManager.REVOKE_PRIVILEGE_RESTRICT, lcc);

        AliasDescriptor aliasDescriptor = dd.getAliasDescriptor(objectId);
        if (aliasDescriptor != null) {
            dm.invalidateFor(aliasDescriptor, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
        }
    }

    private static void preRevokeGenericPrivilege(DDLMessage.RevokeGenericPrivilege revokeGenericPrivilege,
                                                  DataDictionary dd,
                                                  DependencyManager dm,
                                                  LanguageConnectionContext lcc) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeGenericPrivilege.getId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeGenericPrivilege.getPermObjectId());
        PermDescriptor permDescriptor =
            new PermDescriptor(
                dd,
                uuid,
                revokeGenericPrivilege.getObjectType(),
                objectId,
                revokeGenericPrivilege.getPermission(),
                revokeGenericPrivilege.getGrantor(),
                revokeGenericPrivilege.getGrantee(),
                revokeGenericPrivilege.getGrantable());
        int invalidationType = revokeGenericPrivilege.getRestrict() ?
            DependencyManager.REVOKE_PRIVILEGE_RESTRICT : DependencyManager.REVOKE_PRIVILEGE;

        dd.getDataDictionaryCache().permissionCacheRemove(permDescriptor);
        dm.invalidateFor(permDescriptor, invalidationType, lcc);

        PrivilegedSQLObject privilegedSQLObject = null;
        if (revokeGenericPrivilege.getObjectType().compareToIgnoreCase("SEQUENCE") == 0) {
            privilegedSQLObject = dd.getSequenceDescriptor(objectId);
        }
        else {
            privilegedSQLObject = dd.getAliasDescriptor(objectId);
        }
        if (privilegedSQLObject != null) {
            dm.invalidateFor(privilegedSQLObject, invalidationType, lcc);
        }
    }
}
