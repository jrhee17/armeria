/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.client.tls;

import java.time.Duration;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;

class Http2AlpnTest {

    @RegisterExtension
    @Order(0)
    static final SelfSignedCertificateExtension selfSignedCertificate = new SelfSignedCertificateExtension();

    @RegisterExtension
    @Order(1)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                System.out.println("ctx.sessionProtocol(): " + ctx.sessionProtocol());
                assert ctx.sessionProtocol() == SessionProtocol.H2;
                return HttpResponse.of(200);
            });
            sb.https(0);
            sb.tlsProvider(new TlsProvider() {
                               @Override
                               public @Nullable TlsKeyPair keyPair(String hostname) {
                                   return selfSignedCertificate.tlsKeyPair();
                               }
                           },
                           ServerTlsConfig.builder().tlsCustomizer(b -> b
                                                  .applicationProtocolConfig(new ApplicationProtocolConfig(Protocol.ALPN,
                                                                                                           SelectorFailureBehavior.NO_ADVERTISE,
                                                                                                           SelectedListenerFailureBehavior.ACCEPT,
                                                                                                           ApplicationProtocolNames.HTTP_1_1)))
                                          .build());
        }
    };

    @Test
    void basicCase() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final ClientFactory cf = ClientFactory.insecure();
            final AggregatedHttpResponse res = WebClient.builder(SessionProtocol.H2, server.httpsEndpoint())
                                                        .factory(cf)
                                                        .build()
                                                        .blocking().get("/");
            System.out.println(res);
            final ClientRequestContext ctx = captor.get();
            assert ctx.sessionProtocol() == SessionProtocol.H2;
        }
    }
}
