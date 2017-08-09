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

package com.splicemachine.si.constants;

import com.splicemachine.primitives.Bytes;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Created by jleach on 12/9/15.
 */
@SuppressFBWarnings("MS_MUTABLE_ARRAY")
public class SIConstants {

    public static final String SEQUENCE_TABLE_NAME = "SPLICE_SEQUENCES";
    public static final byte[] SEQUENCE_TABLE_NAME_BYTES = Bytes.toBytes("SPLICE_SEQUENCES");
    //snowflake stuff
    public static final String MACHINE_ID_COUNTER = "MACHINE_IDS";
    public static final byte[] COUNTER_COL = Bytes.toBytes("c");

    public static final int TRANSACTION_TABLE_BUCKET_COUNT = 16; //must be a power of 2
    public static final byte[] TRUE_BYTES = Bytes.toBytes(true);
    public static final byte[] FALSE_BYTES = Bytes.toBytes(false);
    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    /**
     * Splice Columns
     *
     * 0 = contains commit timestamp (optionally written after writing transaction is final)
     * 1 = tombstone (if value empty) or anti-tombstone (if value "0")
     * 7 = encoded user data
     * 9 = column for causing write conflicts between concurrent transactions writing to parent and child FK tables
     */
    public static final byte[] SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES = Bytes.toBytes("0");
    public static final byte[] SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES = Bytes.toBytes("1");


    public static final String SI_TRANSACTION_KEY = "T";
    public static final String SI_TRANSACTION_ID_KEY = "A";
    public static final String SI_NEEDED = "B";
    public static final String SI_DELETE_PUT = "D";
    public static final String SI_COUNT_STAR = "M";

    //common SI fields
    public static final String NA_TRANSACTION_ID = "NA_TRANSACTION_ID";
    public static final String SI_EXEMPT = "si-exempt";

    public static final byte[] SI_NEEDED_VALUE_BYTES = Bytes.toBytes((short) 0);

    // The column in which splice stores encoded/packed user data.
    public static final byte[] PACKED_COLUMN_BYTES = Bytes.toBytes("7");

    public static final String DEFAULT_FAMILY_NAME = "V";

    public static final byte[] DEFAULT_FAMILY_BYTES = Bytes.toBytes("V");

    public static final String SI_PERMISSION_FAMILY = "P";

    // Default Constants
    public static final String SUPPRESS_INDEXING_ATTRIBUTE_NAME = "iu";
    public static final byte[] SUPPRESS_INDEXING_ATTRIBUTE_VALUE = new byte[]{};
    public static final String CHECK_BLOOM_ATTRIBUTE_NAME = "cb";

    public static final String ENTRY_PREDICATE_LABEL= "p";

    public static final int DEFAULT_CACHE_SIZE=1<<10;

    // Name of property to use for caching full display name of table and index.
    // Generic but ultimately used in hbase where we want these to be available
    // in HTableDescriptor.

    public static final String TABLE_DISPLAY_NAME_ATTR = "tableDisplayName";
    public static final String INDEX_DISPLAY_NAME_ATTR = "indexDisplayName";
}
