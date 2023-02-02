/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.function.Predicate;

import com.linecorp.armeria.client.AggregatedResponseAs.AggregatedContext;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;

public class AggregatedIfResponseAs<V> extends IfResponseAs<HttpResponse, AggregatedHttpResponse, V> {

    AggregatedResponseAs delegate;
    Predicate<AggregatedHttpResponse> predicate;
    AggregatedContext<V> context;

    AggregatedIfResponseAs(AggregatedResponseAs delegate,
                           Predicate<AggregatedHttpResponse> predicate,
                           AggregatedContext<V> context) {
        super(delegate, predicate);
        this.delegate = delegate;
        this.predicate = predicate;
        this.context = context;
    }

    public AggregatedElseResponseAs<V> thenJson(Class<? extends V> clazz) {
        return then(res -> fromJson(clazz, res));
    }

    static <T> T fromJson(Class<? extends T> clazz, AggregatedHttpResponse res) {
        try {
            return JacksonUtil.readValue(res.contentUtf8().getBytes(StandardCharsets.UTF_8), clazz);
        } catch (Exception e) {
            // need a new exception type
            return Exceptions.throwUnsafely(new InvalidHttpResponseException(res, e));
        }
    }

    @Override
    AggregatedElseResponseAs<V> then(ResponseAs<AggregatedHttpResponse, V> then) {
        context.list.add(new SimpleEntry<>(predicate, then));
        return new AggregatedElseResponseAs<>(delegate, context);
    }
}
