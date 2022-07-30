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

package com.linecorp.armeria.resilience4j.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.Builder;
import io.github.resilience4j.circuitbreaker.IllegalStateTransitionException;

class Resilience4jCircuitBreakerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/500", (ctx, req) -> HttpResponse.of(500));
        }
    };

    @Test
    void testNullName() {
        final CircuitBreaker delegate = CircuitBreaker.ofDefaults(null);
        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(delegate);
        assertThat(cb).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(CircuitState.class)
    void enterState(CircuitState state) {
        final CircuitBreaker delegate = CircuitBreaker.ofDefaults(null);
        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(delegate);
        if (state == CircuitState.HALF_OPEN) {
            // state transition from CLOSED to HALF_OPEN isn't allowed for Resilience4j
            // Armeria re-initializes a state when forcing transition, so there is no need
            // to apply this restriction.
            assertThatThrownBy(() -> cb.enterState(state))
                    .isInstanceOf(IllegalStateTransitionException.class);
        } else {
            cb.enterState(state);
            assertThat(cb.circuitState()).isEqualTo(state);
        }
    }

    @Test
    void testBasicClientIntegration() {
        final int minimumNumberOfCalls = 3;
        final CircuitBreakerConfig config = new Builder()
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .build();

        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(CircuitBreaker.of("cb", config));

        final CircuitBreakerRule rule = CircuitBreakerRule.onStatusClass(HttpStatusClass.SERVER_ERROR);
        final Function<? super HttpClient, CircuitBreakerClient> circuitBreakerDecorator =
                CircuitBreakerClient.newDecorator(cb, rule);
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(circuitBreakerDecorator)
                                          .build();
        for (int i = 0; i < minimumNumberOfCalls; i++) {
            assertThat(client.get("/500").aggregate().join().status().code()).isEqualTo(500);
        }
        assertThatThrownBy(() -> client.get("/500").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(FailFastException.class);
    }
}
