
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

package com.splicemachine.derby.impl.store.access.btree;

import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.io.FormatableBitSet;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.store.access.ConglomerateController;
import com.splicemachine.db.iapi.store.raw.Transaction;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.impl.store.access.base.OpenSpliceConglomerate;
import com.splicemachine.derby.impl.store.access.base.SpliceController;
import com.splicemachine.derby.utils.DerbyBytesUtil;
import com.splicemachine.derby.utils.Scans;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.api.txn.IsolationLevel;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.storage.*;
import com.splicemachine.utils.IntArrays;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.log4j.Logger;
import java.io.IOException;
import java.util.Arrays;


public class IndexController extends SpliceController{
    private static Logger LOG=Logger.getLogger(IndexController.class);
    private int nKeyFields;

    public IndexController(OpenSpliceConglomerate openSpliceConglomerate,
                           Transaction trans,
                           PartitionFactory partitionFactory,
                           TxnOperationFactory txnOperationFactory,
                           int nKeyFields){
        super(openSpliceConglomerate,trans,partitionFactory,txnOperationFactory);
        this.nKeyFields=nKeyFields;
    }

    private byte[] generateIndexKey(DataValueDescriptor[] row,boolean[] order) throws IOException, StandardException{
        if(row.length==nKeyFields){
            return Scans.generateIndexKey(row,order,"1.0",false);
        }
        DataValueDescriptor[] uniqueRow=new DataValueDescriptor[nKeyFields];
        System.arraycopy(row,0,uniqueRow,0,nKeyFields);
        return Scans.generateIndexKey(uniqueRow,order,"1.0",false);
    }

    @Override
    public int insert(DataValueDescriptor[] row) throws StandardException{
        assert row!=null: "Cannot insert a null row";
        if(LOG.isTraceEnabled())
            LOG.trace(String.format("insert row into conglomerate: %s, row: %s",this.getConglomerate(),(Arrays.toString(row))));
        Partition htable = getTable();
        try{
            boolean[] order=((IndexConglomerate)this.openSpliceConglomerate.getConglomerate()).getAscDescInfo();
            byte[] rowKey=generateIndexKey(row,order);
            /*
			 * Check if the rowKey already exists.
			 * TODO: An optimization would be to not check for existence of a rowKey if the index is non-unique.
			 *		 Unfortunately, this information is not available here and would need to be passed down from
			 *		 DataDictionaryImpl through TabInfoImpl.  Something worth looking into in the future.
			 */
            Txn txn = trans.getTxnInformation();
            Record record = htable.get(rowKey,txn, IsolationLevel.SNAPSHOT_ISOLATION);
            if(record==null){
                record = opFactory.newRecord(txn,rowKey, IntArrays.count(row.length),row);
                htable.insert(record,txn);
                return 0;
            }else{
                return ConglomerateController.ROWISDUPLICATE;
            }
        }catch(Exception e){
            LOG.error(e.getMessage(),e);
            throw Exceptions.parseException(e);
        }
    }

    @Override
    public void insertAndFetchLocation(DataValueDescriptor[] row,RowLocation destRowLocation) throws StandardException{
        assert row!=null: "Cannot insert a null row!";
        if(LOG.isTraceEnabled())
            LOG.trace(String.format("insertAndFetchLocation into conglomerate: %s, row: %s, rowLocation: %s",this.getConglomerate(),(Arrays.toString(row)),destRowLocation));
        try(Partition htable = getTable()){
            boolean[] order=((IndexConglomerate)this.openSpliceConglomerate.getConglomerate()).getAscDescInfo();
            byte[] rowKey=generateIndexKey(row,order);
            Record record = opFactory.newRecord(trans.getTxnInformation(),rowKey,row);
            destRowLocation.setValue(rowKey);
            htable.insert(record,trans.getTxnInformation());
        }catch(Exception e){
            throw StandardException.newException("insert and fetch location error",e);
        }
    }

    @Override
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION",justification = "Intentional")
    public boolean replace(RowLocation loc,DataValueDescriptor[] row,FormatableBitSet validColumns) throws StandardException{
        /*
        assert row!=null:"Cannot replace using a null row!";
        if(LOG.isTraceEnabled())
            LOG.trace(String.format("replace conglomerate: %s, rowlocation: %s, destRow: %s, validColumns: %s",this.getConglomerate(),loc,(row==null?null:Arrays.toString(row)),validColumns));
        Partition htable = getTable();
        try{
            boolean[] sortOrder=((IndexConglomerate)this.openSpliceConglomerate.getConglomerate()).getAscDescInfo();
            DataPut put;
            int[] validCols;
            if(openSpliceConglomerate.cloneRowTemplate().length==row.length && validColumns==null){
                put=opFactory.newDataPut(trans.getTxnInformation(),DerbyBytesUtil.generateIndexKey(row,sortOrder,"1.0",false));
                validCols=null;
            }else{
                DataValueDescriptor[] oldValues=openSpliceConglomerate.cloneRowTemplate();
                DataGet get=opFactory.newDataGet(trans.getTxnInformation(),loc.getBytes(),null);
                get = createGet(get,oldValues,null);
                DataResult result=htable.get(get,null);
                ExecRow execRow=new ValueRow(oldValues.length);
                execRow.setRowArray(oldValues);
                DescriptorSerializer[] serializers=VersionedSerializers.forVersion("1.0",true).getSerializers(execRow);
                KeyHashDecoder decoder=BareKeyHash.decoder(null,null,serializers);
                try{
                    DataCell kv=result.userData();
                    decoder.set(kv.valueArray(),
                                kv.valueOffset(),
                            kv.valueLength());
                    decoder.decode(execRow);
                    validCols=new int[validColumns.getNumBitsSet()];
                    int pos=0;
                    for(int i=validColumns.anySetBit();i!=-1;i=validColumns.anySetBit(i)){
                        oldValues[i]=row[i];
                        validCols[pos]=i;
                    }
                    byte[] rowKey=generateIndexKey(row,sortOrder);
                    put=opFactory.newDataPut(trans.getTxnInformation(),rowKey);
                }finally{
                    try{decoder.close();}catch(IOException ignored){}
                }
            }

            encodeRow(row,put,validCols,validColumns);
            htable.put(put);
            super.delete(loc);
            return true;
        }catch(Exception e){
            throw StandardException.newException("Error during replace "+e);
        }
        */
        throw new UnsupportedOperationException("Not Implemented");
    }

    @Override
    public boolean isKeyed(){
        return true;
    }

}
