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

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;

final class AggregatedFutureConditionalResponseAs<V> extends FutureConditionalResponseAs<ResponseEntity<V>> {

    AggregatedFutureConditionalResponseAs(ResponseAs<HttpResponse, CompletableFuture<AggregatedHttpResponse>> originalResponseAs,
                                          ResponseAs<AggregatedHttpResponse, ResponseEntity<V>> responseAs,
                                          Predicate<AggregatedHttpResponse> predicate) {
        super(originalResponseAs, responseAs, predicate);
    }

    public AggregatedFutureConditionalResponseAs<V> andThenJson(
            Class<? extends V> clazz, Predicate<AggregatedHttpResponse> predicate) {
        return (AggregatedFutureConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(clazz), predicate);
    }

    public AggregatedFutureConditionalResponseAs<V> andThenJson(
            Class<? extends V> clazz, ObjectMapper objectMapper, Predicate<AggregatedHttpResponse> predicate) {
        return (AggregatedFutureConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(clazz, objectMapper), predicate);
    }

    public AggregatedFutureConditionalResponseAs<V> andThenJson(
            TypeReference<? extends V> typeRef, Predicate<AggregatedHttpResponse> predicate) {
        return (AggregatedFutureConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(typeRef), predicate);
    }

    public AggregatedFutureConditionalResponseAs<V> andThenJson(
            TypeReference<? extends V> typeRef, ObjectMapper objectMapper, Predicate<AggregatedHttpResponse> predicate) {
        return (AggregatedFutureConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(typeRef, objectMapper), predicate);
    }

    public ResponseAs<HttpResponse, CompletableFuture<ResponseEntity<V>>> orElseJson(Class<? extends V> clazz) {
        return orElse(AggregatedResponseAs.json(clazz));
    }

    public ResponseAs<HttpResponse, CompletableFuture<ResponseEntity<V>>> orElseJson(Class<? extends V> clazz, ObjectMapper objectMapper) {
        return orElse(AggregatedResponseAs.json(clazz, objectMapper));
    }

    public ResponseAs<HttpResponse, CompletableFuture<ResponseEntity<V>>> orElseJson(TypeReference<? extends V> typeRef) {
        return orElse(AggregatedResponseAs.json(typeRef));
    }

    public ResponseAs<HttpResponse, CompletableFuture<ResponseEntity<V>>> orElseJson(TypeReference<? extends V> typeRef,
                                                                                     ObjectMapper objectMapper) {
        return orElse(AggregatedResponseAs.json(typeRef, objectMapper));
    }
}
