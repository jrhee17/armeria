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

import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;

import com.fasterxml.jackson.databind.JavaType;

public class TJsonMaskingDeserializer extends TJsonDeserializer {

    private final TBaseSelectorCache cache;

    TJsonMaskingDeserializer(TBaseSelectorCache cache, JavaType javaType, EnumCache enumCache) {
        super(javaType, enumCache);
        this.cache = cache;
    }

    @Override
    protected Object postProcess(TFieldIdEnum fieldId, FieldMetaData fieldMetaData, Object value) {
        return cache.getMapper(fieldId, fieldMetaData).unmask(value, value.getClass());
    }
}
