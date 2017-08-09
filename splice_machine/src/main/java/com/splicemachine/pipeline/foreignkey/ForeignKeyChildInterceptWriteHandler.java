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

package com.splicemachine.pipeline.foreignkey;

import com.carrotsearch.hppc.BitSet;
import com.carrotsearch.hppc.ObjectArrayList;
import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.ddl.DDLMessage.*;
import com.splicemachine.pipeline.api.Code;
import com.splicemachine.pipeline.api.PipelineExceptionFactory;
import com.splicemachine.pipeline.client.WriteResult;
import com.splicemachine.pipeline.constraint.ConstraintContext;
import com.splicemachine.pipeline.context.WriteContext;
import com.splicemachine.pipeline.writehandler.WriteHandler;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.storage.Partition;
import com.splicemachine.storage.Record;
import com.splicemachine.storage.RecordType;

import javax.annotation.concurrent.NotThreadSafe;
import javax.ws.rs.NotSupportedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Intercepts insert/updates to a FK constraint backing index and sends the rowKey over to the referenced primary-key or
 * unique-index region for existence checking.
 */
@NotThreadSafe
public class ForeignKeyChildInterceptWriteHandler implements WriteHandler{
    private final long referencedConglomerateNumber;
    private final ForeignKeyViolationProcessor violationProcessor;
    private Partition table;
    private ObjectArrayList<Record> mutations = new ObjectArrayList<>();
    private final int formatIds[];
    private FKConstraintInfo fkConstraintInfo;

    public ForeignKeyChildInterceptWriteHandler(long referencedConglomerateNumber,
                                                FKConstraintInfo fkConstraintInfo,
                                                PipelineExceptionFactory exceptionFactory) {
        this.referencedConglomerateNumber = referencedConglomerateNumber;
        this.violationProcessor = new ForeignKeyViolationProcessor(
                new ForeignKeyViolationProcessor.ChildFkConstraintContextProvider(fkConstraintInfo),
                exceptionFactory);
        this.formatIds = new int[fkConstraintInfo.getFormatIdsCount()];
        for (int i =0;i<fkConstraintInfo.getFormatIdsCount();i++)
            this.formatIds[i] = fkConstraintInfo.getFormatIds(i);
        this.fkConstraintInfo = fkConstraintInfo;
    }

    @Override
    public void next(Record mutation, WriteContext ctx) {
        if (isForeignKeyInterceptNecessary(mutation.getRecordType())) {
            mutations.add(mutation);
            ctx.success(mutation);
        }
        ctx.sendUpstream(mutation);
    }

    /* This WriteHandler doesn't do anything when, for example, we delete from the FK backing index. */
    private boolean isForeignKeyInterceptNecessary(RecordType type) {
        return type == RecordType.INSERT || type == RecordType.UPDATE || type == RecordType.UPSERT;
    }

    @Override
    public void flush(WriteContext ctx) throws IOException {
        throw new NotSupportedException("Not Implemented");
        /*
        try {
            initTable();
            HashSet<byte[]> culledLookups = new HashSet(mutations.size());
            int[] locations = new int[mutations.size()];
            int counter = 0;
            for (int i =0; i<mutations.size();i++) {
                byte[] checkRowKey = getCheckRowKey(mutations.get(i).getKey());
                if (culledLookups.contains(checkRowKey)) {
                    locations[i] = counter-1;
                } else {
                    culledLookups.add(checkRowKey);
                    locations[i] = counter;
                    counter++;
                }
            }

            List<byte[]> rowKeysToFetch = new ArrayList<>(culledLookups.size());
            for (byte[] item: culledLookups) {
                rowKeysToFetch.add(item);
            }
            SimpleTxnFilter readUncommittedFilter;
            SimpleTxnFilter readCommittedFilter;
            if (ctx.getTxn() instanceof ActiveWriteTxn) {
                readUncommittedFilter = new SimpleTxnFilter(Long.toString(referencedConglomerateNumber), ((ActiveWriteTxn) ctx.getTxn()).getReadUncommittedActiveTxn(), NoOpReadResolver.INSTANCE, SIDriver.driver().getTxnStore());
                readCommittedFilter = new SimpleTxnFilter(Long.toString(referencedConglomerateNumber), ((ActiveWriteTxn) ctx.getTxn()).getReadCommittedActiveTxn(), NoOpReadResolver.INSTANCE, SIDriver.driver().getTxnStore());
            }else if (ctx.getTxn() instanceof WritableTxn) {
                readUncommittedFilter = new SimpleTxnFilter(Long.toString(referencedConglomerateNumber), ((WritableTxn) ctx.getTxn()).getReadUncommittedActiveTxn(), NoOpReadResolver.INSTANCE, SIDriver.driver().getTxnStore());
                readCommittedFilter = new SimpleTxnFilter(Long.toString(referencedConglomerateNumber), ((WritableTxn) ctx.getTxn()).getReadCommittedActiveTxn(), NoOpReadResolver.INSTANCE, SIDriver.driver().getTxnStore());
            }else
                throw new IOException("invalidTxn");

            Iterator<DataResult> iterator = table.batchGet(new MapAttributes(),rowKeysToFetch);
            BitSet misses = new BitSet(rowKeysToFetch.size());

            int i = 0;
            while (iterator.hasNext()) {
                DataResult result = iterator.next();
                readCommittedFilter.reset();
                readUncommittedFilter.reset();
                if (!hasData(result,readCommittedFilter) || !hasData(result,readUncommittedFilter))
                    misses.set(i);
                i++;
            }

            // No Misses...
            if (misses.isEmpty())
                return;
            // Assemble failures for the write pipeline with error codes.
            i=0;
            for (int location: locations) {
                if (misses.get(location))
                    failWrite(mutations.get(i),ctx);
                i++;
            }
        } catch (Exception e) {
            violationProcessor.failWrite(e, ctx);
        }finally{
            if(table!=null)
                table.close();
            mutations.clear();
        }
        */

    }

    @Override
    public void close(WriteContext ctx) throws IOException {
        if (mutations.size() > 0)
            flush(ctx);
    }

    /* Only need to create the CallBuffer once, but not until we have a WriteContext */
    private void initTable() throws IOException{
            if (table==null)
                table = SIDriver.driver().getTableFactory().getTable(Long.toString((referencedConglomerateNumber)));
    }

    @Override
    public String toString() {
        return "ForeignKeyChildInterceptWriteHandler{parentTable='" + referencedConglomerateNumber + '\'' + '}';
    }

    private void failWrite(Record record, WriteContext ctx) {
        WriteResult foreignKeyConstraint = new WriteResult(Code.FOREIGN_KEY_VIOLATION, ConstraintContext.foreignKey(fkConstraintInfo));
        ctx.failed(record, foreignKeyConstraint);
    }

    /**
     * The rowKey we get in this class, via the write pipeline, is the row key we are attempting to write to
     * the FK backing index.  We have to account for two major things before checking for its existence in the
     * referenced primary key or unique index:
     *
     * (1)
     * If the FK backing index is non-unique (the default, always the case if there is not also a unique constraint
     * on the FK column) then there will be more columns (appended) in the KVPair rowKey than exist in the referenced
     * primary-key/index because of the way we encode rowKeys in non-unique indexes. Unfortunate because in that case we
     * create a new byte array for each KV. DB-2582 exists to see if we can avoid this (possible performance optimization).
     *
     * (2)
     * We have to use a MultiFieldDecoder here to determine if any of the columns in the index are null.  Per the spec
     * we do not check FK constraints on child rows if any col in the FK is null. We have to use MultiFieldDecoder
     * even if we know there are no nulls (because of a not-null constraint for example) in order to correctly
     * count the columns and apply the logic described in #1.
     *
     * Example (two col FK where the primary-key or unique-index to check ends in '45'):
     *
     * rowKeyIn          = [65, 67, 0 54, 45, 0, bytes, to, make, index-entry, unique]
     * formatIds.length  = 2
     * return value      = [65, 67, 0 54, 45]
     */
    private byte[] getCheckRowKey(byte[] rowKeyIn) {
        throw new NotSupportedException("Not Implemented");
    /*
        int position = 0;
        multiFieldDecoder.set(rowKeyIn);
        for (int i = 0; i < formatIds.length; i++) {
            if (multiFieldDecoder.nextIsNull()) {
                return null;
            }
            if (formatIds[i] == StoredFormatIds.SQL_DOUBLE_ID) {
                position += multiFieldDecoder.skipDouble();
            } else if (formatIds[i] == StoredFormatIds.SQL_REAL_ID) {
                position += multiFieldDecoder.skipFloat();
            } else if (typeProvider.isScalar(formatIds[i])) {
                position += multiFieldDecoder.skipLong();
            } else {
                position += multiFieldDecoder.skip();
            }
        }
        int lastKeyIndex = position - 2;

        if (lastKeyIndex == rowKeyIn.length - 1) {
            return rowKeyIn;
        }
        byte[] checkRowKey = new byte[lastKeyIndex + 1];
        System.arraycopy(rowKeyIn, 0, checkRowKey, 0, lastKeyIndex + 1);
        return checkRowKey;
        */
    }

}
