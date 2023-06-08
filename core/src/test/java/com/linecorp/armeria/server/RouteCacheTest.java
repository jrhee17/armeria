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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.MatchesHeader;
import com.linecorp.armeria.server.annotation.MatchesParam;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RouteCacheTest {

    private static final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService("/v1", new Object() {
                @Get("/query")
                @MatchesParam("param")
                public HttpResponse byQuery(@Param String param) {
                    return HttpResponse.of(param);
                }

                @Get("/header")
                @MatchesHeader("x-custom")
                public HttpResponse byHeader(@Header("x-custom") String header) {
                    return HttpResponse.of(header);
                }

                @Get("/exact")
                public HttpResponse exact() {
                    return HttpResponse.of(200);
                }
            });
            sb.meterRegistry(meterRegistry);
        }
    };

    @Test
    void testQueryMatch() {
        final double beforeSize = cacheSize();
        final AggregatedHttpResponse res1 = server.blockingWebClient().get("/v1/query?param=1234");
        assertThat(res1.status().code()).isEqualTo(200);
        assertThat(res1.contentUtf8()).isEqualTo("1234");
        final AggregatedHttpResponse res2 = server.blockingWebClient().get("/v1/query");
        assertThat(res2.status().code()).isEqualTo(404);
        await().pollDelay(Duration.of(1, ChronoUnit.SECONDS))
               .untilAsserted(() -> assertThat(cacheSize()).isEqualTo(beforeSize));
    }

    @Test
    void testHeaderMatch() {
        final double beforeSize = cacheSize();
        final AggregatedHttpResponse res1 = server.blockingWebClient().prepare()
                                                  .header("x-custom", "1234")
                                                  .get("/v1/header")
                                                  .execute();
        assertThat(res1.status().code()).isEqualTo(200);
        assertThat(res1.contentUtf8()).isEqualTo("1234");
        final AggregatedHttpResponse res2 = server.blockingWebClient().get("/v1/header");
        assertThat(res2.status().code()).isEqualTo(404);
        await().pollDelay(Duration.of(1, ChronoUnit.SECONDS))
               .untilAsserted(() -> assertThat(cacheSize()).isEqualTo(beforeSize));
    }

    @Test
    void testExactCached() {
        final double beforeSize = cacheSize();
        assertThat(server.blockingWebClient().get("/v1/exact").status().code())
                .isEqualTo(200);
        await().until(() -> cacheSize() == beforeSize + 1);
    }

    private static double cacheSize() {
        return MoreMeters.measureAll(meterRegistry)
                .get("armeria.server.router.virtual.host.cache.estimated.size#value{hostname.pattern=*}");
    }
}
