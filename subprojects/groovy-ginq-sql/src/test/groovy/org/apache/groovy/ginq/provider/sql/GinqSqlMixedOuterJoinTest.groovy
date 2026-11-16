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
        def sql = GQL(provider: 'native-sql', mixedMode: true, dataSource: db) {
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
