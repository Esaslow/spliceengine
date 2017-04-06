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
package com.splicemachine.db.iapi.types;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.stats.ColumnStatisticsImpl;
import com.splicemachine.db.iapi.stats.ItemStatistics;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Order;
import org.apache.hadoop.hbase.util.PositionedByteRange;
import org.apache.hadoop.hbase.util.SimplePositionedMutableByteRange;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * Test Class for SQLSmallint
 *
 */
public class SQLSmallIntTest extends SQLDataValueDescriptorTest {

        @Test
        public void addTwo() throws StandardException {
            SQLSmallint integer1 = new SQLSmallint(100);
            SQLSmallint integer2 = new SQLSmallint(100);
            Assert.assertEquals("Integer Add Fails", 200, integer1.plus(integer1, integer2, null).getInt(),0);
        }
    
        @Test
        public void subtractTwo() throws StandardException {
                SQLSmallint integer1 = new SQLSmallint(200);
                SQLSmallint integer2 = new SQLSmallint(100);
            Assert.assertEquals("Integer subtract Fails",100,integer1.minus(integer1, integer2, null).getInt(),0);
        }
        @Test(expected = StandardException.class)
        public void testPositiveOverFlow() throws StandardException {
            SQLSmallint integer1 = new SQLSmallint(Integer.MAX_VALUE);
            SQLSmallint integer2 = new SQLSmallint(1);
            integer1.plus(integer1,integer2,null);
        }

        @Test(expected = StandardException.class)
        public void testNegativeOverFlow() throws StandardException {
                SQLSmallint integer1 = new SQLSmallint(Integer.MIN_VALUE);
                SQLSmallint integer2 = new SQLSmallint(1);
                integer1.minus(integer1, integer2, null);
        }

        @Test
        public void serdeKeyData() throws Exception {
                SQLSmallint value1 = new SQLSmallint(100);
                SQLSmallint value2 = new SQLSmallint(200);
                SQLSmallint value1a = new SQLSmallint();
                SQLSmallint value2a = new SQLSmallint();
                PositionedByteRange range1 = new SimplePositionedMutableByteRange(value1.encodedKeyLength());
                PositionedByteRange range2 = new SimplePositionedMutableByteRange(value2.encodedKeyLength());
                value1.encodeIntoKey(range1, Order.ASCENDING);
                value2.encodeIntoKey(range2, Order.ASCENDING);
                Assert.assertTrue("Positioning is Incorrect", Bytes.compareTo(range1.getBytes(), 0, 9, range2.getBytes(), 0, 9) < 0);
                range1.setPosition(0);
                range2.setPosition(0);
                value1a.decodeFromKey(range1);
                value2a.decodeFromKey(range2);
                Assert.assertEquals("1 incorrect",value1.getInt(),value1a.getInt(),0);
                Assert.assertEquals("2 incorrect",value2.getInt(),value2a.getInt(),0);
        }

        @Test
        public void testColumnStatistics() throws Exception {
                SQLSmallint value1 = new SQLSmallint();
                ItemStatistics stats = new ColumnStatisticsImpl(value1);
                SQLSmallint SQLSmallint;
                for (int i = 1; i<= 10000; i++) {
                        if (i>=5000 && i < 6000)
                                SQLSmallint = new SQLSmallint();
                        else if (i>=1000 && i< 2000)
                                SQLSmallint = new SQLSmallint(1000+i%20);
                        else
                                SQLSmallint = new SQLSmallint(i);
                        stats.update(SQLSmallint);
                }
                stats = serde(stats);
                Assert.assertEquals(1000,stats.nullCount());
                Assert.assertEquals(9000,stats.notNullCount());
                Assert.assertEquals(10000,stats.totalCount());
                Assert.assertEquals(new SQLSmallint(10000),stats.maxValue());
                Assert.assertEquals(new SQLSmallint(1),stats.minValue());
                Assert.assertEquals(1000,stats.selectivity(null));
                Assert.assertEquals(1000,stats.selectivity(new SQLSmallint()));
                Assert.assertEquals(51,stats.selectivity(new SQLSmallint(1010)));
                Assert.assertEquals(1,stats.selectivity(new SQLSmallint(9000)));
                Assert.assertEquals(1000.0d,(double) stats.rangeSelectivity(new SQLSmallint(1000),new SQLSmallint(2000),true,false),RANGE_SELECTIVITY_ERRROR_BOUNDS);
                Assert.assertEquals(500.0d,(double) stats.rangeSelectivity(new SQLSmallint(),new SQLSmallint(500),true,false),RANGE_SELECTIVITY_ERRROR_BOUNDS);
                Assert.assertEquals(4000.0d,(double) stats.rangeSelectivity(new SQLSmallint(5000),new SQLSmallint(),true,false),RANGE_SELECTIVITY_ERRROR_BOUNDS);
        }


}
