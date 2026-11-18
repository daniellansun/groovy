/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.groovy.ginq.provider.sql

import groovy.sql.Sql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail

/**
 * Runs one GINQ clause shape on the collection provider and on H2
 * ({@code native-sql} and {@code jooq-sql}) and requires the frozen
 * window results to agree.
 */
class GinqWindowProviderAgreementTest {
    private Sql db
    private List<Map> oneRow
    private List<Map> running
    private List<Map> scores
    private List<Map> blanks
    private List<Map> seq

    @BeforeEach
    void setUp() {
        db = GinqSqlTestSupport.newPopulatedDb()
        oneRow = rows(7)
        running = rows(10, 20, 30)
        scores = rows(60, 60, 80, 80, 100)
        blanks = [[n: null], [n: null]]
        seq = rows(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        store('one_row', oneRow)
        store('running', running)
        store('scores', scores)
        store('blanks', blanks)
        store('seq', seq)
    }

    @AfterEach
    void tearDown() {
        GinqSqlTestSupport.closeDb(db)
    }

    @Test
    void testOneRowNumberingIsZeroBased() {
        def expected = [[0, 7]]
        def collection = GQ {
            from r in oneRow
            orderby r.n
            select (rowNumber() over(orderby r.n)) as rn, (nthValue(r.n, 0) over(orderby r.n)) as nth
        }.toList()
        def nativeRows = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'one_row'
            orderby r.n
            select (rowNumber() over(orderby r.n)) as rn, (nthValue(r.n, 0) over(orderby r.n)) as nth
        }
        def jooqRows = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'one_row'
            orderby r.n
            select (rowNumber() over(orderby r.n)) as rn, (nthValue(r.n, 0) over(orderby r.n)) as nth
        }
        agree(expected, collection)
        agree(expected, nativeRows.collect { [it.rn, it.nth] })
        agree(expected, jooqRows.collect { [it.rn, it.nth] })
    }

    @Test
    void testNtileZeroStaysOnBucketZero() {
        def expected = seq.collect { [it.n, 0] }
        def collection = GQ {
            from r in seq
            orderby r.n
            select r.n, (ntile(0) over(orderby r.n)) as bucket
        }.toList()
        def nativeRows = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'seq'
            orderby r.n
            select r.n, (ntile(0) over(orderby r.n)) as bucket
        }
        def jooqRows = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'seq'
            orderby r.n
            select r.n, (ntile(0) over(orderby r.n)) as bucket
        }
        agree(expected, collection)
        agree(expected, nativeRows.collect { [it.n, it.bucket] })
        agree(expected, jooqRows.collect { [it.n, it.bucket] })
    }

    @Test
    void testNtileUsesTheZeroBasedFormula() {
        def expected = seq.collect { row -> [row.n, (4 * (row.n - 1)).intdiv(10)] }
        def collection = GQ {
            from r in seq
            orderby r.n
            select r.n, (ntile(4) over(orderby r.n)) as bucket
        }.toList()
        def nativeRows = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'seq'
            orderby r.n
            select r.n, (ntile(4) over(orderby r.n)) as bucket
        }
        def jooqRows = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'seq'
            orderby r.n
            select r.n, (ntile(4) over(orderby r.n)) as bucket
        }
        agree(expected, collection)
        agree(expected, nativeRows.collect { [it.n, it.bucket] })
        agree(expected, jooqRows.collect { [it.n, it.bucket] })
    }

    @Test
    void testOrderByWithoutAFrameIsTheWholePartition() {
        def expected = [[10, 60, 20], [20, 60, 20], [30, 60, 20]]
        def collection = GQ {
            from r in running
            orderby r.n
            select r.n, (sum(r.n) over(orderby r.n)) as total, (avg(r.n) over(orderby r.n)) as mean
        }.toList()
        def nativeRows = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'running'
            orderby r.n
            select r.n, (sum(r.n) over(orderby r.n)) as total, (avg(r.n) over(orderby r.n)) as mean
        }
        def jooqRows = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'running'
            orderby r.n
            select r.n, (sum(r.n) over(orderby r.n)) as total, (avg(r.n) over(orderby r.n)) as mean
        }
        agree(expected, collection)
        agree(expected, nativeRows.collect { [it.n, it.total, it.mean] })
        agree(expected, jooqRows.collect { [it.n, it.total, it.mean] })
        assert expected.every { it[1] == 60 }
    }

    @Test
    void testAllNullWindowSumIsZero() {
        def expected = [[0], [0]]
        def collection = GQ {
            from r in blanks
            select (sum(r.n) over()) as total
        }.toList()
        def nativeRows = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'blanks'
            select (sum(r.n) over()) as total
        }
        def jooqRows = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'blanks'
            select (sum(r.n) over()) as total
        }
        agree(expected, collection)
        agree(expected, nativeRows.collect { [it.total] })
        agree(expected, jooqRows.collect { [it.total] })
    }

    @Test
    void testPercentRankAgreesOnTheFrozenFormula() {
        def single = GQ {
            from r in oneRow
            select (percentRank() over(orderby r.n)) as pct
        }.toList()
        def nativeSingle = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'one_row'
            select (percentRank() over(orderby r.n)) as pct
        }
        def jooqSingle = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'one_row'
            select (percentRank() over(orderby r.n)) as pct
        }
        agree([[1]], single)
        agree([[1]], nativeSingle.collect { [it.pct] })
        agree([[1]], jooqSingle.collect { [it.pct] })

        // ranks 1, 1, 3, 3, 5 over 5 rows: (rank - 1) / (size - 1)
        def expected = [[60, 0], [60, 0], [80, 0.5], [80, 0.5], [100, 1]]
        def collection = GQ {
            from r in scores
            orderby r.n
            select r.n, (percentRank() over(orderby r.n)) as pct
        }.toList()
        def nativeRows = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'scores'
            orderby r.n
            select r.n, (percentRank() over(orderby r.n)) as pct
        }
        def jooqRows = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'scores'
            orderby r.n
            select r.n, (percentRank() over(orderby r.n)) as pct
        }
        agree(expected, collection)
        agree(expected, nativeRows.collect { [it.n, it.pct] })
        agree(expected, jooqRows.collect { [it.n, it.pct] })
    }

    @Test
    void testEmptyFrameAverageStaysNull() {
        def expected = [[null], [null], [null]]
        def collection = GQ {
            from r in running
            orderby r.n
            select (avg(r.n) over(orderby r.n rows 5, 6)) as mean
        }.toList()
        def nativeRows = GQL(provider: 'native-sql', dataSource: db) {
            from r in 'running'
            orderby r.n
            select (avg(r.n) over(orderby r.n rows 5, 6)) as mean
        }
        def jooqRows = GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
            from r in 'running'
            orderby r.n
            select (avg(r.n) over(orderby r.n rows 5, 6)) as mean
        }
        agree(expected, collection)
        agree(expected, nativeRows.collect { [it.mean] })
        agree(expected, jooqRows.collect { [it.mean] })
    }

    @Test
    void testRankFrameIsHonoredInMemoryAndRejectedBySql() {
        def framed = GQ {
            from r in running
            orderby r.n
            select r.n, (rank() over(orderby r.n rows 0, 0)) as rk
        }.toList()
        def plain = GQ {
            from r in running
            orderby r.n
            select r.n, (rank() over(orderby r.n)) as rk
        }.toList()
        agree([[10, 1], [20, 1], [30, 1]], framed)
        agree([[10, 1], [20, 2], [30, 3]], plain)

        def numbered = GQ {
            from r in running
            orderby r.n
            select r.n, (rowNumber() over(orderby r.n rows 0, 0)) as rn
        }.toList()
        agree([[10, 0], [20, 1], [30, 2]], numbered)

        def nativeRank = shouldFail '''
            GQL(provider: 'native-sql', dataSource: db) {
                from r in 'running'
                select (rank() over(orderby r.n rows 0, 0))
            }
        '''
        assert nativeRank.message.contains('does not accept')
        assert nativeRank.message.contains('GINQ applies that frame')
        def jooqRank = shouldFail '''
            GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
                from r in 'running'
                select (rank() over(orderby r.n rows 0, 0))
            }
        '''
        assert jooqRank.message.contains('does not accept')

        def nativeNumber = shouldFail '''
            GQL(provider: 'native-sql', dataSource: db) {
                from r in 'running'
                select (rowNumber() over(orderby r.n rows 0, 0))
            }
        '''
        assert nativeNumber.message.contains('does not accept')
        assert !nativeNumber.message.contains('GINQ applies that frame')
    }

    @Test
    void testRankWithoutOrderByIsNullInMemoryAndRejectedBySql() {
        def collection = GQ {
            from r in running
            select (rank() over()) as rk,
                    (denseRank() over()) as dense,
                    (percentRank() over()) as pct,
                    (cumeDist() over()) as cume
        }.toList()
        agree([[null, null, null, null], [null, null, null, null], [null, null, null, null]], collection)

        def err = shouldFail '''
            GQL(provider: 'native-sql', dataSource: db) {
                from r in 'running'
                select (rank() over())
            }
        '''
        assert err.message.contains('needs `orderby`')
        def jooqErr = shouldFail '''
            GQL(provider: 'jooq-sql', dialect: 'H2', dataSource: db) {
                from r in 'running'
                select (percentRank() over(partitionby r.n))
            }
        '''
        assert jooqErr.message.contains('needs `orderby`')
    }

    private void store(String name, List<Map> values) {
        db.execute "create table ${name} (n int)".toString()
        values.each { row ->
            db.execute "insert into ${name} (n) values (?)", [row.n]
        }
    }

    private static List<Map> rows(int... values) {
        values.collect { [n: it] }
    }

    private static void agree(List expected, List actual) {
        assert expected.size() == actual.size()
        expected.eachWithIndex { Object want, int i ->
            same(want, actual[i])
        }
    }

    private static void same(Object want, Object got) {
        if (want instanceof List) {
            List wanted = (List) want
            List found = got instanceof List ? (List) got : got.toList()
            assert wanted.size() == found.size()
            wanted.eachWithIndex { Object cell, int j -> same(cell, found[j]) }
            return
        }
        if (want == null) {
            assert got == null
            return
        }
        if (want instanceof Number) {
            assert got instanceof Number
            assert (((Number) want) as BigDecimal).subtract(((Number) got) as BigDecimal).abs() < 0.0000001G
            return
        }
        assert want == got
    }
}
