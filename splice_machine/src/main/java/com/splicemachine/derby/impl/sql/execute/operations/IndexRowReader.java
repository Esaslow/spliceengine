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

package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.si.api.txn.IsolationLevel;
import com.splicemachine.si.api.txn.Txn;
import org.spark_project.guava.collect.Lists;
import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.impl.store.access.hbase.HBaseRowLocation;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.storage.*;
import com.splicemachine.utils.Pair;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Utility for executing "look-ahead" index lookups, where the index lookup is backgrounded,
 * while other processes occur on the caller thread.
 *
 * @author Scott Fines
 *         Created on: 9/4/13
 */
public class IndexRowReader implements Iterator<LocatedRow>, Iterable<LocatedRow>{
    protected static Logger LOG=Logger.getLogger(IndexRowReader.class);
    private final ExecutorService lookupService;
    private final int batchSize;
    private final int numBlocks;
    private final ExecRow outputTemplate;
    private final long mainTableConglomId;
    private final int[] indexCols;
    private final Txn txn;
    private final TxnOperationFactory operationFactory;
    private final PartitionFactory tableFactory;

    private List<Pair<LocatedRow, Record>> currentResults;
    private List<Future<List<Pair<LocatedRow, Record>>>> resultFutures;
    private boolean populated=false;
    protected Iterator<LocatedRow> sourceIterator;

    private LocatedRow heapRowToReturn=new LocatedRow();
    private LocatedRow indexRowToReturn;

    IndexRowReader(ExecutorService lookupService,
                   Iterator<LocatedRow> sourceIterator,
                   ExecRow outputTemplate,
                   Txn txn,
                   int lookupBatchSize,
                   int numConcurrentLookups,
                   long mainTableConglomId,
                   int[] indexCols,
                   TxnOperationFactory operationFactory,
                   PartitionFactory tableFactory){
        this.lookupService=lookupService;
        this.sourceIterator=sourceIterator;
        this.outputTemplate=outputTemplate;
        this.txn=txn;
        batchSize=lookupBatchSize;
        this.numBlocks=numConcurrentLookups;
        this.mainTableConglomId=mainTableConglomId;
        this.tableFactory=tableFactory;
        this.indexCols=indexCols;
        this.resultFutures=Lists.newArrayListWithCapacity(numConcurrentLookups);
        this.operationFactory = operationFactory;
    }

    public void close() throws IOException{
        lookupService.shutdownNow();
    }

    @Override
    public LocatedRow next(){
        return heapRowToReturn;
    }

    public LocatedRow nextScannedRow(){
        return indexRowToReturn;
    }

    @Override
    public void remove(){

    }

    @Override
    public boolean hasNext(){
        throw new UnsupportedOperationException("not implemented");
            /*
        try{
            if(currentResults==null || currentResults.size()<=0)
                getMoreData();

            if(currentResults==null || currentResults.size()<=0){
                return false; // No More Data
            }

            Pair<LocatedRow, Record> next=currentResults.remove(0);
            //merge the results
            LocatedRow nextScannedRow=next.getFirst();
            Record nextFetchedData=next.getSecond();

            for(DataCell kv : nextFetchedData){
                keyDecoder.decode(kv.keyArray(),kv.keyOffset(),kv.keyLength(),nextScannedRow.getRow());
                rowDecoder.set(kv.valueArray(),kv.valueOffset(),kv.valueLength());
                rowDecoder.decode(nextScannedRow.getRow());
            }
            heapRowToReturn=nextScannedRow;
            indexRowToReturn=nextScannedRow;
            return true;
        }catch(Exception e){
            throw new RuntimeException(e);
        }
        }*/
    }


    /**********************************************************************************************************************************/
        /*private helper methods*/
    private void getMoreData() throws StandardException, IOException{
        //read up to batchSize rows from the source, then submit them to the background thread for processing
        List<LocatedRow> sourceRows=Lists.newArrayListWithCapacity(batchSize);
        for(int i=0;i<batchSize;i++){
            if(!sourceIterator.hasNext())
                break;
            LocatedRow next=sourceIterator.next();
            for(int index=0;index<indexCols.length;index++){
                if(indexCols[index]!=-1){
                    outputTemplate.setColumn(index+1,next.getRow().getColumn(indexCols[index]+1));
                }
            }
            HBaseRowLocation rl=(HBaseRowLocation)next.getRow().getColumn(next.getRow().nColumns());
            sourceRows.add(new LocatedRow(HBaseRowLocation.deepClone(rl), outputTemplate.getClone()));
        }
        if(sourceRows.size()>0){
            //submit to the background thread
            Lookup task=new Lookup(sourceRows);
            resultFutures.add(lookupService.submit(task));
        }

        //if there is only one submitted future, call this again to set off an additional background process
        if(resultFutures.size()<numBlocks && sourceRows.size()==batchSize)
            getMoreData();
        else if(resultFutures.size()>0){
            waitForBlockCompletion();
        }
    }

    private void waitForBlockCompletion() throws StandardException, IOException{
        //wait for the first future to return correctly or error-out
        try{
            Future<List<Pair<LocatedRow, Record>>> future=resultFutures.remove(0);
            currentResults=future.get();
        }catch(InterruptedException e){
            throw new InterruptedIOException(e.getMessage());
        }catch(ExecutionException e){
            Throwable t=e.getCause();
            if(t instanceof IOException) throw (IOException)t;
            else throw Exceptions.parseException(t);
        }
    }

    public class Lookup implements Callable<List<Pair<LocatedRow, Record>>>{
        private final List<LocatedRow> sourceRows;

        public Lookup(List<LocatedRow> sourceRows){
            this.sourceRows=sourceRows;
        }

        @Override
        public List<Pair<LocatedRow, Record>> call() throws Exception{
            List<byte[]> rowKeys = new ArrayList<>(sourceRows.size());
            for(LocatedRow sourceRow : sourceRows){
                byte[] row=sourceRow.getRowLocation().getBytes();
                rowKeys.add(row);
            }

            try(Partition table = tableFactory.getTable(Long.toString(mainTableConglomId))){
                Iterator<Record> results=table.batchGet(rowKeys,txn, IsolationLevel.SNAPSHOT_ISOLATION);
                List<Pair<LocatedRow, Record>> locations=Lists.newArrayListWithCapacity(sourceRows.size());
                for(LocatedRow sourceRow : sourceRows){
                    if(!results.hasNext())
                        throw new IllegalStateException("Programmer error: incompatible iterator sizes!");
                    locations.add(Pair.newPair(sourceRow,results.next()));
                }
                return locations;
            }
        }
    }

    @Override
    public Iterator<LocatedRow> iterator(){
        return this;
    }

}
