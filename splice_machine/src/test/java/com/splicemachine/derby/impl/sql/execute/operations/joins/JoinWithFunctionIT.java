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

package com.splicemachine.derby.impl.sql.execute.operations.joins;

import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.derby.test.framework.SpliceUnitTest;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.homeless.TestUtils;
import com.splicemachine.test_tools.TableCreator;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.sparkproject.guava.collect.Lists;

import java.sql.ResultSet;
import java.util.Collection;

import static com.splicemachine.test_tools.Rows.row;
import static com.splicemachine.test_tools.Rows.rows;
import static org.junit.Assert.assertEquals;

/**
 * Created by jyuan on 5/18/16.
 */
@RunWith(Parameterized.class)
public class JoinWithFunctionIT extends SpliceUnitTest {
    private static final String SCHEMA = JoinWithFunctionIT.class.getSimpleName().toUpperCase();
    private static SpliceWatcher spliceClassWatcher = new SpliceWatcher(SCHEMA);

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        Collection<Object[]> params = Lists.newArrayListWithCapacity(4);
        params.add(new Object[]{"NESTEDLOOP"});
        params.add(new Object[]{"SORTMERGE"});
        params.add(new Object[]{"BROADCAST"});
        return params;
    }
    private String joinStrategy;

    public JoinWithFunctionIT(String joinStrategy) {
        this.joinStrategy = joinStrategy;
    }

    @ClassRule
    public static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(SCHEMA);

    @Rule
    public SpliceWatcher methodWatcher = new SpliceWatcher(SCHEMA);

    @BeforeClass
    public static void createSharedTables() throws Exception {
        new TableCreator(spliceClassWatcher.getOrCreateConnection())
                .withCreate("create table a (i int, j int)")
                .withInsert("insert into a values(?,?)")
                .withRows(rows(row(1, 1), row(2, 2), row(-3, 3), row(-4, 4))).create();

        new TableCreator(spliceClassWatcher.getOrCreateConnection())
                .withCreate("create table b (i int, j int)")
                .withInsert("insert into b values(?,?)")
                .withRows(rows(row(-1, 1), row(-2, 2), row(3, 3), row(4, 4))).create();

        new TableCreator(spliceClassWatcher.getOrCreateConnection())
                .withCreate("create table c (d double, j int)")
                .withInsert("insert into c values(?,?)")
                .withRows(rows(row(1.1, 1), row(2.2, 2), row(-3.3, 3), row(-4.4, 4))).create();

        new TableCreator(spliceClassWatcher.getOrCreateConnection())
                .withCreate("CREATE TABLE TABLE_A (ID BIGINT,TYPE VARCHAR(325))")
                .withInsert("insert into table_a values(?,?)")
                .withRows(rows(row(1,"A"))).create();

        new TableCreator(spliceClassWatcher.getOrCreateConnection())
                .withCreate("CREATE TABLE TABLE_B(ID BIGINT,BD SMALLINT)")
                .withInsert("insert into table_b values(?,?)")
                .withRows(rows(row(1,1))).create();

        new TableCreator(spliceClassWatcher.getOrCreateConnection())
                .withCreate("CREATE TABLE TABLE_C (ACCOUNT VARCHAR(75),CATEGORY VARCHAR(75),SUB_CATEGORY VARCHAR(75),SOURCE VARCHAR(75),TYPE VARCHAR(500))")
                .withInsert("insert into table_c values(?,?,?,?,?)")
                .withRows(rows(row("ACCOUNT", "CATEGORY", "SUB_CATEGORY", "PBD_SMARTWORKS", "A"))).create();
    }

    @Test
    public void testUnaryFunction() throws Exception {
        String sql = String.format("select * from --SPLICE-PROPERTIES joinOrder=FIXED\n" +
                "a\n" +
                ", b  --SPLICE-PROPERTIES joinStrategy=%s\n" +
                "where abs(a.i)=abs(b.i) order by a.i", joinStrategy);
        ResultSet rs = methodWatcher.executeQuery(sql);
        String expected =
                "I | J | I | J |\n" +
                "----------------\n" +
                "-4 | 4 | 4 | 4 |\n" +
                "-3 | 3 | 3 | 3 |\n" +
                " 1 | 1 |-1 | 1 |\n" +
                " 2 | 2 |-2 | 2 |";
        assertEquals("\n" + sql + "\n", expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        rs.close();
    }

    @Test
    public void testJavaFunction() throws Exception {
        String sql = String.format("select a.i, a.j, c.d from --SPLICE-PROPERTIES joinOrder=FIXED\n" +
                "a\n" +
                ", c --SPLICE-PROPERTIES joinStrategy=%s\n" +
                " where double(a.i)=floor(c.d) order by a.i", joinStrategy);
        ResultSet rs = methodWatcher.executeQuery(sql);

        String expected =
                "I | J |  D  |\n" +
                "--------------\n" +
                "-4 | 4 |-3.3 |\n" +
                " 1 | 1 | 1.1 |\n" +
                " 2 | 2 | 2.2 |";

        String s = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
        assertEquals(s, expected, s);
        rs.close();
    }

    @Test
    public void testBinaryFunction() throws Exception {
        String sql = String.format("select * from --SPLICE-PROPERTIES joinOrder=FIXED\n" +
                "a\n" +
                ", b  --SPLICE-PROPERTIES joinStrategy=%s\n" +
                "where mod(abs(a.i), 10) = abs(b.i) order by a.i", joinStrategy);
        ResultSet rs = methodWatcher.executeQuery(sql);

        String expected =
                "I | J | I | J |\n" +
                "----------------\n" +
                "-4 | 4 | 4 | 4 |\n" +
                "-3 | 3 | 3 | 3 |\n" +
                " 1 | 1 |-1 | 1 |\n" +
                " 2 | 2 |-2 | 2 |";

        String s = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
        assertEquals(s, expected, s);
        rs.close();
    }

    @Test
    public void testBinaryArithmeticOperator() throws Exception {
        String sql = String.format("select * from --SPLICE-PROPERTIES joinOrder=FIXED\n" +
                "a\n" +
                ", b  --SPLICE-PROPERTIES joinStrategy=%s\n" +
                ", c --SPLICE-PROPERTIES joinStrategy=%s\n" +
                "where a.j+1 = b.j+1 and b.j+1 = c.j+1 order by a.i", joinStrategy, joinStrategy);
        ResultSet rs = methodWatcher.executeQuery(sql);
        String s = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
        rs.close();
        String expected =
                "I | J | I | J |  D  | J |\n" +
                "--------------------------\n" +
                "-4 | 4 | 4 | 4 |-4.4 | 4 |\n" +
                "-3 | 3 | 3 | 3 |-3.3 | 3 |\n" +
                " 1 | 1 |-1 | 1 | 1.1 | 1 |\n" +
                " 2 | 2 |-2 | 2 | 2.2 | 2 |";
        assertEquals(s, expected, s);
    }

    @Test
    public void testSelfJoinWithBinaryArithmeticOperator() throws Exception {
        String sql = String.format("select * from --SPLICE-PROPERTIES joinOrder=fixed\n" +
                " a t1 --SPLICE-PROPERTIES joinStrategy=%s \n" +
                " , a t2 where t1.i*2=t2.j order by t1.i", joinStrategy);

        ResultSet rs = methodWatcher.executeQuery(sql);
        String s = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
        rs.close();
        String expected =
                "I | J | I | J |\n" +
                "----------------\n" +
                " 1 | 1 | 2 | 2 |\n" +
                " 2 | 2 |-4 | 4 |";
        assertEquals(s, expected, s);
    }

    @Test
    public void testLeftOuterJoin() throws Exception {
        String sqlText = "SELECT C.ACCOUNT ,C.CATEGORY, C.SUB_CATEGORY\n" +
                "FROM TABLE_A A\n" +
                "LEFT JOIN TABLE_B B\n" +
                "ON A.ID = B.ID\n" +
                "LEFT JOIN TABLE_C C\n" +
                "ON C.SOURCE= 'PBD_SMARTWORKS'\n" +
                "and C.TYPE=UPPER(A.type)\n" +
                "WHERE B.BD = 1";

        ResultSet rs = methodWatcher.executeQuery(sqlText);
        String s = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
        rs.close();
        String expected =
                "ACCOUNT |CATEGORY |SUB_CATEGORY |\n" +
                "----------------------------------\n" +
                " ACCOUNT |CATEGORY |SUB_CATEGORY |";
        assertEquals(s, expected, s);
    }
}
