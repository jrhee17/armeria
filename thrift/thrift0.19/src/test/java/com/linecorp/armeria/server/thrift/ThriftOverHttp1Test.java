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
package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.thrift.transport.THttpClient;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.Ignore;
import org.junit.Test;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

import io.netty.util.AsciiString;
import testing.thrift.main.SleepService;

public class ThriftOverHttp1Test extends AbstractThriftOverHttpTest {
    @Override
    protected TTransport newTransport(String uri, HttpHeaders headers) throws TTransportException {
        CloseableHttpClient httpclient = null;
        try {
            final PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder
                    .create()
                    .setSSLSocketFactory(
                            SSLConnectionSocketFactoryBuilder
                                    .create()
                                    .setSslContext(
                                            SSLContextBuilder.create()
                                                             .loadTrustMaterial(
                                                                     TrustAllStrategy.INSTANCE)
                                                             .build())
                                    .setHostnameVerifier(
                                            NoopHostnameVerifier.INSTANCE)
                                    .build())
                    .build();
            httpclient = HttpClients
                    .custom()
                    .setConnectionManager(connManager)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
        final THttpClient client = new THttpClient(uri, httpclient);
        client.setCustomHeaders(
                headers.names().stream()
                       .collect(toImmutableMap(AsciiString::toString,
                                               name -> String.join(", ", headers.getAll(name)))));
        return client;
    }

    @Test
    public void testNonPostRequest() throws Exception {
        final HttpUriRequest[] reqs = {
                new HttpGet(newUri("http", "/hello")),
                new HttpDelete(newUri("http", "/hello"))
        };

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            for (HttpUriRequest r: reqs) {
                try (CloseableHttpResponse res = hc.execute(r)) {
                    assertThat(res.getCode()).isEqualTo(405);
                    assertThat(EntityUtils.toString(res.getEntity()))
                            .isNotEqualTo("Hello, world!");
                }
            }
        }
    }

    @Test
    @Ignore
    public void testPipelinedHttpInvocation() throws Exception {
        // FIXME: Enable this test once we have a working Thrift-over-HTTP/1 client with pipelining.
        try (TTransport transport = newTransport("http", "/sleep")) {
            final SleepService.Client client = new SleepService.Client.Factory().getClient(
                    ThriftProtocolFactories.binary(0, 0).getProtocol(transport));

            client.send_sleep(1000);
            client.send_sleep(500);
            client.send_sleep(0);
            assertThat(client.recv_sleep()).isEqualTo(1000L);
            assertThat(client.recv_sleep()).isEqualTo(500L);
            assertThat(client.recv_sleep()).isEqualTo(0L);
        }
    }
}
