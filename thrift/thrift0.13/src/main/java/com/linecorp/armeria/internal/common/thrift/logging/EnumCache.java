/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.common.thrift.logging;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.thrift.TEnum;
import org.apache.thrift.meta_data.EnumMetaData;

import com.linecorp.armeria.common.annotation.Nullable;

public class EnumCache {

    private final Map<Class<?>, EnumMeta> enumMetaCache = new ConcurrentHashMap<>();

    Object mapToEnum(Object obj, EnumMetaData enumMetaData) {
        final EnumMeta enumMeta =
                enumMetaCache.computeIfAbsent(enumMetaData.enumClass,
                                              ignored -> new EnumMeta(enumMetaData.enumClass));
        Object value = null;
        if (obj instanceof Number) {
            value = enumMeta.byOrdinal(((Number) obj).intValue());
        } else if (obj instanceof String) {
            value =  enumMeta.byName((String) obj);
        }
        if (value != null) {
            return value;
        }
        throw new RuntimeException("Enum not found for obj: <" + obj + ">, and class <"  +
                                   obj.getClass() + ">.");
    }

    private static class EnumMeta {

        final Map<String, Object> name2value;
        final Map<Integer, Object> int2value;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        EnumMeta(Class<? extends TEnum> clazz) {
            final Class<? extends Enum> enumClass = (Class<? extends Enum>) clazz;
            final EnumSet<? extends Enum> enumSet = EnumSet.allOf(enumClass);
            name2value = enumSet.stream().collect(Collectors.toMap(Enum::name, Function.identity()));
            int2value = enumSet.stream().collect(Collectors.toMap(e -> {
                final TEnum t = (TEnum) e;
                return t.getValue();
            }, Function.identity()));
        }

        @Nullable
        Object byName(String name) {
            return name2value.get(name);
        }

        @Nullable
        Object byOrdinal(int ordinal) {
            return int2value.get(ordinal);
        }
    }
}
