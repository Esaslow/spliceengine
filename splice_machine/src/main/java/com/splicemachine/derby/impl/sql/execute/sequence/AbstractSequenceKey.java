package com.splicemachine.derby.impl.sql.execute.sequence;

import java.util.Arrays;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.tools.ResourcePool;
import org.apache.hadoop.hbase.client.HTableInterface;

public abstract class AbstractSequenceKey implements ResourcePool.Key{
        protected HTableInterface table;
        protected final byte[] sysColumnsRow;
        protected final long blockAllocationSize;
        protected long autoIncStart;
        protected long autoIncrement;
        public AbstractSequenceKey(
                   HTableInterface table,
                   byte[] sysColumnsRow,
                   long blockAllocationSize,
                   long autoIncStart,
                   long autoIncrement) {
            assert (table != null && sysColumnsRow != null): "Null Table or SysColumnsRow Passed in";
            this.sysColumnsRow = sysColumnsRow;
            this.blockAllocationSize = blockAllocationSize;
            this.table = table;
            this.autoIncStart = autoIncStart;
            this.autoIncrement = autoIncrement;
        }

        public byte[] getSysColumnsRow(){
            return sysColumnsRow;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AbstractSequenceKey)) return false;
            AbstractSequenceKey key = (AbstractSequenceKey) o;
            return Arrays.equals(sysColumnsRow, key.sysColumnsRow)
                    && blockAllocationSize == key.blockAllocationSize &&
                    autoIncStart == key.autoIncStart &&
                    autoIncrement == key.autoIncrement;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(sysColumnsRow);
        }

        public long getStartingValue() throws StandardException{
            return autoIncStart;
        }

        public long getIncrementSize() throws StandardException{
            return autoIncrement;
        }
        public abstract SpliceSequence makeNew() throws StandardException;
    }