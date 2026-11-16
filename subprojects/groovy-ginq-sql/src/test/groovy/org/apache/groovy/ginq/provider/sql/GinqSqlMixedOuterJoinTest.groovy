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

import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Mixed-mode outer joins must expose an unmatched alias as null, the same
 * way the collection provider does, including when later clauses stay in memory.
 */
class GinqSqlMixedOuterJoinTest {
    private Sql db

    @BeforeEach
    void setUp() {
        db = GinqSqlTestSupport.newPopulatedDb()
        db.execute("insert into departments (id, name) values (9, 'Empty')")
        db.execute("insert into departments (id, name) values (10, 'Vacant')")
    }

    @AfterEach
    void tearDown() {
        GinqSqlTestSupport.closeDb(db)
    }

    @Test
    void testLeftJoinUnmatchedAliasIsNull() {
        def employees = db.rows('select * from employees')
        def departments = db.rows('select * from departments')
        def memory = GQL {
            from e in employees
            leftjoin d in departments on e.deptId == d.id
            orderby e.name, (d == null ? 'missing' : d.name)
            select e.name, (d == null ? 'missing' : d.name), d == null, d != null
        }.toList()
        def sql = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            leftjoin d in 'departments' on e.deptId == d.id
            orderby e.name, (d == null ? 'missing' : d.name)
            select e.name, (d == null ? 'missing' : d.name), d == null, d != null
        }.toList()
        assert memory == sql
        assert ['Dave', 'missing', true, false] in sql
        assert sql.findAll { it[2] } == [['Dave', 'missing', true, false]]
        assert sql.findAll { !it[2] }.every { it[3] && it[1] != 'missing' }
    }

    @Test
    void testRightJoinUnmatchedAliasIsNull() {
        def employees = db.rows('select * from employees')
        def departments = db.rows('select * from departments')
        def memory = GQL {
            from e in employees
            rightjoin d in departments on e.deptId == d.id
            orderby (e == null ? 'missing' : e.name), d.name
            select (e == null ? 'missing' : e.name), d.name, e == null, e != null
        }.toList()
        def sql = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            rightjoin d in 'departments' on e.deptId == d.id
            orderby (e == null ? 'missing' : e.name), d.name
            select (e == null ? 'missing' : e.name), d.name, e == null, e != null
        }.toList()
        assert memory == sql
        assert ['missing', 'Empty', true, false] in sql
        assert ['missing', 'Vacant', true, false] in sql
        assert sql.findAll { it[2] }.collect { it[1] }.sort() == ['Empty', 'Vacant']
        assert !sql.any { it[0] == 'Dave' }
    }

    @Test
    void testFullJoinUnmatchedAliasIsNullOnEitherSide() {
        def employees = db.rows('select * from employees')
        def departments = db.rows('select * from departments')
        def excluded = -1
        def memory = GQL {
            from e in employees
            fulljoin d in departments on e.deptId == d.id && d.id != excluded
            orderby (e == null ? 'missing' : e.name), (d == null ? 'missing' : d.name)
            select (e == null ? 'missing' : e.name), (d == null ? 'missing' : d.name), e == null, d == null
        }.toList()
        def sql = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: db) {
            from e in 'employees'
            fulljoin d in 'departments' on e.deptId == d.id && d.id != excluded
            orderby (e == null ? 'missing' : e.name), (d == null ? 'missing' : d.name)
            select (e == null ? 'missing' : e.name), (d == null ? 'missing' : d.name), e == null, d == null
        }.toList()
        assert memory == sql
        assert ['Dave', 'missing', false, true] in sql
        assert ['missing', 'Empty', true, false] in sql
        assert ['missing', 'Vacant', true, false] in sql
        assert !sql.any { it[2] && it[3] }
    }

    @Test
    void testHashOuterJoinsUseTheSameNullAlias() {
        def employees = db.rows('select * from employees')
        def departments = db.rows('select * from departments')
        def memory = GQL {
            from e in employees
            lefthashjoin d in departments on e.deptId == d.id
            orderby e.name
            select e.name, (d == null ? 'missing' : d?.name), d == null
        }.toList()
        def sql = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            lefthashjoin d in 'departments' on e.deptId == d.id
            orderby e.name
            select e.name, (d == null ? 'missing' : d?.name), d == null
        }.toList()
        assert memory == sql
        assert ['Dave', 'missing', true] in sql

        def right = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            righthashjoin d in 'departments' on e.deptId == d.id
            select (e == null ? 'missing' : e.name), d.name, e == null, e != null
        }.toList()
        assert right.findAll { it[2] }.collect { it[1] }.sort() == ['Empty', 'Vacant']
        assert right.findAll { it[2] }.every { it[0] == 'missing' && !it[3] }
    }

    @Test
    void testInnerAndCrossJoinsDoNotInventNullAliases() {
        def inner = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            join d in 'departments' on e.deptId == d.id
            orderby e.name, d.name
            select e.name, (d == null), (d != null)
        }.toList()
        assert inner.every { !it[1] && it[2] }
        assert !inner.any { it[0] == 'Dave' }
        assert 5 == inner.size()

        def crossed = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            crossjoin d in 'departments'
            select e == null, d == null
        }.toList()
        assert 6 * 5 == crossed.size()
        assert crossed.every { !it[0] && !it[1] }
    }

    @Test
    void testAllNullColumnsStayAPresentRow() {
        db.execute('create table anchors (id integer)')
        db.execute('insert into anchors (id) values (1)')
        db.execute('create table blanks (id integer, note varchar(20))')
        db.execute('insert into blanks (id, note) values (null, null)')

        def present = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from a in 'anchors'
            join b in 'blanks' on a.id == 1
            select b == null, b != null, b.id, b.note
        }.toList()
        assert [[false, true, null, null]] == present

        def absent = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from a in 'anchors'
            leftjoin b in 'blanks' on a.id == b.id
            select b == null, b != null, (b == null ? 'missing' : b.note)
        }.toList()
        assert [[true, false, 'missing']] == absent
    }

    @Test
    void testPresenceNameDoesNotHideARealColumn() {
        db.execute('create table marks (__ginq_present integer, name varchar(20))')
        db.execute("insert into marks (__ginq_present, name) values (7, 'kept')")
        db.execute('create table anchors (id integer)')
        db.execute('insert into anchors (id) values (1)')

        def present = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from a in 'anchors'
            join m in 'marks' on a.id == 1
            select m == null, m.__ginq_present, m.name
        }.toList()
        assert [[false, 7, 'kept']] == present

        def absent = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from a in 'anchors'
            leftjoin m in 'marks' on 1 == 0
            select m == null, (m == null ? 'missing' : m.name)
        }.toList()
        assert [[true, 'missing']] == absent
    }

    @Test
    void testSeveralUnmatchedAliasesStayIndexAligned() {
        db.execute('create table anchors (id integer, name varchar(20))')
        db.execute("insert into anchors (id, name) values (1, 'one'), (2, 'two')")

        def rows = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from a in 'anchors'
            leftjoin b in 'departments' on 1 == 0
            leftjoin c in 'departments' on 1 == 0
            orderby a.name
            select a.name, b == null, c == null, (b == null ? 'no-b' : b.name), (c == null ? 'no-c' : c.name)
        }.toList()
        assert [['one', true, true, 'no-b', 'no-c'], ['two', true, true, 'no-b', 'no-c']] == rows
    }

    @Test
    void testInMemoryTailSeesNullAlias() {
        def labels = [[dept: 'Dev', tag: 'code'], [dept: 'none', tag: 'gap']]
        def rows = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            leftjoin d in 'departments' on e.deptId == d.id
            innerjoin t in labels on (d == null ? 'none' : d.name) == t.dept
            where e.name == 'Alice' || e.name == 'Dave'
            orderby e.name
            select e.name, (d == null ? 'missing' : d.name), t.tag, d == null, d != null
        }.toList()
        assert [['Alice', 'Dev', 'code', false, true], ['Dave', 'missing', 'gap', true, false]] == rows
    }

    @Test
    void testGroupByAndOrderByOnANullAlias() {
        def rows = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
            from e in 'employees'
            rightjoin d in 'departments' on e.deptId == d.id
            groupby (e == null ? 'missing' : e.name) into g
            orderby g.key
            select g.key, g.count()
        }.toList()
        def missing = rows.find { it[0] == 'missing' }
        assert missing != null
        assert 2 == missing[1]
        assert rows.any { it[0] == 'Alice' && it[1] == 1 }
    }

    @Test
    void testCompleteFullJoinIncludesBothUnmatchedSides() {
        def captured = new GinqSqlTranslationTest.CapturingSql(db)
        def rows = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: captured) {
            from e in 'employees'
            fulljoin d in 'departments' on e.deptId == d.id
            select e.name as en, d.name as dn
        }.toList()
        def upper = captured.sqlText.toUpperCase(Locale.ROOT)
        assert !upper.contains('FULL OUTER JOIN')
        assert !upper.contains('FULL JOIN')
        assert upper.contains('UNION ALL')
        assert rows.any { it.en == 'Dave' && it.dn == null }
        assert rows.any { it.en == null && it.dn == 'Empty' }
        assert rows.any { it.en == null && it.dn == 'Vacant' }
        assert rows.any { it.en == 'Alice' && it.dn == 'Dev' }
        assert 8 == rows.size()
    }

    @Test
    void testCompleteFullJoinChainOrderAndLimit() {
        db.execute('create table aa (id integer)')
        db.execute('create table bb (id integer)')
        db.execute('create table cc (id integer)')
        db.execute('insert into aa (id) values (1), (2)')
        db.execute('insert into bb (id) values (1), (3)')
        db.execute('insert into cc (id) values (3), (4)')
        def captured = new GinqSqlTranslationTest.CapturingSql(db)
        def limited = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: captured) {
            from a in 'aa'
            fulljoin b in 'bb' on a.id == b.id
            fulljoin c in 'cc' on b.id == c.id
            orderby a.id, b.id, c.id
            limit 3
            select a.id as aId, b.id as bId, c.id as cId
        }.toList()
        def upper = captured.sqlText.toUpperCase(Locale.ROOT)
        assert !upper.contains('FULL OUTER JOIN')
        assert !upper.contains('FULL JOIN')
        assert upper.contains('UNION ALL')
        assert upper.contains('ORDER BY')
        assert upper.contains('FETCH')
        assert !upper.contains('ORDER BY A.ID')
        assert captured.sqlText.indexOf('UNION ALL') < upper.indexOf('ORDER BY')
        assert [[1, 1, null], [2, null, null], [null, 3, 3]] == limited.collect { [it.aId, it.bId, it.cId] }

        def all = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: db) {
            from a in 'aa'
            fulljoin b in 'bb' on a.id == b.id
            fulljoin c in 'cc' on b.id == c.id
            select a.id as aId, b.id as bId, c.id as cId
        }.toList()
        assert 4 == all.size()
        assert all.any { it.aId == null && it.bId == null && it.cId == 4 }
    }

    @Test
    void testCompleteFullJoinWhereStaysInSql() {
        def floor = 4000
        def captured = new GinqSqlTranslationTest.CapturingSql(db)
        def rows = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: captured) {
            from e in 'employees'
            fulljoin d in 'departments' on e.deptId == d.id
            where e.salary > floor
            select e.name as en, d.name as dn
        }.toList()
        assert captured.sqlText.toUpperCase(Locale.ROOT).contains('UNION ALL')
        assert !captured.sqlText.toUpperCase(Locale.ROOT).contains('FULL JOIN')
        assert !captured.sqlText.toUpperCase(Locale.ROOT).contains('FULL OUTER JOIN')
        assert [4000] == captured.params
        assert ['Alice', 'Eve', 'Frank'] as Set == rows.collect { it.en } as Set
        assert rows.every { it.dn != null }
        assert 3 == rows.size()
    }

    @Test
    void testNestedFullJoinMatchesTheCollectionProvider() {
        db.execute('create table aa (id integer)')
        db.execute('create table bb (id integer)')
        db.execute('create table cc (id integer)')
        db.execute('insert into aa (id) values (1), (2)')
        db.execute('insert into bb (id) values (1), (3)')
        db.execute('insert into cc (id) values (3), (4)')
        // A full-joined with B, then with C: one row for each of (1,1), (2), (3,3) and (4).
        def expectFull = [
                [false, 1, false, 1, true, null],
                [false, 2, true, null, true, null],
                [true, null, false, 3, false, 3],
                [true, null, true, null, false, 4],
        ]
        def sqlFull = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: db) {
            from a in 'aa'
            fulljoin b in 'bb' on a.id == b.id
            fulljoin c in 'cc' on b.id == c.id
            select (a == null), (a?.id) as aId, (b == null), (b?.id) as bId, (c == null), (c?.id) as cId
        }.toList()
        assert bag(expectFull) == bag(sqlFull)
        assert sqlFull.count { it[4] == false && it[5] == 4 } == 1
        assert sqlFull.count { it[2] == false && it[3] == 3 && it[5] == 3 } == 1

        def expectRight = [
                [true, null, false, 3, false, 3],
                [true, null, true, null, false, 4],
        ]
        def sqlRight = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: db) {
            from a in 'aa'
            fulljoin b in 'bb' on a.id == b.id
            rightjoin c in 'cc' on b.id == c.id
            select (a == null), (a?.id) as aId, (b == null), (b?.id) as bId, (c == null), (c?.id) as cId
        }.toList()
        assert bag(expectRight) == bag(sqlRight)

        def sqlHash = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: db) {
            from a in 'aa'
            fullhashjoin b in 'bb' on a.id == b.id
            righthashjoin c in 'cc' on b.id == c.id
            select (a == null), (a?.id) as aId, (b == null), (b?.id) as bId, (c == null), (c?.id) as cId
        }.toList()
        assert bag(expectRight) == bag(sqlHash)

        def jooq = GQL(provider: 'jooq-sql', dialect: 'H2', mixedMode: true, dataSource: db) {
            from a in 'aa'
            fulljoin b in 'bb' on a.id == b.id
            fulljoin c in 'cc' on b.id == c.id
            select (a == null), (a?.id) as aId, (b == null), (b?.id) as bId, (c == null), (c?.id) as cId
        }.toList()
        assert bag(expectFull) == bag(jooq)
    }

    @Test
    void testFullJoinPushesTranslatableOrderAndLimit() {
        def employees = db.rows('select * from employees')
        def departments = db.rows('select * from departments')
        def joined = GQL {
            from e in employees
            fulljoin d in departments on e.deptId == d.id
            select (e == null ? null : e.name), (d == null ? null : d.name), (e == null), (d == null)
        }.toList()
        def memory = joined.sort { a, b ->
            int byName = compareNullsLast(a[0], b[0])
            byName != 0 ? byName : compareNullsLast(a[1], b[1])
        }.take(4)
        def sql = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: db) {
            from e in 'employees'
            fulljoin d in 'departments' on e.deptId == d.id
            orderby e.name, d.name
            limit 4
            select (e == null ? null : e.name), (d == null ? null : d.name), (e == null), (d == null)
        }.toList()
        assert memory == sql
        assert 4 == sql.size()
        assert ['Alice', 'Dev', false, false] == sql[0]
        assert sql.every { it[2] == false }
    }

    @Test
    void testPushedWhereOnAFullJoinRepeatsBranchParameters() {
        def floor = 4000
        def rows = GQL(provider: 'native-sql', dialect: 'h2', mixedMode: true, dataSource: db) {
            from e in 'employees'
            fulljoin d in 'departments' on e.deptId == d.id
            where e.salary > floor
            orderby e.name
            select (e == null ? 'missing' : e.name), d == null
        }.toList()
        assert ['Alice', 'Eve', 'Frank'] == rows.collect { it[0] }
        assert rows.every { it[1] == false }
    }

    private static int compareNullsLast(Object left, Object right) {
        if (left == null && right == null) {
            return 0
        }
        if (left == null) {
            return 1
        }
        if (right == null) {
            return -1
        }
        return left.toString() <=> right.toString()
    }

    private static List<String> bag(List rows) {
        rows.collect { row -> (0..<row.size()).collect { row[it] }.toString() }.sort()
    }

    @Test
    void testJooqOuterJoinAliasIsNull() {
        def rows = GQL(provider: 'jooq-sql', dialect: 'H2', mixedMode: true, dataSource: db) {
            from e in 'employees'
            rightjoin d in 'departments' on e.deptId == d.id
            orderby d.name, (e == null ? 'missing' : e.name)
            select (e == null ? 'missing' : e.name), d.name, e == null, e != null
        }.toList()
        assert rows.findAll { it[2] }.collect { it[1] }.sort() == ['Empty', 'Vacant']
        assert rows.findAll { it[2] }.every { it[0] == 'missing' && !it[3] }
        assert rows.any { it[0] == 'Alice' && it[1] == 'Dev' && !it[2] && it[3] }
    }
}
