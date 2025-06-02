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

import static com.linecorp.armeria.internal.common.thrift.logging.TJsonProtocolUtils.getTypeNameForTypeID;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.TUnion;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.protocol.TType;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;

class TBaseSerializer extends JsonSerializer<TBase> {

    @Override
    public Class<TBase> handledType() {
        return TBase.class;
    }

    @Nullable
    protected Object postProcess(TFieldIdEnum fieldId, FieldMetaData fieldMetaData, Object value) {
        return value;
    }

    @Override
    public void serialize(TBase tBase, JsonGenerator gen, SerializerProvider prov)
            throws IOException {
        gen.writeStartObject();
        final boolean isUnion = tBase instanceof TUnion<?,?>;
        final Map<? extends TFieldIdEnum, FieldMetaData> metadatas =
                ThriftMetadataAccess.getStructMetaDataMap(tBase.getClass());
        for (Entry<? extends TFieldIdEnum, FieldMetaData> entry : metadatas.entrySet()) {
            if (isUnion && !tBase.isSet(entry.getKey())) {
                // thrift0.9 defines unions with default requirement
                continue;
            }

            final FieldValueMetaData valueMetaData = entry.getValue().valueMetaData;
            if (entry.getValue().requirementType == TFieldRequirementType.OPTIONAL &&
                !tBase.isSet(entry.getKey())) {
                continue;
            }

            final byte type = valueMetaData.type;
            if ((type == TType.MAP && !(valueMetaData instanceof MapMetaData)) ||
                (type == TType.SET && !(valueMetaData instanceof SetMetaData)) ||
                (type == TType.LIST && !(valueMetaData instanceof ListMetaData))) {
                // valueMetadata is not set as the true value for thrift <0.19
                // see https://github.com/apache/thrift/pull/2765
                // just skip writing since we are unsure what the element type is
                continue;
            }

            Object value = tBase.getFieldValue(entry.getKey());

            value = postProcess(entry.getKey(), entry.getValue(), value);
            if (value == null) {
                continue;
            }

            // TJsonProtocol
            gen.writeFieldId(entry.getKey().getThriftFieldId());
            gen.writeStartObject();
            gen.writeFieldName(getTypeNameForTypeID(type));
            if (type == TType.MAP) {
                gen.writeStartArray();
                final MapMetaData mapMetaData = (MapMetaData) valueMetaData;
                gen.writeString(getTypeNameForTypeID(mapMetaData.keyMetaData.type));
                gen.writeString(getTypeNameForTypeID(mapMetaData.valueMetaData.type));
                final Map<?, ?> map = (Map<?, ?>) value;
                gen.writeNumber(map.size());
                gen.writeStartObject();
                for (Entry<?, ?> o : map.entrySet()) {
                    gen.writeFieldName(o.getKey().toString());
                    writeObject(gen, o.getValue(), mapMetaData.valueMetaData.type);
                }
                gen.writeEndObject();
                gen.writeEndArray();
            } else if (type == TType.LIST) {
                gen.writeStartArray();
                final ListMetaData listMetaData = (ListMetaData) valueMetaData;
                gen.writeString(getTypeNameForTypeID(listMetaData.elemMetaData.type));
                final List<?> list = (List<?>) value;
                gen.writeNumber(list.size());
                for (Object o : list) {
                    writeObject(gen, o, listMetaData.elemMetaData.type);
                }
                gen.writeEndArray();
            } else if (type == TType.SET) {
                gen.writeStartArray();
                final SetMetaData setMetaData = (SetMetaData) valueMetaData;
                gen.writeString(getTypeNameForTypeID(setMetaData.elemMetaData.type));
                final Set<?> set = (Set<?>) value;
                gen.writeNumber(set.size());
                for (Object o : set) {
                    writeObject(gen, o, setMetaData.elemMetaData.type);
                }
                gen.writeEndArray();
            } else {
                if (valueMetaData.isBinary()) {
                    final byte[] bytes = (byte[]) value;
                    gen.writeBinary(bytes, 0, bytes.length);
                } else {
                    writeObject(gen, value, type);
                }
            }
            gen.writeEndObject();
        }
        gen.writeEndObject();
    }

    private static void writeObject(JsonGenerator gen, Object o, byte type) throws IOException {
        if (type == TType.ENUM) {
            final TEnum enumValue = (TEnum) o;
            o = enumValue.getValue();
        } else if (type == TType.BOOL) {
            final Boolean booleanValue = (Boolean) o;
            o = booleanValue ? 1 : 0;
        }
        gen.writeObject(o);
    }
}
