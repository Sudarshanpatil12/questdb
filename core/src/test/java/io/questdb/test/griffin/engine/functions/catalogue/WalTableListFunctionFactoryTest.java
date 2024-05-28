/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.test.griffin.engine.functions.catalogue;

import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.SqlException;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.Utf8s;
import io.questdb.test.AbstractCairoTest;
import io.questdb.test.std.TestFilesFacadeImpl;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import static io.questdb.std.Files.SEPARATOR;

public class WalTableListFunctionFactoryTest extends AbstractCairoTest {

    @Test
    public void testWalTablesQueryCache() throws Exception {
        assertMemoryLeak(() -> {
            cloneCreateTable("A", false);
            cloneCreateTable("B", true);
            cloneCreateTable("C", true);

            try (RecordCursorFactory factory = select("wal_tables()")) {
                // RecordCursorFactory could be cached in QueryCache and reused
                // so let's run the query few times using the same factory
                for (int i = 0; i < 5; i++) {
                    try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                        println(factory, cursor);
                        TestUtils.assertEquals("name\tsuspended\twriterTxn\twriterLagTxnCount\tsequencerTxn\terrorCode\terrorTag\terrorMessage\n" +
                                "B\tfalse\t0\t0\t0\tnull\t\t\n" +
                                "C\tfalse\t0\t0\t0\tnull\t\t\n", sink);
                    }
                }
            }
        });
    }

    @Test
    public void testWalTablesSelectAll() throws Exception {
        FilesFacade filesFacade = new TestFilesFacadeImpl() {
            private int attempt = 0;

            @Override
            public int errno() {
                return 888;
            }

            @Override
            public int openRW(LPSZ name, long opts) {
                if (Utf8s.containsAscii(name, "x.d.1") && attempt++ == 0) {
                    return -1;
                }
                return Files.openRW(name, opts);
            }
        };

        assertMemoryLeak(filesFacade, () -> {
            cloneCreateTable("A", false);
            cloneCreateTable("B", true);
            cloneCreateTable("C", true);
            cloneCreateTable("D", true);

            insert("insert into B values (1, 'A', '2022-12-05T01', 'B')");
            compile("update B set x = 101");
            insert("insert into B values (2, 'C', '2022-12-05T02', 'D')");
            insert("insert into C values (1, 'A', '2022-12-05T01', 'B')");
            insert("insert into C values (2, 'C', '2022-12-05T02', 'D')");
            insert("insert into D values (1, 'A', '2022-12-05T01', 'B')");

            drainWalQueue();

            Assert.assertTrue(engine.getTableSequencerAPI().isSuspended(engine.verifyTableName("B")));
            Assert.assertFalse(engine.getTableSequencerAPI().isSuspended(engine.verifyTableName("C")));
            Assert.assertFalse(engine.getTableSequencerAPI().isSuspended(engine.verifyTableName("D")));

            assertSql("name\tsuspended\twriterTxn\twriterLagTxnCount\tsequencerTxn\terrorCode\terrorTag\terrorMessage\n" +
                    "B\ttrue\t1\t0\t3\t888\t\tcould not open read-write [file=" + root + SEPARATOR + "B~2" + SEPARATOR + "2022-12-05" + SEPARATOR + "x.d.1]\n" +
                    "C\tfalse\t2\t0\t2\tnull\t\t\n" +
                    "D\tfalse\t1\t0\t1\tnull\t\t\n", "wal_tables() order by name");

            assertSql("name\tsuspended\twriterTxn\n" +
                    "B\ttrue\t1\n" +
                    "C\tfalse\t2\n" +
                    "D\tfalse\t1\n", "select name, suspended, writerTxn from wal_tables() order by name");

            assertSql("name\tsuspended\twriterTxn\n" +
                    "B\ttrue\t1\n", "select name, suspended, writerTxn from wal_tables() where name = 'B'");
        });
    }

    private void cloneCreateTable(final String tableName, boolean isWal) throws SqlException {
        compile("create table " + tableName + " (" +
                "x long," +
                "sym symbol," +
                "ts timestamp," +
                "sym2 symbol" +
                ") timestamp(ts) partition by DAY" + (isWal ? " WAL" : ""));
    }
}
