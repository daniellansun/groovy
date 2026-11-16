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

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.apache.groovy.ginq.provider.sql.ir.SqlBinary
import org.apache.groovy.ginq.provider.sql.ir.SqlCaseWhen
import org.apache.groovy.ginq.provider.sql.ir.SqlCast
import org.apache.groovy.ginq.provider.sql.ir.SqlColumn
import org.apache.groovy.ginq.provider.sql.ir.SqlDerivedTable
import org.apache.groovy.ginq.provider.sql.ir.SqlExists
import org.apache.groovy.ginq.provider.sql.ir.SqlExpr
import org.apache.groovy.ginq.provider.sql.ir.SqlFunction
import org.apache.groovy.ginq.provider.sql.ir.SqlIn
import org.apache.groovy.ginq.provider.sql.ir.SqlInQuery
import org.apache.groovy.ginq.provider.sql.ir.SqlIsNull
import org.apache.groovy.ginq.provider.sql.ir.SqlJoin
import org.apache.groovy.ginq.provider.sql.ir.SqlLike
import org.apache.groovy.ginq.provider.sql.ir.SqlLiteral
import org.apache.groovy.ginq.provider.sql.ir.SqlOrderSpec
import org.apache.groovy.ginq.provider.sql.ir.SqlProjection
import org.apache.groovy.ginq.provider.sql.ir.SqlQuery
import org.apache.groovy.ginq.provider.sql.ir.SqlQueryNode
import org.apache.groovy.ginq.provider.sql.ir.SqlScalarQuery
import org.apache.groovy.ginq.provider.sql.ir.SqlSetQuery
import org.apache.groovy.ginq.provider.sql.ir.SqlStar
import org.apache.groovy.ginq.provider.sql.ir.SqlTableRef
import org.apache.groovy.ginq.provider.sql.ir.SqlTableSource
import org.apache.groovy.ginq.provider.sql.ir.SqlUnary
import org.codehaus.groovy.GroovyBugError

/**
 * Puts a presence column on each mixed-mode source and, when the dialect has
 * no {@code FULL OUTER JOIN}, nests each full join as its own union. The
 * union is a derived table, and the next join reads that table. A later
 * outer join sees the whole left subjoin as absent only when every presence
 * column inside it is null.
 */
@PackageScope
@CompileStatic
class MixedSourceLayout {
    static final String PRESENCE = '__ginq_present'
    private static final String SRC = '__ginq_src'

    private final List<String> aliasNames

    private MixedSourceLayout(List<String> aliasNames) {
        this.aliasNames = aliasNames
    }

    /**
     * Wraps every source with a presence column. When {@code emulateFull} is
     * set, each full join becomes
     * {@code (left LEFT JOIN right) UNION ALL (left RIGHT JOIN right WHERE left is absent)}
     * and that union is the left input of the following join.
     */
    static SqlQueryNode layout(SqlQuery query, List<String> aliasNames, boolean emulateFull) {
        return new MixedSourceLayout(aliasNames).apply(query, emulateFull)
    }

    private SqlQueryNode apply(SqlQuery query, boolean emulateFull) {
        query.from = wrap(query.from)
        List<SqlJoin> wrapped = []
        for (SqlJoin join : query.joins) {
            wrapped.add(new SqlJoin(join.type, wrap(join.table), join.on))
        }
        query.joins.clear()
        query.joins.addAll(wrapped)
        if (!emulateFull) {
            return query
        }
        return nest(query)
    }

    private SqlQueryNode nest(SqlQuery query) {
        Rel rel = new Rel()
        rel.from = query.from
        rel.direct.add(aliasNames[0])
        rel.output.add(0)
        for (int j = 0; j < query.joins.size(); j++) {
            SqlJoin join = query.joins[j]
            int index = j + 1
            if (join.type == SqlJoin.Type.FULL) {
                rel = nestFull(rel, join, index)
            } else {
                appendJoin(rel, join, index)
            }
        }
        return finish(rel, query)
    }

    private void appendJoin(Rel rel, SqlJoin join, int index) {
        rel.joins.add(new SqlJoin(join.type, join.table, rewriteExpr(join.on, rel, aliasNames[index])))
        rel.direct.add(aliasNames[index])
        rel.output.add(index)
    }

    private Rel nestFull(Rel rel, SqlJoin join, int index) {
        List<SqlProjection> projections = project(rel)
        projections.add(new SqlProjection(new SqlStar(aliasNames[index])))
        SqlExpr on = rewriteExpr(join.on, rel, aliasNames[index])
        SqlQuery left = materialize(rel, SqlJoin.Type.LEFT, join.table, on, projections, null)
        SqlQuery right = materialize(rel, SqlJoin.Type.RIGHT, join.table, on, projections, absence(rel))
        SqlSetQuery union = new SqlSetQuery(left, SqlSetQuery.SetOp.UNION_ALL, right)
        int first = rel.output[0]
        String container = '__ginqf' + first + '_' + (index + 1)
        Rel next = new Rel()
        next.from = new SqlDerivedTable(union, container)
        next.container = container
        next.output.addAll(rel.output)
        next.output.add(index)
        return next
    }

    private SqlQuery materialize(Rel rel, SqlJoin.Type type, SqlTableSource right, SqlExpr on,
                                 List<SqlProjection> projections, SqlExpr where) {
        SqlQuery query = new SqlQuery()
        query.from = rel.from
        query.joins.addAll(rel.joins)
        query.joins.add(new SqlJoin(type, right, on))
        query.projections.addAll(projections)
        query.where = where
        return query
    }

    private List<SqlProjection> project(Rel rel) {
        List<SqlProjection> projections = []
        if (rel.container != null) {
            projections.add(new SqlProjection(new SqlStar(rel.container)))
        }
        for (String alias : rel.direct) {
            projections.add(new SqlProjection(new SqlStar(alias)))
        }
        return projections
    }

    private SqlExpr absence(Rel rel) {
        SqlExpr acc = null
        for (int index : rel.output) {
            SqlExpr column
            if (rel.direct.contains(aliasNames[index])) {
                column = new SqlColumn(aliasNames[index], PRESENCE)
            } else {
                column = new SqlColumn(rel.container, '__ginqabs_' + index)
            }
            SqlExpr pred = new SqlIsNull(column, false)
            acc = acc == null ? pred : new SqlBinary('AND', acc, pred)
        }
        return acc
    }

    private SqlQueryNode finish(Rel rel, SqlQuery original) {
        SqlExpr where = rewriteExpr(original.where, rel)
        List<SqlOrderSpec> orders = []
        for (SqlOrderSpec spec : original.orderBy) {
            orders.add(rewriteOrder(spec, rel))
        }
        if (rel.joins.isEmpty() && rel.container != null) {
            SqlQuery wrapper = new SqlQuery()
            wrapper.from = rel.from
            wrapper.projections.add(new SqlProjection(new SqlStar(rel.container)))
            wrapper.where = where
            wrapper.orderBy.addAll(orders)
            wrapper.offset = original.offset
            wrapper.fetch = original.fetch
            return wrapper
        }
        SqlQuery query = new SqlQuery()
        query.from = rel.from
        query.joins.addAll(rel.joins)
        query.projections.addAll(project(rel))
        query.where = where
        query.orderBy.addAll(orders)
        query.offset = original.offset
        query.fetch = original.fetch
        return query
    }

    private SqlOrderSpec rewriteOrder(SqlOrderSpec spec, Rel rel) {
        if (!(spec.expr instanceof SqlColumn)) {
            throw new GroovyBugError('emulated full join order key is not a column')
        }
        SqlColumn column = (SqlColumn) spec.expr
        if (rel.direct.contains(column.tableAlias)) {
            return spec
        }
        int index = aliasNames.indexOf(column.tableAlias)
        String qualifier = rel.container != null ? rel.container : column.tableAlias
        return new SqlOrderSpec(new SqlColumn(qualifier, '__ginqord_' + index + '_' + column.column), spec.asc, spec.nullsLast)
    }

    private SqlExpr rewriteExpr(SqlExpr expr, Rel rel, String alsoDirect = null) {
        if (expr == null || rel.container == null) {
            return expr
        }
        if (expr instanceof SqlColumn) {
            SqlColumn column = (SqlColumn) expr
            if (column.tableAlias == null || column.tableAlias == alsoDirect
                    || rel.direct.contains(column.tableAlias) || column.tableAlias == rel.container) {
                return column
            }
            int index = aliasNames.indexOf(column.tableAlias)
            if (index < 0) {
                return column
            }
            return new SqlColumn(rel.container, '__ginqref_' + index + '_' + column.column)
        }
        if (expr instanceof SqlBinary) {
            return new SqlBinary(expr.op, rewriteExpr(expr.left, rel, alsoDirect), rewriteExpr(expr.right, rel, alsoDirect))
        }
        if (expr instanceof SqlUnary) {
            return new SqlUnary(expr.op, rewriteExpr(expr.operand, rel, alsoDirect))
        }
        if (expr instanceof SqlIsNull) {
            return new SqlIsNull(rewriteExpr(expr.expr, rel, alsoDirect), expr.negated)
        }
        if (expr instanceof SqlIn) {
            List<SqlExpr> values = []
            for (SqlExpr value : expr.values) {
                values.add(rewriteExpr(value, rel, alsoDirect))
            }
            return new SqlIn(rewriteExpr(expr.expr, rel, alsoDirect), values, expr.negated)
        }
        if (expr instanceof SqlLike) {
            return new SqlLike(rewriteExpr(expr.expr, rel, alsoDirect), rewriteExpr(expr.pattern, rel, alsoDirect))
        }
        if (expr instanceof SqlFunction) {
            List<SqlExpr> args = []
            for (SqlExpr arg : expr.args) {
                args.add(rewriteExpr(arg, rel, alsoDirect))
            }
            return new SqlFunction(expr.name, args, expr.distinct)
        }
        if (expr instanceof SqlCast) {
            return new SqlCast(rewriteExpr(expr.expr, rel, alsoDirect), expr.sqlType)
        }
        if (expr instanceof SqlCaseWhen) {
            return new SqlCaseWhen(rewriteExpr(expr.condition, rel, alsoDirect), rewriteExpr(expr.thenExpr, rel, alsoDirect), rewriteExpr(expr.elseExpr, rel, alsoDirect))
        }
        if (expr instanceof SqlScalarQuery) {
            return new SqlScalarQuery(rewriteQuery(expr.query, rel, alsoDirect))
        }
        if (expr instanceof SqlExists) {
            return new SqlExists(rewriteQuery(expr.query, rel, alsoDirect))
        }
        if (expr instanceof SqlInQuery) {
            return new SqlInQuery(rewriteExpr(expr.expr, rel, alsoDirect), rewriteQuery(expr.query, rel, alsoDirect), expr.negated)
        }
        return expr
    }

    private SqlQuery rewriteQuery(SqlQuery query, Rel rel, String alsoDirect = null) {
        SqlQuery copy = new SqlQuery()
        copy.distinct = query.distinct
        for (SqlProjection projection : query.projections) {
            copy.projections.add(new SqlProjection(rewriteExpr(projection.expr, rel, alsoDirect), projection.alias))
        }
        copy.from = query.from
        for (SqlJoin join : query.joins) {
            copy.joins.add(new SqlJoin(join.type, join.table, rewriteExpr(join.on, rel, alsoDirect)))
        }
        copy.where = rewriteExpr(query.where, rel, alsoDirect)
        copy.groupBy.addAll(query.groupBy)
        copy.having = rewriteExpr(query.having, rel, alsoDirect)
        copy.orderBy.addAll(query.orderBy)
        copy.offset = query.offset
        copy.fetch = query.fetch
        return copy
    }

    private static SqlTableSource wrap(SqlTableSource source) {
        SqlQuery inner = new SqlQuery()
        inner.from = realias(source, SRC)
        inner.projections.add(new SqlProjection(new SqlStar(SRC)))
        inner.projections.add(new SqlProjection(new SqlLiteral(1), PRESENCE))
        return new SqlDerivedTable(inner, source.alias)
    }

    private static SqlTableSource realias(SqlTableSource source, String alias) {
        if (source instanceof SqlTableRef) {
            return new SqlTableRef(source.tableName, alias)
        }
        if (source instanceof SqlDerivedTable) {
            return new SqlDerivedTable(source.query, alias)
        }
        throw new GroovyBugError('unknown mixed-mode source ' + source.getClass().name)
    }

    private static final class Rel {
        SqlTableSource from
        final List<SqlJoin> joins = []
        final List<String> direct = []
        final List<Integer> output = []
        String container
    }
}
