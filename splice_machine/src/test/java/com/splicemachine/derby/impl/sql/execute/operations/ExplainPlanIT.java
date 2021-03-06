/*
 * Copyright (c) 2012 - 2017 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.derby.test.framework.*;
import com.splicemachine.test_tools.TableCreator;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.Description;

import java.sql.*;
import java.util.Properties;

import static com.splicemachine.test_tools.Rows.row;
import static com.splicemachine.test_tools.Rows.rows;

/**
 * Created by jyuan on 10/7/14.
 */
public class ExplainPlanIT extends SpliceUnitTest  {

    public static final String CLASS_NAME = ExplainPlanIT.class.getSimpleName().toUpperCase();
    protected static SpliceWatcher spliceClassWatcher = new SpliceWatcher(CLASS_NAME);
    public static final String TABLE_NAME = "A";
    protected static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(CLASS_NAME);

    private static String tableDef = "(I INT)";
    protected static SpliceTableWatcher spliceTableWatcher = new SpliceTableWatcher(TABLE_NAME,CLASS_NAME, tableDef);

    @ClassRule
    public static TestRule chain = RuleChain.outerRule(spliceClassWatcher)
            .around(spliceSchemaWatcher)
            .around(spliceTableWatcher)
            .around(new SpliceDataWatcher(){
                @Override
                protected void starting(Description description){
                    try{
                        PreparedStatement ps=spliceClassWatcher.prepareStatement(format("insert into %s.%s values (?)",CLASS_NAME,TABLE_NAME));
                        for(int i=0;i<10;i++){
                            ps.setInt(1, i);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                        ps = spliceClassWatcher.prepareStatement(
                                format("insert into %s.%s select * from %s.%s", CLASS_NAME,TABLE_NAME, CLASS_NAME,TABLE_NAME));
                        for (int i = 0; i < 11; ++i) {
                            ps.execute();
                        }
                        ps = spliceClassWatcher.prepareStatement(format("analyze schema %s", CLASS_NAME));
                        ps.execute();
                    }catch(Exception e){
                        throw new RuntimeException(e);
                    }finally{
                        spliceClassWatcher.closeAll();
                    }
                }

            });
    @Rule
    public SpliceWatcher methodWatcher = new SpliceWatcher(CLASS_NAME);

    @BeforeClass
    public static void createTables() throws Exception {
        Connection conn = spliceClassWatcher.getOrCreateConnection();

        new TableCreator(conn)
                .withCreate("create table t1 (c1 int, c2 int)")
                .create();

        new TableCreator(conn)
                .withCreate("create table t2 (c1 int, c2 int)")
                .create();

        new TableCreator(conn)
                .withCreate("create table t3 (c1 int, c2 int)")
                .create();

        new TableCreator(conn)
                .withCreate("create table t4 (a4 int, b4 int, c4 int, primary key(a4))")
                .withInsert("insert into t4 values(?,?,?)")
                .withRows(rows(
                        row(1,1,1),
                        row(2,2,2),
                        row(3,3,3),
                        row(4,4,4),
                        row(5,5,5),
                        row(6,6,6),
                        row(7,7,7),
                        row(8,8,8),
                        row(9,9,9),
                        row(10,10,10)))
                .create();
        int factor = 10;
        for (int i = 1; i <= 12; i++) {
            spliceClassWatcher.executeUpdate(format("insert into t4 select a4+%d, b4,c4 from t4", factor));
            factor = factor * 2;
        }

        new TableCreator(conn)
                .withCreate("create table t5 (a5 int, b5 int, c5 int, d5 int, e5 int, primary key(a5, b5, c5))")
                .withIndex("create index ix_t5 on t5(b5, c5, d5)")
                .withInsert("insert into t5 values(?,?,?,?,?)")
                .withRows(rows(
                        row(1,1,1,1,1),
                        row(2,2,2,2,2),
                        row(3,3,3,3,3),
                        row(4,4,4,4,4),
                        row(5,5,5,5,5),
                        row(6,6,6,6,6),
                        row(7,7,7,7,7),
                        row(8,8,8,8,8),
                        row(9,9,9,9,9),
                        row(10,10,10,10,10)))
                .create();

        for (int i = 1; i <= 12; i++) {
            spliceClassWatcher.executeUpdate(format("insert into t5 select a5+%d, b5,c5, d5, e5 from t5", factor));
            factor = factor * 2;
        }
    }

    @Test
    public void testExplainSelect() throws Exception {
        ResultSet rs  = methodWatcher.executeQuery(
                String.format("explain select * from %s", this.getTableReference(TABLE_NAME)));

        int count = 0;
        while (rs.next()) {
            ++count;
        }
        Assert.assertTrue(count>0);
    }

    @Test
    public void testExplainUpdate() throws Exception {
        ResultSet rs  = methodWatcher.executeQuery(
                String.format("explain update %s set i = 0 where i = 1", this.getTableReference(TABLE_NAME)));

        int count = 0;
        while (rs.next()) {
            ++count;
        }
        Assert.assertTrue(count>0);
    }

    @Test
    public void testExplainDelete() throws Exception {
        ResultSet rs  = methodWatcher.executeQuery(
                String.format("explain delete from %s where i = 1", this.getTableReference(TABLE_NAME)));

        int count = 0;
        while (rs.next()) {
            ++count;
        }
        Assert.assertTrue(count>0);
    }

    @Test
    public void testExplainTwice() throws Exception {
        ResultSet rs  = methodWatcher.executeQuery(
                String.format("-- some comments \n explain\nupdate %s set i = 0 where i = 1", this.getTableReference(TABLE_NAME)));
        int count1 = 0;
        while (rs.next()) {
            ++count1;
        }
        rs.close();
        rs  = methodWatcher.executeQuery(
                String.format("-- some comments \n explain\nupdate %s set i = 0 where i = 1", this.getTableReference(TABLE_NAME)));
        int count2 = 0;
        while (rs.next()) {
            ++count2;
        }
        Assert.assertTrue(count1 == count2);
    }

    @Test
    public void testUseSpark() throws Exception {
        String sql = format("explain select * from %s.%s --SPLICE-PROPERTIES useSpark=false", CLASS_NAME, TABLE_NAME);
        ResultSet rs  = methodWatcher.executeQuery(sql);
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan contains useSpark=false", rs.getString(1).contains("engine=control"));

        sql = format("explain select * from %s.%s", CLASS_NAME, TABLE_NAME);
        rs  = methodWatcher.executeQuery(sql);
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan contains useSpark=true", rs.getString(1).contains("engine=Spark"));

    }

    @Test
    public void testSparkConnection() throws Exception {
        String url = "jdbc:splice://localhost:1527/splicedb;create=true;user=splice;password=admin;useSpark=true";
        Connection connection = DriverManager.getConnection(url, new Properties());
        connection.setSchema(CLASS_NAME.toUpperCase());
        Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery("explain select * from A");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan contains useSpark=false", rs.getString(1).contains("engine=Spark"));

    }

    @Test
    public void testControlConnection() throws Exception {
        String url = "jdbc:splice://localhost:1527/splicedb;create=true;user=splice;password=admin;useSpark=false";
        Connection connection = DriverManager.getConnection(url, new Properties());
        connection.setSchema(CLASS_NAME.toUpperCase());
        Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery("explain select * from A");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan contains useSpark=false", rs.getString(1).contains("engine=control"));
    }

    @Test
    public void testControlQuery() throws Exception {
        String url = "jdbc:splice://localhost:1527/splicedb;user=splice;password=admin";
        Connection connection = DriverManager.getConnection(url, new Properties());
        connection.setSchema(CLASS_NAME.toUpperCase());
        Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery("explain select * from A --SPLICE-PROPERTIES useSpark=false");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan contains useSpark=false", rs.getString(1).contains("engine=control"));
    }

    //DB-5743
    @Test
    public void testPredicatePushDownAfterOJ2IJ() throws Exception {

        String query =
                "explain select count(*) from t1 a\n" +
                "left join t2 b on a.c1=b.c1\n" +
                "left join t2 c on b.c2=c.c2\n" +
                "where a.c2 not in (1, 2, 3) and c.c1 > 0";
        
        // Make sure predicate on a.c2 is pushed down to the base table scan
        String predicate = "preds=[(A.C2[0:2] <> 1),(A.C2[0:2] <> 2),(A.C2[0:2] <> 3)]";
        ResultSet rs  = methodWatcher.executeQuery(query);
        while(rs.next()) {
            String s = rs.getString(1);
            if (s.contains(predicate)) {
                Assert.assertTrue(s, s.contains("TableScan"));
            }
        }
    }

    @Test
    public void testChoiceOfDatasetProcessorType() throws Exception {
        // collect stats
        methodWatcher.executeQuery(format("analyze table %s.t4", CLASS_NAME));
        //test select with single table
        // PK access path, we should pick control path
        ResultSet rs = methodWatcher.executeQuery("explain select * from t4 where a4=10000");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan to pick control path", rs.getString(1).contains("engine=control"));

        // full table scan, we should go for spark path as all rows need to be accessed, even though the output row count
        // is small after applying the predicate
        rs = methodWatcher.executeQuery("explain select * from t4 where b4=10000");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan to pick spark path", rs.getString(1).contains("engine=Spark"));

        // test join case, base table scan may not exceeds the rowcount limit of 20000, if the join result rowcount exceeds this
        // limit, we still need to go for Spark path
        rs = methodWatcher.executeQuery("explain select * from t4 as X, t4 as Y where X.a4>30000 and Y.a4 >30000 and X.b4=Y.b4");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan to pick spark path", rs.getString(1).contains("engine=Spark"));

        rs = methodWatcher.executeQuery("explain select * from t4 as X, t4 as Y where X.a4=30000 and Y.a4 =30000 and X.b4=Y.b4");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("expect explain plan to pick control path", rs.getString(1).contains("engine=control"));
    }

    @Test
    public void testDefaultSelectivityFactorHint() throws Exception {
        // collect stats
        methodWatcher.executeQuery(format("analyze table %s.t5", CLASS_NAME));
        methodWatcher.executeQuery(format("analyze table %s.t4", CLASS_NAME));

        double selectivity[] = {1, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7};
        String engine[] = {"Spark", "Spark", "control", "control", "control", "control", "control", "control"};
        int rowCount[] = {900000, 90000, 9000, 900, 90, 9, 1,1};

        /* Q1: test single table case on PK */
        ResultSet rs;
        for (int i=0; i < selectivity.length; i++) {
            rs = methodWatcher.executeQuery(format("explain select * from t5 --splice-properties useDefaultRowCount=1000000, defaultSelectivityFactor=%.8f\n where a5=100001", selectivity[i]));
            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:expect explain plan to pick %s path", i, engine[i]), rs.getString(1).contains(format("engine=%s", engine[i])));
            //skip the next step to get to the TableScan step
            Assert.assertTrue(rs.next());

            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:Expected TableScan",i), rs.getString(1).contains("TableScan"));
            Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount[i]), rs.getString(1).contains(format("outputRows=%d",rowCount[i])));
            rs.close();
        }

        /* test with stats */
        rs = methodWatcher.executeQuery("explain select * from t5 where a5=100001");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("With stats, expect explain plan to pick control path", rs.getString(1).contains("engine=control"));
        //skip the next step to get to the TableScan step
        Assert.assertTrue(rs.next());

        Assert.assertTrue(rs.next());
        Assert.assertTrue("Expected TableScan", rs.getString(1).contains("TableScan"));
        Assert.assertTrue("With stats, outputRows is expected to be 1", rs.getString(1).contains("outputRows=1"));
        rs.close();

        /* Q2: test the switch from table scan to index scan */
        String engine2[] = {"Spark", "Spark", "Spark", "control", "control", "control", "control", "control"};
        int rowCount2[] = {1000000, 1000000, 1000000, 27, 1, 1, 1, 1};
        for (int i=0; i < selectivity.length; i++) {
            rs = methodWatcher.executeQuery(format("explain select * from t5 --splice-properties useDefaultRowCount=1000000, defaultSelectivityFactor=%.8f\n where b5=100001 and c5=3", selectivity[i]));
            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:expect explain plan to pick %s path", i, engine[i]), rs.getString(1).contains(format("engine=%s", engine2[i])));
            if (i< 3) {
                //selectivity is not small enough to make index lookup plan win, so we expect TableScan plan
                //skip the next step to get to the TableScan step
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected TableScan",i), rs.getString(1).contains("TableScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d",rowCount2[i])));
            } else {
                // index lookup plan should win
                //skip the next two steps to get to the IndexScan step
                Assert.assertTrue(rs.next());
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected IndexScan", i), rs.getString(1).contains("IndexScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d", rowCount2[i])));
            }
            rs.close();
        }

        /* test with stats */
        rs = methodWatcher.executeQuery("explain select * from t5 where b5=100001 and c5=3");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("With stats, expect explain plan to pick control path", rs.getString(1).contains("engine=control"));
        //skip the next two steps to get to the IndexScan step
        Assert.assertTrue(rs.next());
        Assert.assertTrue(rs.next());

        Assert.assertTrue(rs.next());
        Assert.assertTrue("Expected IndexScan", rs.getString(1).contains("IndexScan"));
        Assert.assertTrue("With stats, outputRows is expected to be 1", rs.getString(1).contains("scannedRows=1"));
        rs.close();

        /* Q3: test join case */
        String engine3[] = {"Spark", "Spark", "Spark", "control", "control", "control", "control", "control"};
        int rowCount3[] = {1000000, 1000000, 1000000, 27, 1, 1, 1, 1};
        String join3[] = {"BroadcastJoin", "BroadcastJoin", "BroadcastJoin",
                "NestedLoopJoin", "NestedLoopJoin", "NestedLoopJoin", "NestedLoopJoin", "NestedLoopJoin"};
        for (int i=0; i < selectivity.length; i++) {
            rs = methodWatcher.executeQuery(format("explain select * from --splice-properties joinOrder=fixed\n" +
                    "t5 --splice-properties useDefaultRowCount=1000000, defaultSelectivityFactor=%.8f\n " +
                    ", t4 where b5=100001 and c5=3 and d5=a4", selectivity[i]));
            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:expect explain plan to pick %s path", i, engine3[i]), rs.getString(1).contains(format("engine=%s", engine3[i])));
            // skip ScrollInsensitive step
            Assert.assertTrue(rs.next());
            if (i< 3) {
                //selectivity is not small enough to make index lookup plan win, so we expect TableScan plan
                // with large input table rows, broadcast join should win
                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected %s",i, join3[i]), rs.getString(1).contains(join3[i]));
                //skip the next step to get to the TableScan step for T5
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected TableScan",i), rs.getString(1).contains("TableScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d",rowCount3[i])));
            } else {
                // index lookup plan should win
                // with small input table rows, nested loop join should win
                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected %s",i, join3[i]), rs.getString(1).contains(join3[i]));

                //skip the next two steps to get to the IndexScan step
                Assert.assertTrue(rs.next());
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected IndexScan", i), rs.getString(1).contains("IndexScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d", rowCount3[i])));
            }
            rs.close();
        }

        /* test with stats */
        rs = methodWatcher.executeQuery("explain select * from --splice-properties joinOrder=fixed\n t5, t4 where b5=100001 and c5=3 and d5=a4");
        Assert.assertTrue(rs.next());
        Assert.assertTrue("With stats, expect explain plan to pick control path", rs.getString(1).contains("engine=control"));
        // skip ScrollInsensitive step
        Assert.assertTrue(rs.next());

        Assert.assertTrue(rs.next());
        Assert.assertTrue("Expected NestedLoopJoin", rs.getString(1).contains("NestedLoopJoin"));
        //skip the next two steps to get to the IndexScan step
        Assert.assertTrue(rs.next());
        Assert.assertTrue(rs.next());

        Assert.assertTrue(rs.next());
        Assert.assertTrue("Expected IndexScan", rs.getString(1).contains("IndexScan"));
        Assert.assertTrue("With stats, outputRows is expected to be 1", rs.getString(1).contains("scannedRows=1"));
        rs.close();

    }

    @Test
    @Ignore("DB-7058")
    public void testDefaultSelectivityFactorHintAtSessionLevel() throws Exception {
        // collect stats
        methodWatcher.executeQuery(format("analyze table %s.t5", CLASS_NAME));
        methodWatcher.executeQuery(format("analyze table %s.t4", CLASS_NAME));

        double selectivity[] = {1, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7};
        String engine[] = {"Spark", "Spark", "control", "control", "control", "control", "control", "control"};
        int rowCount[] = {900000, 90000, 9000, 900, 90, 9, 1,1};

        String engine2[] = {"Spark", "Spark", "Spark", "control", "control", "control", "control", "control"};
        int rowCount2[] = {1000000, 1000000, 1000000, 27, 1, 1, 1, 1};

        String engine3[] = {"Spark", "Spark", "Spark", "control", "control", "control", "control", "control"};
        int rowCount3[] = {1000000, 1000000, 1000000, 27, 1, 1, 1, 1};
        String join3[] = {"BroadcastJoin", "BroadcastJoin", "BroadcastJoin",
                "BroadcastJoin", "NestedLoopJoin", "NestedLoopJoin", "NestedLoopJoin", "NestedLoopJoin"};

        for (int i=0; i<selectivity.length; i++) {
            String url = format("jdbc:splice://localhost:1527/splicedb;create=true;user=splice;password=admin;defaultSelectivityFactor=%.8f", selectivity[i]);
            Connection connection = DriverManager.getConnection(url, new Properties());
            connection.setSchema(CLASS_NAME.toUpperCase());
            Statement s = connection.createStatement();

            /* Q1: test single table case on PK */
            ResultSet rs = s.executeQuery("explain select * from t5 --splice-properties useDefaultRowCount=1000000\n where a5=100001");
            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:expect explain plan to pick %s path", i, engine[i]), rs.getString(1).contains(format("engine=%s", engine[i])));
            //skip the next step to get to the TableScan step
            Assert.assertTrue(rs.next());

            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:Expected TableScan",i), rs.getString(1).contains("TableScan"));
            Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount[i]), rs.getString(1).contains(format("outputRows=%d",rowCount[i])));
            rs.close();

            /* Q2: test the switch from table scan to index scan */
            rs = s.executeQuery("explain select * from t5 --splice-properties useDefaultRowCount=1000000\n where b5=100001 and c5=3");
            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:expect explain plan to pick %s path", i, engine[i]), rs.getString(1).contains(format("engine=%s", engine2[i])));
            if (i< 3) {
                //selectivity is not small enough to make index lookup plan win, so we expect TableScan plan
                //skip the next step to get to the TableScan step
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected TableScan",i), rs.getString(1).contains("TableScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d",rowCount2[i])));
            } else {
                // index lookup plan should win
                //skip the next two steps to get to the IndexScan step
                Assert.assertTrue(rs.next());
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected IndexScan", i), rs.getString(1).contains("IndexScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d", rowCount2[i])));
            }
            rs.close();


            /* Q3: test join case */
            rs = s.executeQuery("explain select * from --splice-properties joinOrder=fixed\n" +
                    "t5 --splice-properties useDefaultRowCount=1000000\n" +
                    ", t4 where b5=100001 and c5=3 and d5=a4");
            Assert.assertTrue(rs.next());
            Assert.assertTrue(format("Iteration [%d]:expect explain plan to pick %s path", i, engine3[i]), rs.getString(1).contains(format("engine=%s", engine3[i])));
            // skip ScrollInsensitive step
            Assert.assertTrue(rs.next());
            if (i< 3) {
                //selectivity is not small enough to make index lookup plan win, so we expect TableScan plan
                // with large input table rows, broadcast join should win
                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected %s",i, join3[i]), rs.getString(1).contains(join3[i]));
                //skip the next step to get to the TableScan step for T5
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected TableScan",i), rs.getString(1).contains("TableScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d",rowCount3[i])));
            } else {
                // index lookup plan should win
                // with small input table rows, nested loop join should win
                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected %s",i, join3[i]), rs.getString(1).contains(join3[i]));

                //skip the next two steps to get to the IndexScan step
                Assert.assertTrue(rs.next());
                Assert.assertTrue(rs.next());

                Assert.assertTrue(rs.next());
                Assert.assertTrue(format("Iteration [%d]:Expected IndexScan", i), rs.getString(1).contains("IndexScan"));
                Assert.assertTrue(format("Iteration [%d]: outputRows is expected to be: %d", i, rowCount2[i]), rs.getString(1).contains(format("scannedRows=%d", rowCount3[i])));
            }
            rs.close();

            s.close();
            connection.close();
        }
    }
}
