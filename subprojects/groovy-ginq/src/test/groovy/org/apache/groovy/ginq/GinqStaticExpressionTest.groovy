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
package org.apache.groovy.ginq

import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.assertScript

/**
 * Static queries compiled from source while the test runs.
 */
class GinqStaticExpressionTest {

    @Test
    void arithmeticOnADynamicRowUsesTheRuntimeOperators() {
        assertScript '''
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                static List run() {
                    def rows = [[value: 8]]
                    GQ {
                        from r in rows
                        select r.value % 3 + r.value.intdiv(2) + (r.value ** 2) + (r.value << 1) +
                                (r.value >> 1) + (r.value >>> 1) + (r.value & 3) + (r.value | 1) + (r.value ^ 1)
                    }.toList()
                }
            }
            assert Example.run() == [112]
        '''
    }

    @Test
    void stringConcatenationAndAMapSubscriptStayInTheQuery() {
        assertScript '''
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                static List run() {
                    def rows = [[name: 'Ann', extra: [flag: true]]]
                    GQ {
                        from r in rows
                        where r.extra['flag']
                        select r.name + '!'
                    }.toList()
                }
            }
            assert Example.run() == ['Ann!']
        '''
    }

    @Test
    void staticQueryCallsAnUnresolvedMethodOnTheClass() {
        assertScript '''
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                static List run() {
                    GQ {
                        from n in [1, 2]
                        select helper(n)
                    }.toList()
                }
            }
            Example.metaClass.static.helper = { Object n -> 'helper' + n }
            assert Example.run() == ['helper1', 'helper2']
        '''
    }

    @Test
    void instanceQueryCallsAnUnresolvedMethodOnThis() {
        assertScript '''
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                Object methodMissing(String name, Object args) {
                    Object[] values = args instanceof Object[] ? (Object[]) args : [args] as Object[]
                    return name + values[0]
                }
                List run() {
                    GQ {
                        from n in [3]
                        select helper(n)
                    }.toList()
                }
            }
            assert new Example().run() == ['helper3']
        '''
    }

    @Test
    void compileDynamicMethodSkipsTheStaticQueryCheck() {
        assertScript '''
            import groovy.transform.CompileDynamic
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                @CompileDynamic
                static List run() {
                    GQ {
                        from row in [[a: 1], [a: 3]]
                        where row.a > 1
                        select row.a
                    }.toList()
                }
            }
            assert Example.run() == [3]
        '''
    }

    @Test
    void compileDynamicOuterClassSkipsAnInnerStaticQuery() {
        assertScript '''
            import groovy.transform.CompileDynamic
            import groovy.transform.CompileStatic
            @CompileDynamic
            class Outer {
                @CompileStatic
                static class Inner {
                    static List run() {
                        def rows = [[n: 2], [n: 5]]
                        GQ {
                            from r in rows
                            where r.n > 3
                            select r.n
                        }.toList()
                    }
                }
            }
            assert Outer.Inner.run() == [5]
        '''
    }

    @Test
    void nestedSubscriptOnAQueryResultReadsTheInnerValue() {
        assertScript '''
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                static Object run() {
                    def result = GQ {
                        from n in [[9], [8]]
                        select n
                    }
                    result[0][0]
                }
            }
            assert Example.run() == 9
        '''
    }

    @Test
    void lowestUpperBoundDropsArgumentsAClassDoesNotDeclare() {
        assertScript '''
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                static List run() {
                    List<Integer> ints = [1, 2]
                    List<Long> longs = [3L]
                    def rows = true ? ints : longs
                    GQ {
                        from n in rows
                        select n + 1
                    }.toList()
                }
            }
            assert Example.run() == [2, 3]
        '''
    }

    @Test
    void safeCallInsideTheQueryReadsAMissingMapPropertyAsNull() {
        assertScript '''
            import groovy.transform.CompileStatic
            @CompileStatic
            class Example {
                static List run() {
                    def rows = [[missing: 4], [:]]
                    GQ {
                        from r in rows
                        select r?.missing
                    }.toList()
                }
            }
            assert Example.run() == [4, null]
        '''
    }
}
