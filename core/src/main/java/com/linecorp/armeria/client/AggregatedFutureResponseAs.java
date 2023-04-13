/*
 * Copyright 2022 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;

public class AggregatedFutureResponseAs implements ResponseAs<HttpResponse, CompletableFuture<AggregatedHttpResponse>> {

    @Override
    public CompletableFuture<AggregatedHttpResponse> as(HttpResponse response) {
        return response.aggregate();
    }

    <V> AggregatedFutureConditionalResponseAs<V> andThenJson(Class<? extends V> clazz, Predicate<AggregatedHttpResponse> predicate) {
        return new AggregatedFutureConditionalResponseAs<>(this, AggregatedResponseAs.json(clazz), predicate);
    }
}
