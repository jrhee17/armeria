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

package com.linecorp.armeria.client.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.logging.RequestLog;

class ConcurrencyLimitWithRetryTest {

    private static final RuntimeException EXCEPTION = new RuntimeException();

    @ParameterizedTest
    @EnumSource(ExchangeType.class)
    void responseReturnedOnly(ExchangeType exchangeType) {
        final WebClient client =
                WebClient.builder("http://127.0.0.1")
                         .responseTimeoutMillis(Long.MAX_VALUE)
                         .decorator((delegate, ctx, req) -> {
                             throw EXCEPTION;
                         })
                         .decorator(ConcurrencyLimitingClient.newDecorator(3))
                         .decorator(RetryingClient.newDecorator(
                                 RetryConfig.builder(RetryRule.onException())
                                            .maxTotalAttempts(3)
                                            .build()))
                         .build();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
            final RequestOptions requestOptions = RequestOptions.builder().exchangeType(exchangeType).build();
            assertThatThrownBy(() -> client.execute(request, requestOptions).aggregate().join())
                    .hasCause(EXCEPTION);
            final List<ClientRequestContext> contexts = captor.getAll();
            assertThat(contexts).hasSize(1);
            final RequestLog log = contexts.get(0).log().whenComplete().join();
            assertThat(log.responseCause()).isEqualTo(EXCEPTION);
        }
    }
}
