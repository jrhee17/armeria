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

import static com.linecorp.armeria.client.AggregatedIfResponseAs.fromJson;

import java.util.Map.Entry;
import java.util.function.Predicate;

import com.linecorp.armeria.client.AggregatedResponseAs.AggregatedContext;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;

class AggregatedElseResponseAs<V> implements ResponseAs<HttpResponse, V> {
    AggregatedResponseAs delegate;
    AggregatedContext<V> context;

    AggregatedElseResponseAs(AggregatedResponseAs delegate,
                             AggregatedContext<V> context) {
        this.delegate = delegate;
        this.context = context;
    }

    public AggregatedIfResponseAs<V> when(Predicate<AggregatedHttpResponse> predicate) {
        return new AggregatedIfResponseAs<>(delegate, predicate, context);
    }

    public ResponseAs<HttpResponse, ResponseEntity<V>> orElse(ResponseAs<AggregatedHttpResponse, V> responseAs) {
        return delegate.andThen(res -> {
            for (Entry<Predicate<AggregatedHttpResponse>, ResponseAs<AggregatedHttpResponse, V>> r : context.list) {
                if (r.getKey().test(res)) {
                    return ResponseEntity.of(res.headers(), r.getValue().as(res));
                }
            }
            return ResponseEntity.of(res.headers(), responseAs.as(res));
        });
    }

    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(Class<? extends V> clazz) {
        return orElse(res -> fromJson(clazz, res));
    }

    @Override
    public V as(HttpResponse response) {
        return orElse(res -> {
            throw new InvalidHttpResponseException(res, "None of the specified conditions were satisifed", null);
        }).as(response).content();
    }
}
