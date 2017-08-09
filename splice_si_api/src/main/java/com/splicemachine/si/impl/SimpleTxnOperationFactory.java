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

package com.splicemachine.si.impl;

import com.splicemachine.access.impl.data.UnsafeRecord;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.api.txn.ConflictType;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.storage.Record;
import com.splicemachine.storage.RecordScan;
import com.splicemachine.storage.RecordType;
import com.splicemachine.utils.IntArrays;

import javax.ws.rs.NotSupportedException;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;


/**
 * @author Scott Fines
 *         Date: 7/8/14
 */
public class SimpleTxnOperationFactory implements TxnOperationFactory{
    public SimpleTxnOperationFactory(){
    }

    @Override
    public void writeTxn(Txn txn, ObjectOutput out) throws IOException{
        out.writeObject(txn);
    }

    @Override
    public Txn readTxn(ObjectInput in) throws IOException {
        try {
            return (Txn) in.readObject();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public ConflictType conflicts(Record potentialRecord, Record existingRecord) {
        return null;
    }

    @Override
    public RecordScan newDataScan() {
        return null;
    }

    @Override
    public Record newRecord(Txn txn, byte[] key) {
        UnsafeRecord record = new UnsafeRecord(key,1l,true);
        record.setTxnId1(txn.getTxnId());
        record.setTxnId2(txn.getParentTxnId());
        record.setRecordType(RecordType.INSERT);
        return record;
    }

    @Override
    public Record newRecord(Txn txn, byte[] key, int[] fields, ExecRow data) throws StandardException{
        return newRecord(txn,key,fields,data.getRowArray());
    }

    @Override
    public Record newRecord(Txn txn, byte[] key, int[] fields, DataValueDescriptor[] data) throws StandardException{
        return newRecord(txn,key,16, key.length,fields,data);
    }

    @Override
    public Record newRecord(Txn txn, byte[] keyObject, int keyOffset, int keyLength, int[] fields, ExecRow data) {
        return newRecord(txn, keyObject, keyOffset, keyLength, fields, data);
    }

    @Override
    public Record newRecord(Txn txn, byte[] keyObject, int keyOffset, int keyLength, int[] fields, DataValueDescriptor[] data) throws StandardException {
        System.out.println("new Reocrd");
        Record record = new UnsafeRecord(keyObject,keyOffset,keyLength,1,new byte[400],16,true);
        record.setTxnId1(txn.getParentTxnId());
        record.setTxnId2(txn.getTxnId());
        record.setNumberOfColumns(2);
        System.out.println("new Reocrd2");
        record.setData(fields,data);
        System.out.println("new Reocrd3");
        return record;
    }

    @Override
    public Record newRecord(Txn txn, byte[] key, ExecRow data) throws StandardException {
        return newRecord(txn, key, data.getRowArray());
    }

    @Override
    public Record newRecord(Txn txn, byte[] key, DataValueDescriptor[] data) throws StandardException {
        return newRecord(txn, key, 0, key.length, IntArrays.count(data.length), data);
    }

    @Override
    public DDLFilter newDDLFilter(Txn txn) {
        throw new NotSupportedException("Not Implemented");
    }

    @Override
    public Record newDelete(Txn txn, byte[] key) {
        UnsafeRecord record = new UnsafeRecord(key,1l,true);
        record.setTxnId1(txn.getTxnId());
        record.setTxnId2(txn.getParentTxnId());
        record.setRecordType(RecordType.DELETE);
        return record;
    }

    @Override
    public Record newUpdate(Txn txn, byte[] key) {
        return null;
    }

    @Override
    public RecordScan readScan(ObjectInput in) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void writeScan(RecordScan scan, ObjectOutput out) {
        throw new UnsupportedOperationException("not implemented");
    }
}
