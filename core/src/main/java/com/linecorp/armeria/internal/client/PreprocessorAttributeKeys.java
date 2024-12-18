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

package com.linecorp.armeria.internal.client;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.endpoint.EndpointGroup;

import io.netty.util.AttributeKey;

public final class PreprocessorAttributeKeys {

    public static final AttributeKey<Object> FUTURE_CONVERTER_KEY =
            AttributeKey.valueOf(PreprocessorAttributeKeys.class, "futureConverter");
    public static final AttributeKey<BiFunction<ClientRequestContext, Throwable, ?>> ERROR_RESPONSE_FACTORY_KEY =
            AttributeKey.valueOf(PreprocessorAttributeKeys.class, "errorResponseFactory");
    public static final AttributeKey<EndpointGroup> ENDPOINT_GROUP_KEY =
            AttributeKey.valueOf(PreprocessorAttributeKeys.class, "endpointGroup");

    public static Set<AttributeKey<?>> keys() {
        return ImmutableSet.of(FUTURE_CONVERTER_KEY, ERROR_RESPONSE_FACTORY_KEY, ENDPOINT_GROUP_KEY);
    }

    private PreprocessorAttributeKeys() {}
}
