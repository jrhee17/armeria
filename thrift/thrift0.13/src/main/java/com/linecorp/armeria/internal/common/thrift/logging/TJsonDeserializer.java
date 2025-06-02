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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;

class TJsonDeserializer extends JsonDeserializer<TBase> {

    private final TBase defaultInstance;
    private final Map<String, TFieldIdEnum> fieldToIdMap;
    final Map<? extends TFieldIdEnum, FieldMetaData> metaDataMap;
    private final EnumCache methodCache;

    TJsonDeserializer(JavaType javaType, EnumCache methodCache) {
        this.methodCache = methodCache;
        try {
            defaultInstance =
                    (TBase) javaType.getRawClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        metaDataMap = ThriftMetadataAccess.getStructMetaDataMap(defaultInstance.getClass());
        fieldToIdMap = metaDataMap.entrySet().stream()
                                  .collect(Collectors.toMap(e -> String.valueOf(e.getKey().getThriftFieldId()),
                                                            Entry::getKey));
    }

    @Override
    public Class<TBase> handledType() {
        return TBase.class;
    }

    @Override
    public TBase deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        final TBase builder = defaultInstance.deepCopy();
        ensureToken(JsonToken.START_OBJECT, p, ctxt);
        return populateTbase(p, ctxt, builder);
    }

    private TBase populateTbase(JsonParser p, DeserializationContext ctxt, TBase builder) throws IOException {
        while (p.nextToken() != JsonToken.END_OBJECT) {
            ensureToken(JsonToken.FIELD_NAME, p, ctxt);
            final String fieldName = p.getText();
            if (!fieldToIdMap.containsKey(fieldName)) {
                ctxt.handleUnknownProperty(p, this, defaultInstance.getClass(), fieldName);
                p.skipChildren();
                continue;
            }

            final TFieldIdEnum fieldId = fieldToIdMap.get(fieldName);
            final FieldMetaData metadata = metaDataMap.get(fieldId);
            assert metadata != null;

            advanceToken(JsonToken.START_OBJECT, p, ctxt);
            advanceToken(JsonToken.FIELD_NAME, p, ctxt);
            final String field = p.getText();
            final byte typeId = TJsonProtocolUtils.getTypeIdForTypeName(field);

            Object value = readType(p, ctxt, typeId, metadata.valueMetaData);
            advanceToken(JsonToken.END_OBJECT, p, ctxt);
            value = postProcess(fieldId, metadata, value);
            if (value != null) {
                builder.setFieldValue(fieldId, value);
            }
        }

        return builder;
    }

    protected Object postProcess(TFieldIdEnum fieldId, FieldMetaData fieldMetaData, Object value) {
        return value;
    }

    /**
     * The {@param typeId} is used as the primary source of truth for parsing the JSON stream.
     * {@param metaData} is used as a hint when determining the next class to be parsed
     * in case the underlying type is a struct.
     */
    private Object readType(JsonParser p, DeserializationContext ctxt, byte typeId, FieldValueMetaData metaData)
            throws IOException {
        Object value;
        switch (typeId) {
            case TType.BOOL:
                p.nextToken();
                value = p.getValueAsBoolean();
                break;
            case TType.BYTE:
                p.nextToken();
                value = p.getByteValue();
                break;
            case TType.DOUBLE:
                p.nextToken();
                value = p.getDoubleValue();
                break;
            case TType.I16:
                p.nextToken();
                value = p.getShortValue();
                break;
            case TType.I32:
                p.nextToken();
                value = p.getIntValue();
                break;
            case TType.I64:
                p.nextToken();
                value = p.getLongValue();
                break;
            case TType.STRING:
                p.nextToken();
                value = p.getText();
                break;
            case TType.STRUCT:
                p.nextToken();
                final StructMetaData structMetaData = (StructMetaData) metaData;
                value = p.readValueAs(structMetaData.structClass);
                break;
            case TType.MAP:
                final MapMetaData mapMetaData = (MapMetaData) metaData;
                advanceToken(JsonToken.START_ARRAY, p, ctxt);
                p.nextToken();
                final byte keyType = TJsonProtocolUtils.getTypeIdForTypeName(p.getText());
                p.nextToken();
                final byte valueType = TJsonProtocolUtils.getTypeIdForTypeName(p.getText());
                p.nextToken();
                final int mapSz = p.getIntValue();

                advanceToken(JsonToken.START_OBJECT, p, ctxt);
                final ImmutableMap.Builder<Object, Object> mapBuilder = ImmutableMap.builder();
                for (int i = 0; i < mapSz; i++) {
                    final Object mapKey = readType(p, ctxt, keyType, mapMetaData.keyMetaData);
                    final Object mapValue = readType(p, ctxt, valueType, mapMetaData.valueMetaData);
                    mapBuilder.put(mapKey, mapValue);
                }
                value = mapBuilder.build();
                advanceToken(JsonToken.END_OBJECT, p, ctxt);
                advanceToken(JsonToken.END_ARRAY, p, ctxt);
                break;
            case TType.SET:
                final SetMetaData setMetaData = (SetMetaData) metaData;
                advanceToken(JsonToken.START_ARRAY, p, ctxt);
                p.nextToken();
                final byte setElemType = TJsonProtocolUtils.getTypeIdForTypeName(p.getText());
                p.nextToken();
                final int setSz = p.getIntValue();

                final ImmutableSet.Builder<Object> setBuilder = ImmutableSet.builder();
                for (int i = 0; i < setSz; i++) {
                    final Object elem = readType(p, ctxt, setElemType, setMetaData.elemMetaData);
                    setBuilder.add(elem);
                }
                value = setBuilder.build();
                advanceToken(JsonToken.END_ARRAY, p, ctxt);
                break;
            case TType.LIST:
                final ListMetaData listMetaData = (ListMetaData) metaData;
                advanceToken(JsonToken.START_ARRAY, p, ctxt);
                p.nextToken();
                final byte elemType = TJsonProtocolUtils.getTypeIdForTypeName(p.getText());
                p.nextToken();
                final int listSz = p.getIntValue();

                final ImmutableList.Builder<Object> listBuilder = ImmutableList.builder();
                for (int i = 0; i < listSz; i++) {
                    final Object elem = readType(p, ctxt, elemType, listMetaData.elemMetaData);
                    listBuilder.add(elem);
                }
                value = listBuilder.build();
                advanceToken(JsonToken.END_ARRAY, p, ctxt);
                break;
            default:
                throw new RuntimeException();
        }

        if (metaData.type == TType.ENUM && metaData instanceof EnumMetaData) {
            value = methodCache.mapToEnum(value, (EnumMetaData) metaData);
        } else if (metaData.isBinary() && value instanceof String) {
            value = ByteBuffer.wrap(Base64.getDecoder().decode((String) value));
        }

        return value;
    }

    private void advanceToken(JsonToken expectedToken, JsonParser p,
                              DeserializationContext ctxt) throws IOException {
        p.nextToken();
        if (expectedToken != p.currentToken()) {
            throw ctxt.wrongTokenException(p, defaultInstance.getClass(), p.currentToken(), "");
        }
    }

    private void ensureToken(JsonToken expectedToken, JsonParser p,
                             DeserializationContext ctxt) throws IOException {
        if (expectedToken != p.currentToken()) {
            throw ctxt.wrongTokenException(p, defaultInstance.getClass(), p.currentToken(), "");
        }
    }
}
