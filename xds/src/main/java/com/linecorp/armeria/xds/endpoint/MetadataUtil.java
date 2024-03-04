/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.endpoint;

import java.util.Map.Entry;
import java.util.Objects;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.Value.KindCase;

import io.envoyproxy.envoy.config.core.v3.Metadata;

final class MetadataUtil {

    static boolean metadataLabelMatch(Struct labelSet, Metadata hostMetadata,
                                      String filterKey, boolean listAsAny) {
        if (hostMetadata == Metadata.getDefaultInstance()) {
            return labelSet.getFieldsMap().isEmpty();
        }
        if (!hostMetadata.containsFilterMetadata(filterKey)) {
            return labelSet.getFieldsMap().isEmpty();
        }
        final Struct dataStruct = hostMetadata.getFilterMetadataOrThrow(filterKey);
        for (Entry<String, Value> kv: labelSet.getFieldsMap().entrySet()) {
            if (!dataStruct.getFieldsMap().containsKey(kv.getKey())) {
                return false;
            }
            final Value value = dataStruct.getFieldsOrThrow(kv.getKey());
            if (listAsAny && value.getKindCase() == KindCase.LIST_VALUE) {
                boolean anyMatch = false;
                for (Value innerValue: value.getListValue().getValuesList()) {
                    if (Objects.equals(kv.getValue(), innerValue)) {
                        anyMatch = true;
                        break;
                    }
                }
                if (!anyMatch) {
                    return false;
                }
            } else if (!Objects.equals(kv.getValue(), value)) {
                return false;
            }
        }
        return true;
    }

    private MetadataUtil() {}
}
