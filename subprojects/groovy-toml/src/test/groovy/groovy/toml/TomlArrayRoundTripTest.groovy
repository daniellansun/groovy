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
package groovy.toml

import org.junit.jupiter.api.Test

class TomlArrayRoundTripTest {

    private static Object roundTrip(Object built) {
        return new TomlSlurper().parseText(built.toString())
    }

    @Test
    void testEmptyRootArray() {
        // GROOVY-12738
        def toml = new TomlBuilder()
        toml([])

        assert toml.toString() == ' = []\n'
        assert roundTrip(toml) == []
        assert TomlBuilder.toToml([]) == ' = []\n'
        assert new TomlSlurper().parseText(TomlBuilder.toToml([])) == []
    }

    @Test
    void testNestedRootArray() {
        // GROOVY-12738
        def toml = new TomlBuilder()
        toml([[1, 2], [], [3]])

        assert toml.toString() == ' = [[1, 2], [], [3]]\n'
        assert roundTrip(toml) == [[1, 2], [], [3]]
        assert TomlBuilder.toToml([[1, 2], [], [3]]) == ' = [[1, 2], [], [3]]\n'
    }

    @Test
    void testDeeplyNestedRootArray() {
        // GROOVY-12738
        def toml = new TomlBuilder()
        toml([[[[1]]], []])

        assert toml.toString() == ' = [[[[1]]], []]\n'
        assert roundTrip(toml) == [[[[1]]], []]
    }

    @Test
    void testRootArrayOfTables() {
        // GROOVY-12738
        def toml = new TomlBuilder()
        toml([[name: 'Guillaume'], [name: 'Jochen'], [name: 'Paul']])

        assert toml.toString() == " = [{name = 'Guillaume'}, {name = 'Jochen'}, {name = 'Paul'}]\n"
        assert roundTrip(toml) == toml.content
    }

    @Test
    void testEmptyAndNestedArraysInsideObject() {
        // GROOVY-12738
        def toml = new TomlBuilder()
        toml {
            empty([])
            nested([[1, 2], [], [3]])
        }

        assert toml.toString() == '''\
empty = []
nested = [[1, 2], [], [3]]
'''
        assert roundTrip(toml) == toml.content
    }

    @Test
    void testEmptyRootObjectRoundTrip() {
        // GROOVY-12738
        def toml = new TomlBuilder()
        toml([:])

        assert toml.toString() == ' = {}\n'
        assert roundTrip(toml) == [:]
    }

    @Test
    void testEmptyRootObjectAndScalars() {
        // GROOVY-12738
        assert TomlBuilder.toToml([:]) == ' = {}\n'
        assert new TomlSlurper().parseText(TomlBuilder.toToml([:])) == [:]
        assert TomlBuilder.toToml('hello') == " = 'hello'\n"
        assert new TomlSlurper().parseText(TomlBuilder.toToml('hello')) == 'hello'
        assert TomlBuilder.toToml(1) == ' = 1\n'
        assert new TomlSlurper().parseText(TomlBuilder.toToml(1)) == 1
        assert TomlBuilder.toToml(true) == ' = true\n'
        assert new TomlSlurper().parseText(TomlBuilder.toToml(true)) == true
        // TOML has no null. A root null is written as an empty string, and reads back as one.
        assert new TomlBuilder().toString() == " = ''\n"
        assert TomlBuilder.toToml(null) == " = ''\n"
        assert new TomlSlurper().parseText(TomlBuilder.toToml(null)) == ''
    }

    @Test
    void testNamedArrayIsNotUnwrapped() {
        // GROOVY-12738
        assert new TomlSlurper().parseText('v = [1, 2, 3]\n') == [v: [1, 2, 3]]
        assert new TomlSlurper().parseText("name = ' = [1, 2]'\n").name == ' = [1, 2]'
    }
}
