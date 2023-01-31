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

import java.util.Map.Entry;
import java.util.function.Predicate;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;

class ToEntityResponseAs<V> implements ResponseAs<HttpResponse, V> {
    private final AggregatedResponseAs<V> delegate;
    private final ResponseAs<AggregatedHttpResponse, V> fallback;

    ToEntityResponseAs(AggregatedResponseAs<V> delegate,
                       ResponseAs<AggregatedHttpResponse, V> fallback) {
        this.delegate = delegate;
        this.fallback = fallback;
    }

    public ResponseAs<HttpResponse, ResponseEntity<V>> toEntity() {
        return ResponseAsUtil.BLOCKING.andThen(res -> {
            for (Entry<Predicate<AggregatedHttpResponse>, ResponseAs<AggregatedHttpResponse, V>> r : delegate.list) {
                if (r.getKey().test(res)) {
                    return ResponseEntity.of(res.headers(), r.getValue().as(res));
                }
            }
            return ResponseEntity.of(res.headers(), fallback.as(res));
        });
    }

    @Override
    public V as(HttpResponse response) {
        return ResponseAsUtil.BLOCKING.andThen(fallback).as(response);
    }
}
