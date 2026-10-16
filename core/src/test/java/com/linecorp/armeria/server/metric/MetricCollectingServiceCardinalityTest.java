/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.server.metric;

import static com.linecorp.armeria.common.metric.MoreMeters.measureAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetricCollectingServiceCardinalityTest {

    // A prefix function that adds the request path as a tag, creating high cardinality.
    private static final MeterIdPrefixFunction HIGH_CARDINALITY_PREFIX =
            new MeterIdPrefixFunction() {
                @Override
                public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
                    return new MeterIdPrefix("test",
                                             "method", log.requestHeaders().method().name(),
                                             "path", log.requestHeaders().path());
                }

                @Override
                public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
                    return new MeterIdPrefix("test",
                                             "method", log.requestHeaders().method().name(),
                                             "path", log.requestHeaders().path(),
                                             "http.status",
                                             log.responseHeaders().status().codeAsText());
                }
            };

    private static final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/api/{id}", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.meterRegistry(registry);
            // Only allow 3 distinct activeRequestPrefix values.
            sb.decorator(MetricCollectingService.newDecorator(HIGH_CARDINALITY_PREFIX, 3));
        }
    };

    @Test
    void metricsAreCappedAtMaxCardinality() {
        // Send 5 requests with distinct paths.
        for (int i = 1; i <= 5; i++) {
            server.webClient().blocking().get("/api/" + i);
        }

        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);

            // The first 3 paths should have metrics recorded.
            assertThat(measurements)
                    .containsKey("test.requests#count{http.status=200,method=GET,path=/api/1,result=success}")
                    .containsKey("test.requests#count{http.status=200,method=GET,path=/api/2,result=success}")
                    .containsKey("test.requests#count{http.status=200,method=GET,path=/api/3,result=success}");

            // Paths 4 and 5 should be rejected — no metrics for them.
            assertThat(measurements)
                    .doesNotContainKey(
                            "test.requests#count{http.status=200,method=GET,path=/api/4,result=success}")
                    .doesNotContainKey(
                            "test.requests#count{http.status=200,method=GET,path=/api/5,result=success}");
        });

        // Active request metrics are also gated — paths 4 and 5 have no active gauge either.
    }

    @Test
    void previouslySeenPrefixesAreStillRecorded() {
        // After the first test, paths 1-3 are in the seen set.
        // Sending another request to /api/1 should still record.
        server.webClient().blocking().get("/api/1");

        await().untilAsserted(() -> {
            final Map<String, Double> measurements = measureAll(registry);
            assertThat(measurements)
                    .containsEntry(
                            "test.requests#count{http.status=200,method=GET,path=/api/1,result=success}",
                            2.0);
        });
    }
}
