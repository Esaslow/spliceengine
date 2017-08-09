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

package com.splicemachine.storage;

import com.splicemachine.metrics.MetricFactory;
import com.splicemachine.metrics.Metrics;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.storage.util.MeasuredResultScanner;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * @author Scott Fines
 *         Date: 12/22/15
 */
public abstract class SkeletonHBaseClientPartition implements Partition<byte[],Txn,IsolationLevel>{

    @Override
    public String getName(){
        return getTableName();
    }


    /*Single row access methods*/
    @Override
    public Record get(byte[] key, Txn txn, IsolationLevel isolationLevel) throws IOException{
        assert get instanceof HGet : "Programmer Error: incorrect type for performing a Get!";
        Result result=doGet(((HGet)get).unwrapDelegate());

        if(previous==null)
            previous = new HResult(result);
        else
            ((HResult)previous).set(result);
        return previous;
    }

    @Override
    public RecordScanner openScanner(RecordScan scan) throws IOException{
        MeasuredResultScanner scanner=new MeasuredResultScanner(getScanner(((HScan)scan).unwrapDelegate()),metricFactory);
        return new ResultDataScanner(scanner);
    }

    /*Data Mutation methods*/
    @Override
    public void put(DataPut put) throws IOException{
        doPut(((HPut)put).unwrapDelegate());
    }

    @Override
    public void delete(DataDelete delete) throws IOException{
        doDelete(((HDelete)delete).unwrapDelegate());
    }

    @Override
    public void mutate(DataMutation put) throws IOException{
        if(put instanceof HPut)
            doPut(((HPut)put).unwrapDelegate());
        else doDelete(((HDelete)put).unwrapDelegate());
    }


    @Override
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public Iterator<MutationStatus> writeBatch(DataPut[] toWrite) throws IOException{
        List<Put> puts = new ArrayList<>(toWrite.length);
        for(int i=0;i<toWrite.length;i++){
            puts.add(((HPut)toWrite[i]).unwrapDelegate());
        }
        doPut(puts);
        List<MutationStatus> results = new ArrayList<>(toWrite.length);
        for(int i=0;i<toWrite.length;i++){
            results.add(HMutationStatus.success());
        }

        return results.iterator();
    }

    //no-op for remote access
    @Override public void closeOperation() throws IOException{ }
    @Override public void startOperation() throws IOException{ }
    @Override public boolean isClosed(){ return false; }
    @Override public boolean isClosing(){ return false; }


    @Override
    public long increment(byte[] rowKey,byte[] family,byte[] qualifier,long amount) throws IOException{
        Increment incr = new Increment(rowKey);
        incr.addColumn(family,qualifier,amount);
        return doIncrement(incr);
    }

    @Override
    public Lock getRowLock(byte[] key,int keyOff,int keyLen) throws IOException{
        //we don't support distributed row locks--that would be hard
        throw new UnsupportedOperationException("Cannot support row locking with remote access");
    }
    /*
     * A Table represents all regions, so the range is (-Inf,Inf). Thus, it contains
     * everything.
     */
    @Override public byte[] getStartKey(){ return HConstants.EMPTY_START_ROW; }
    @Override public byte[] getEndKey(){ return HConstants.EMPTY_END_ROW; }
    @Override public boolean containsRow(byte[] row){ return true; }
    @Override public boolean containsRow(byte[] row,int offset,int length){ return true; }
    @Override public boolean overlapsRange(byte[] start,byte[] stop){ return true; }

    @Override
    public boolean overlapsRange(byte[] start,int startOff,int startLen,byte[] stop,int stopOff,int stopLen){
        return true;
    }

    @Override
    public void writesRequested(long writeRequests){
        //no-op
    }

    @Override
    public void readsRequested(long readRequests){
        //no-op
    }

    protected abstract Result doGet(Get get) throws IOException;

    protected abstract ResultScanner getScanner(Scan scan) throws IOException;

    protected abstract void doDelete(Delete delete) throws IOException;

    protected abstract void doPut(Put put) throws IOException;

    protected abstract void doPut(List<Put> puts) throws IOException;

    protected abstract long doIncrement(Increment incr) throws IOException;
}
