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

package com.splicemachine.derby.utils;

import com.splicemachine.pipeline.ErrorState;
import org.spark_project.guava.collect.Lists;
import com.splicemachine.db.iapi.error.PublicAPI;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.SQLLongint;
import com.splicemachine.db.impl.jdbc.EmbedConnection;
import com.splicemachine.db.impl.jdbc.EmbedResultSet40;
import com.splicemachine.db.impl.sql.GenericColumnDescriptor;
import com.splicemachine.db.impl.sql.execute.IteratorNoPutResultSet;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.impl.store.access.SpliceTransactionManager;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnLifecycleManager;
import com.splicemachine.si.api.txn.TxnSupplier;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.stream.Stream;
import com.splicemachine.stream.StreamException;
import com.splicemachine.utils.ByteSlice;

import javax.ws.rs.NotSupportedException;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * @author Scott Fines
 *         Date: 2/20/14
 */
public class TransactionAdmin{

    public static void killAllActiveTransactions(long maxTxnId) throws SQLException{
        throw new UnsupportedOperationException("not implemented");
        /*ActiveTransactionReader reader=new ActiveTransactionReader(0l,maxTxnId,null);
        try(Stream<Txn> activeTransactions=reader.getActiveTransactions()){
            final TxnLifecycleManager tc=SIDriver.driver().lifecycleManager();
            Txn next;
            while((next=activeTransactions.next())!=null){
                tc.rollback(next.getTxnId());
            }
        }catch(StreamException|IOException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
        */
    }

    public static void killTransaction(long txnId) throws SQLException{
        try{
            TxnSupplier store=SIDriver.driver().getTxnStore();
            Txn txn=store.getTransaction(txnId);
            //if the transaction is read-only, or doesn't exist, then don't do anything to it
            if(txn==null) return;

            TxnLifecycleManager tc=SIDriver.driver().lifecycleManager();
            tc.rollback(txn);
        }catch(IOException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    private static final ResultColumnDescriptor[] CURRENT_TXN_ID_COLUMNS=new GenericColumnDescriptor[]{
            new GenericColumnDescriptor("txnId",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT))
    };

    public static void SYSCS_GET_CURRENT_TRANSACTION(ResultSet[] resultSet) throws SQLException{
        EmbedConnection defaultConn=(EmbedConnection)SpliceAdmin.getDefaultConn();
        Txn txn=((SpliceTransactionManager)defaultConn.getLanguageConnection().getTransactionExecute()).getActiveStateTxn();
        ExecRow row=new ValueRow(1);
        row.setColumn(1,new SQLLongint(txn.getTxnId()));
        Activation lastActivation=defaultConn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet rs=new IteratorNoPutResultSet(Collections.singletonList(row),
                CURRENT_TXN_ID_COLUMNS,
                lastActivation);
        try{
            rs.openCore();
        }catch(StandardException e){
            throw PublicAPI.wrapStandardException(e);
        }
        resultSet[0]=new EmbedResultSet40(defaultConn,rs,false,null,true);
    }

    public static void SYSCS_GET_ACTIVE_TRANSACTION_IDS(ResultSet[] resultSets) throws SQLException{
       throw new NotSupportedException("Not implemented");
    }


    private static final ResultColumnDescriptor[] TRANSACTION_TABLE_COLUMNS=new GenericColumnDescriptor[]{
            new GenericColumnDescriptor("txnId",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("parentTxnId",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("modifiedConglomerate",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("status",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("isolationLevel",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("beginTimestamp",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("commitTimestamp",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("effectiveCommitTimestamp",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("isAdditive",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BOOLEAN)),
            new GenericColumnDescriptor("lastKeepAlive",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIMESTAMP))
    };

    public static void SYSCS_DUMP_TRANSACTIONS(ResultSet[] resultSet) throws SQLException{
        throw PublicAPI.wrapStandardException(ErrorState.SPLICE_OPERATION_UNSUPPORTED.newException("CALL SYSCS_DUMP_TRANSACTIONS"));
//        ActiveTransactionReader reader=new ActiveTransactionReader(0l,Long.MAX_VALUE,null);
//        try{
//            ExecRow template=toRow(TRANSACTION_TABLE_COLUMNS);
//            List<ExecRow> results=Lists.newArrayList();
//
//            try(Stream<TxnView> activeTxns=reader.getAllTransactions()){
//                TxnView txn;
//                while((txn=activeTxns.next())!=null){
//                    template.resetRowArray();
//                    DataValueDescriptor[] dvds=template.getRowArray();
//                    dvds[0].setValue(txn.getTxnId());
//                    if(txn.getParentTxnId()!=-1l)
//                        dvds[1].setValue(txn.getParentTxnId());
//                    else
//                        dvds[1].setToNull();
//                    Iterator<ByteSlice> destTables=txn.getDestinationTables();
//                    if(destTables!=null && destTables.hasNext()){
//                        StringBuilder tables=new StringBuilder();
//                        boolean isFirst=true;
//                        while(destTables.hasNext()){
//                            ByteSlice table=destTables.next();
//                            if(!isFirst) tables.append(",");
//                            else isFirst=false;
//                            tables.append(Bytes.toString(Encoding.decodeBytesUnsortd(table.array(),table.offset(),table.length())));
//                        }
//                        dvds[2].setValue(tables.toString());
//                    }else
//                        dvds[2].setToNull();
//
//                    dvds[3].setValue(txn.getState().toString());
//                    dvds[4].setValue(txn.getIsolationLevel().toHumanFriendlyString());
//                    dvds[5].setValue(txn.getBeginTimestamp());
//                    setLong(dvds[6],txn.getCommitTimestamp());
//                    setLong(dvds[7],txn.getEffectiveCommitTimestamp());
//                    dvds[8].setValue(txn.isAdditive());
//                    dvds[9].setValue(new Timestamp(txn.getLastKeepAliveTimestamp()),null);
//                    results.add(template.getClone());
//                }
//            }
//            EmbedConnection defaultConn=(EmbedConnection)SpliceAdmin.getDefaultConn();
//            Activation lastActivation=defaultConn.getLanguageConnection().getLastActivation();
//            IteratorNoPutResultSet rs=new IteratorNoPutResultSet(results,TRANSACTION_TABLE_COLUMNS,lastActivation);
//            rs.openCore();
//
//            resultSet[0]=new EmbedResultSet40(defaultConn,rs,false,null,true);
//        }catch(StreamException|IOException e){
//            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
//        }catch(StandardException e){
//            throw PublicAPI.wrapStandardException(e);
//        }
    }

    private static final ResultColumnDescriptor[] CHILD_TXN_ID_COLUMNS=new GenericColumnDescriptor[]{
            new GenericColumnDescriptor("childTxnId",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT))
    };

    public static void SYSCS_COMMIT_CHILD_TRANSACTION(Long txnId) throws SQLException, IOException{
        TxnLifecycleManager tc=SIDriver.driver().lifecycleManager();
        TxnSupplier store=SIDriver.driver().getTxnStore();
        Txn childTxn=store.getTransaction(txnId);
        if(childTxn==null){
            throw new IllegalArgumentException(String.format("Specified child transaction id %s not found.",txnId));
        }
        try{
            tc.commit(childTxn);
        }catch(IOException e){

            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    public static void SYSCS_ELEVATE_TRANSACTION(String tableName) throws IOException, SQLException{
        //TxnSupplier store = TransactionStorage.getTxnStore();
        EmbedConnection defaultConn=(EmbedConnection)SpliceAdmin.getDefaultConn();
        try{
            defaultConn.getLanguageConnection().getTransactionExecute().elevate();
        }catch(StandardException e){
            // TODO Auto-generated catch block
            throw new IllegalArgumentException(String.format("Specified tableName %s cannot be elevated. ",tableName));
        }

        ((SpliceTransactionManager)defaultConn.getLanguageConnection().getTransactionExecute()).getActiveStateTxn();

    }

    public static void SYSCS_START_CHILD_TRANSACTION(long parentTransactionId,String spliceTableName,ResultSet[] resultSet) throws IOException, SQLException{

        // Verify the parentTransactionId passed in
        TxnSupplier store=SIDriver.driver().getTxnStore();
        Txn parentTxn=store.getTransaction(parentTransactionId);
        if(parentTxn==null){
            throw new IllegalArgumentException(String.format("Specified parent transaction id %s not found. Unable to create child transaction.",parentTransactionId));
        }

        Txn childTxn;
        try{
            childTxn=SIDriver.driver().lifecycleManager().beginChildTransaction(parentTxn);
        }catch(IOException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        ExecRow row=new ValueRow(1);
        row.setColumn(1,new SQLLongint(childTxn.getTxnId()));
        EmbedConnection defaultConn=(EmbedConnection)SpliceAdmin.getDefaultConn();
        Activation lastActivation=defaultConn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet rs=new IteratorNoPutResultSet(Arrays.asList(row),CHILD_TXN_ID_COLUMNS,lastActivation);
        try{
            rs.openCore();
        }catch(StandardException e){
            throw PublicAPI.wrapStandardException(e);
        }
        resultSet[0]=new EmbedResultSet40(defaultConn,rs,false,null,true);
    }

    /******************************************************************************************************************/
    /*private helper methods*/
    private static void setLong(DataValueDescriptor dvd,Long value) throws StandardException{
        if(value!=null)
            dvd.setValue(value.longValue());
        else
            dvd.setToNull();
    }

    private static ExecRow toRow(ResultColumnDescriptor[] columns) throws StandardException{
        DataValueDescriptor[] dvds=new DataValueDescriptor[columns.length];
        for(int i=0;i<columns.length;i++){
            dvds[i]=columns[i].getType().getNull();
        }
        ExecRow row=new ValueRow(columns.length);
        row.setRowArray(dvds);
        return row;
    }
}
