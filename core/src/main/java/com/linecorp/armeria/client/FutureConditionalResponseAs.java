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

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;

public class FutureConditionalResponseAs<V> {

    private final ResponseAs<HttpResponse, CompletableFuture<AggregatedHttpResponse>> originalResponseAs;
    FutureConditionalResponseAs(ResponseAs<HttpResponse, CompletableFuture<AggregatedHttpResponse>> originalResponseAs,
                                ResponseAs<AggregatedHttpResponse, V> responseAs,
                                Predicate<AggregatedHttpResponse> predicate) {
        this.originalResponseAs = originalResponseAs;
        andThen(responseAs, predicate);
    }

    final List<Entry<ResponseAs<AggregatedHttpResponse, V>, Predicate<AggregatedHttpResponse>>> list = new ArrayList<>();

    public ResponseAs<HttpResponse, CompletableFuture<V>> orElse(ResponseAs<AggregatedHttpResponse, V> responseAs) {
        return new ResponseAs<HttpResponse, CompletableFuture<V>>() {
            @Override
            public CompletableFuture<V> as(HttpResponse response) {
                final CompletableFuture<AggregatedHttpResponse> r = originalResponseAs.as(response);
                return r.thenApply(res -> {
                    for (Entry<ResponseAs<AggregatedHttpResponse, V>, Predicate<AggregatedHttpResponse>> entry : list) {
                        if (entry.getValue().test(res)) {
                            return entry.getKey().as(res);
                        }
                    }
                    return responseAs.as(res);
                });
            }
        };
    }

    public FutureConditionalResponseAs<V> andThen(ResponseAs<AggregatedHttpResponse, V> responseAs,
                                                  Predicate<AggregatedHttpResponse> predicate) {
        list.add(new SimpleEntry<>(responseAs, predicate));
        return this;
    }
}
