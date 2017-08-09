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

import com.carrotsearch.hppc.ObjectArrayList;
import com.splicemachine.ddl.DDLMessage;
import com.splicemachine.pipeline.api.Code;
import com.splicemachine.pipeline.client.WriteResult;
import com.splicemachine.pipeline.constraint.ConstraintContext;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.api.txn.IsolationLevel;
import com.splicemachine.storage.*;
import com.splicemachine.pipeline.api.PipelineExceptionFactory;
import com.splicemachine.pipeline.context.WriteContext;
import com.splicemachine.pipeline.writehandler.WriteHandler;
import com.splicemachine.si.impl.driver.SIDriver;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

/**
 * Intercepts deletes from a parent table (primary key or unique index) and sends the rowKey over to the referencing
 * indexes to check for its existence.
 */
@NotThreadSafe
public class ForeignKeyParentInterceptWriteHandler implements WriteHandler{
    private final List<Long> referencingIndexConglomerateIds;
    private final List<DDLMessage.FKConstraintInfo> constraintInfos;
    private final ForeignKeyViolationProcessor violationProcessor;
    private TxnOperationFactory txnOperationFactory;
    private HashMap<Long,Partition> childPartitions = new HashMap<>();
    private String parentTableName;
    private ObjectArrayList<Record> mutations = new ObjectArrayList<>();


    public ForeignKeyParentInterceptWriteHandler(String parentTableName,
                                                 List<Long> referencingIndexConglomerateIds,
                                                 PipelineExceptionFactory exceptionFactory,
                                                 List<DDLMessage.FKConstraintInfo> constraintInfos
                                                 ) {
        this.referencingIndexConglomerateIds = referencingIndexConglomerateIds;
        this.violationProcessor = new ForeignKeyViolationProcessor(
                new ForeignKeyViolationProcessor.ParentFkConstraintContextProvider(parentTableName),exceptionFactory);
        this.constraintInfos = constraintInfos;
        this.txnOperationFactory = SIDriver.driver().getOperationFactory();
        this.parentTableName = parentTableName;
    }

    @Override
    public void next(Record mutation, WriteContext ctx) {
        if (isForeignKeyInterceptNecessary(mutation.getRecordType())) {
            mutations.add(mutation);
        }
        ctx.sendUpstream(mutation);
    }
    /** We exist to prevent updates/deletes of rows from the parent table which are referenced by a child.
     * Since we are a WriteHandler on a primary-key or unique-index we can just handle deletes.
     */
    private boolean isForeignKeyInterceptNecessary(RecordType type) {
        /* We exist to prevent updates/deletes of rows from the parent table which are referenced by a child.
         * Since we are a WriteHandler on a primary-key or unique-index we can just handle deletes. */
        return type == RecordType.DELETE;
    }


    @Override
    public void flush(WriteContext ctx) throws IOException {
        try {
            // TODO Buffer with skip scan
            for (int k = 0; k<mutations.size();k++) {
                Record mutation = mutations.get(k);
                for (int i = 0; i < referencingIndexConglomerateIds.size(); i++) {
                    long indexConglomerateId = referencingIndexConglomerateIds.get(i);
                    Partition table = null;
                    if (childPartitions.containsKey(indexConglomerateId))
                        table = childPartitions.get(indexConglomerateId);
                    else {
                        table = SIDriver.driver().getTableFactory().getTable(Long.toString((indexConglomerateId)));
                        childPartitions.put(indexConglomerateId, table);
                    }
                    if (hasReferences(indexConglomerateId, table, mutation, ctx))
                        failRow(mutation, ctx, constraintInfos.get(i));
                    else
                        ctx.success(mutation);
                }
            }
        } catch (Exception e) {
            violationProcessor.failWrite(e, ctx);
        }

    }

    @Override
    public void close(WriteContext ctx) throws IOException {
        mutations.clear();
        for (Partition table:childPartitions.values()) {
            if (table != null)
                table.close();
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

            /*
         * The way prefix keys work is that longer keys sort after shorter keys. We
         * are already starting exactly where we want to be, and we want to end as soon
         * as we hit a record which is not this key.
         *
         * Historically, we did this by using an HBase PrefixFilter. We can do that again,
         * but it's a bit of a pain to make that work in an architecture-independent
         * way (we would need to implement a version of that for other architectures,
         * for example. It's much easier for us to just make use of row key sorting
         * to do the job for us.
         *
         * We start where we want, and we need to end as soon as we run off that. The
         * first key which is higher than the start key is the start key as a prefix followed
         * by 0x00 (in unsigned sort order). Therefore, we make the end key
         * [startKey | 0x00].
         */


        private boolean hasReferences(Long indexConglomerateId, Partition table, Record kvPair, WriteContext ctx) throws IOException {
        byte[] startKey = kvPair.getKey();
        //make sure this is a transactional scan
        RecordScan scan = txnOperationFactory.newDataScan(); // Non-Transactional, will resolve on this side
        scan =scan.startKey(startKey);
        byte[] endKey = Bytes.unsignedCopyAndIncrement(startKey);//new byte[startKey.length+1];
        scan = scan.stopKey(endKey);
            Record next = null;
            try(RecordScanner scanner = table.openScanner(scan,ctx.getTxn(), IsolationLevel.READ_COMMITTED)) {
                while ((next = scanner.next()) != null) {
                    return true;
                }
            }
            try(RecordScanner scanner = table.openScanner(scan,ctx.getTxn(), IsolationLevel.READ_UNCOMMITTED)) {
                while ((next = scanner.next()) != null) {
                    return true;
                }
            }
            return false;
    }
    /**
     *
     * TODO JL
     * Try to understand why we have replacement algorithms for simple messages.
     *
     * @param mutation
     * @param ctx
     * @param fkConstraintInfo
     */
    private void failRow(Record mutation, WriteContext ctx, DDLMessage.FKConstraintInfo fkConstraintInfo ) {
        String failedKvAsHex = Bytes.toHex(mutation.getKey());
        ConstraintContext context = ConstraintContext.foreignKey(fkConstraintInfo);
        WriteResult foreignKeyConstraint = new WriteResult(Code.FOREIGN_KEY_VIOLATION, context.withMessage(1, parentTableName));
        ctx.failed(mutation, foreignKeyConstraint);
    }
}
