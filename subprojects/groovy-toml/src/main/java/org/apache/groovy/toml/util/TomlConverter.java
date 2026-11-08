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
package org.apache.groovy.toml.util;

import groovy.toml.TomlRuntimeException;
import org.apache.groovy.lang.annotation.Incubating;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

/**
 * Converts between TOML and JSON text representations.
 *
 *  @since 4.0.0
 */
@Incubating
public final class TomlConverter {
    // Jackson 2's defaults keep the conversion producing what it did before Jackson 3;
    // both mappers are thread-safe once built, so they are shared
    private static final JsonMapper JSON_MAPPER = JsonMapper.builderWithJackson2Defaults().build();
    private static final TomlMapper TOML_MAPPER = TomlMapper.builder().configureForJackson2().build();

    // Carrier key for a root value that is not a non-empty table. Jackson 3.3
    // rejects that value, but this module has always written it with an empty
    // key (" = [1, 2, 3]"). The carrier is stripped before the text is returned
    // and is put back only while reading that same text.
    private static final String ROOT_VALUE_KEY = "v";
    private static final String EMPTY_KEY_PREFIX = " = ";

    /**
     * Converts TOML content from the supplied reader into JSON text.
     *
     * @param tomlReader the reader that provides TOML content
     * @return the equivalent JSON text
     */
    public static String convertTomlToJson(Reader tomlReader) {
        try {
            String toml = readFully(tomlReader);
            boolean historicalRoot = toml.startsWith(EMPTY_KEY_PREFIX);
            String source = historicalRoot ? ROOT_VALUE_KEY + toml : toml;
            Object value = TOML_MAPPER.readValue(source, Object.class);
            if (historicalRoot) {
                value = unwrapRootValue(value);
            }
            return JSON_MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new TomlRuntimeException(e);
        }
    }

    /**
     * Converts JSON content from the supplied reader into TOML text.
     *
     * @param jsonReader the reader that provides JSON content
     * @return the equivalent TOML text
     */
    public static String convertJsonToToml(Reader jsonReader) {
        try {
            JsonNode json = JSON_MAPPER.readTree(jsonReader);
            if (json != null && json.isObject() && !json.isEmpty()) {
                return TOML_MAPPER.writeValueAsString(json);
            }
            return writeRootValue(json);
        } catch (JacksonException e) {
            throw new TomlRuntimeException(e);
        }
    }

    private static String writeRootValue(JsonNode value) throws JacksonException {
        ObjectNode wrapper = JSON_MAPPER.createObjectNode();
        if (value == null || value.isNull() || value.isMissingNode()) {
            wrapper.putNull(ROOT_VALUE_KEY);
        } else {
            wrapper.set(ROOT_VALUE_KEY, value);
        }
        String written = TOML_MAPPER.writeValueAsString(wrapper);
        if (!written.startsWith(ROOT_VALUE_KEY + EMPTY_KEY_PREFIX)) {
            throw new TomlRuntimeException("Cannot write a TOML value at the document root");
        }
        return written.substring(ROOT_VALUE_KEY.length());
    }

    private static Object unwrapRootValue(Object value) {
        if (value instanceof Map<?, ?> map && map.size() == 1 && map.containsKey(ROOT_VALUE_KEY)) {
            return map.get(ROOT_VALUE_KEY);
        }
        return value;
    }

    private static String readFully(Reader reader) {
        char[] buffer = new char[2048];
        StringBuilder text = new StringBuilder();
        try (Reader in = reader) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                text.append(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new TomlRuntimeException(e);
        }
        return text.toString();
    }

    private TomlConverter() {}
}
