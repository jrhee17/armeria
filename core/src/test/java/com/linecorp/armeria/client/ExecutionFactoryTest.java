/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.Lists;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.common.EventLoopGroupExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

class ExecutionFactoryTest {

    private static final ClientOptionValue<Long> CUSTOM_CLIENT_OPTION =
            ClientOptions.MAX_RESPONSE_LENGTH.newValue(Long.MAX_VALUE);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                final Channel channel = ctx.log().partial().channel();
                assert channel != null;
                return HttpResponse.of(channel.id().asShortText());
            });
            sb.service("/webClient", (ctx, req) -> HttpResponse.of("/webClient"));
            sb.service("/prefix/webClient", (ctx, req) -> HttpResponse.of("/prefix/webClient"));
        }
    };

    @RegisterExtension
    static EventLoopGroupExtension eventLoopGroup = new EventLoopGroupExtension(4);

    private static RequestExecutionFactory executionFactory() {
        return new RequestExecutionFactory() {
            @Override
            public RequestExecution prepare(HttpRequest httpRequest,
                                            @Nullable RpcRequest rpcRequest, RequestTarget requestTarget,
                                            RequestOptions requestOptions, ClientOptions clientOptions) {
                final ClientRequestContext ctx =
                        ClientRequestContext
                                .builder(httpRequest, rpcRequest, requestTarget)
                                .requestOptions(requestOptions)
                                .endpointGroup(server.httpEndpoint())
                                .options(clientOptions)
                                .sessionProtocol(SessionProtocol.HTTP)
                                .build();
                return RequestExecution.of(ctx);
            }
        };
    }

    @Test
    void specifyEventLoops() {
        final AtomicInteger counter = new AtomicInteger();
        final ArrayList<EventExecutor> eventExecutors = Lists.newArrayList(eventLoopGroup.get().iterator());
        final WebClient client = WebClient.of(new RequestExecutionFactory() {
            @Override
            public RequestExecution prepare(HttpRequest httpRequest,
                                            @Nullable RpcRequest rpcRequest, RequestTarget requestTarget,
                                            RequestOptions requestOptions, ClientOptions clientOptions) {
                final ClientRequestContext ctx =
                        ClientRequestContext
                                .builder(httpRequest, rpcRequest, requestTarget)
                                .requestOptions(requestOptions)
                                .eventLoop((EventLoop) eventExecutors.get(counter.getAndIncrement() % 4))
                                .endpointGroup(server.httpEndpoint())
                                .options(clientOptions)
                                .sessionProtocol(SessionProtocol.HTTP)
                                .build();
                return RequestExecution.of(ctx);
            }
        });
        final Set<String> channelIds = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            final AggregatedHttpResponse res = client.blocking().get("/");
            assertThat(res.status().code()).isEqualTo(200);
            channelIds.add(res.contentUtf8());
        }
        assertThat(channelIds).hasSize(4);
    }

    private static Stream<Arguments> webClientCompatArgs() {
        final RequestExecutionFactory executionFactory = executionFactory();

        return Stream.of(
                Arguments.of(WebClient.of(executionFactory), "/webClient"),
                Arguments.of(Clients.newDerivedClient(WebClient.of(executionFactory),
                                                      CUSTOM_CLIENT_OPTION), "/webClient"),
                Arguments.of(WebClient.of(executionFactory, "/prefix"), "/prefix/webClient"),
                Arguments.of(Clients.builder(SerializationFormat.NONE, executionFactory)
                                    .build(WebClient.class), "/webClient"),
                Arguments.of(Clients.builder(SerializationFormat.NONE, executionFactory, "/prefix")
                                    .build(WebClient.class), "/prefix/webClient")
        );
    }

    @ParameterizedTest
    @MethodSource("webClientCompatArgs")
    void webClientCompat(WebClient webClient, String expected) {
        final AggregatedHttpResponse res = webClient.blocking().get("/webClient");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo(expected);
    }
}
