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

import static java.util.Objects.requireNonNull;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Predicate;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.util.Exceptions;

public class AggregatedResponseAs implements ResponseAs<HttpResponse, AggregatedHttpResponse> {

    static class AggregatedContext<V> {
        List<Entry<Predicate<AggregatedHttpResponse>, ResponseAs<AggregatedHttpResponse, V>>> list =
                new ArrayList<>();

        void add(Predicate<AggregatedHttpResponse> predicate, ResponseAs<AggregatedHttpResponse, V> responseAs) {
            list.add(new SimpleEntry<>(predicate, responseAs));
        }
    }

    AggregatedResponseAs() {
    }

    @Override
    public AggregatedHttpResponse as(HttpResponse response) {
        requireNonNull(response, "response");
        try {
            return response.aggregate().join();
        } catch (Exception ex) {
            return Exceptions.throwUnsafely(Exceptions.peel(ex));
        }
    }

    public <V> AggregatedIfResponseAs<V> when(
            Predicate<AggregatedHttpResponse> predicate) {
        final AggregatedContext<V> context = new AggregatedContext<>();
        return new AggregatedIfResponseAs<>(this, predicate, context);
    }

    public ResponseAs<HttpResponse, ResponseEntity<String>> string() {
        return andThen(AggregatedResponseAsUtil.string());
    }

    @Override
    public boolean requiresAggregation() {
        return true;
    }
}
