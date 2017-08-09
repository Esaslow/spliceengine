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

package com.splicemachine.access.client;

import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.constants.SIConstants;
import org.junit.Assert;
import org.apache.hadoop.hbase.KeyValue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * Deprecate
 *
 * Created by jleach on 7/8/16.
 */
@Deprecated
public class SpliceHRegionInfoTest {
    private static final byte[] rowKey = "1234".getBytes();
    private static long DEFAULT_TIMESTAMP = 12345l;
    private static long DEFAULT_TIMESTAMP_2 = 12346l;


    @Test
    public void testOrderingOfColumns() {
        List<KeyValue> keyValueList = new ArrayList();
            keyValueList.add(new KeyValue(rowKey, SIConstants.DEFAULT_FAMILY_BYTES,
            SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES, DEFAULT_TIMESTAMP, rowKey));
        keyValueList.add(new KeyValue(rowKey, SIConstants.DEFAULT_FAMILY_BYTES,
                SIConstants.PACKED_COLUMN_BYTES, DEFAULT_TIMESTAMP, rowKey));
        keyValueList.add(new KeyValue(rowKey, SIConstants.DEFAULT_FAMILY_BYTES,
                SIConstants.SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES, DEFAULT_TIMESTAMP_2, rowKey));

        Collections.sort(keyValueList,SpliceKVComparator.INSTANCE);
        Assert.assertTrue("Position 1 incorrect",Bytes.equals(keyValueList.get(0).getQualifierArray(),
                keyValueList.get(0).getQualifierOffset(),keyValueList.get(0).getQualifierLength(),
                SIConstants.SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES,0,1));
        Assert.assertTrue("Position 2 incorrect",Bytes.equals(keyValueList.get(1).getQualifierArray(),
                keyValueList.get(1).getQualifierOffset(),keyValueList.get(1).getQualifierLength(),
                SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES,0,1));
        Assert.assertTrue("Position 3 incorrect",Bytes.equals(keyValueList.get(2).getQualifierArray(),
                keyValueList.get(2).getQualifierOffset(),keyValueList.get(2).getQualifierLength(),
                SIConstants.PACKED_COLUMN_BYTES,0,1));

    }
}
