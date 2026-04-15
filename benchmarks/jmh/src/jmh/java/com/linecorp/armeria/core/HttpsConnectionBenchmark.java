/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.core;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.armeria.server.ConnectionAcceptor;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsProvider;
import com.linecorp.armeria.server.ServerTlsSpec;

import io.netty.util.AttributeMap;

/**
 * Measures per-connection TLS overhead with different server pipeline configurations.
 *
 * <p>Each JMH thread sends one HTTP/1.1 request with {@code Connection: close} per
 * iteration, forcing a fresh TLS handshake every time. Use {@code -Pjmh.threads=N}
 * to control concurrency — all threads share the same server and client, so the
 * server pipeline is stressed under parallel handshakes. JMH reports aggregate
 * throughput (ops/sec) across all threads.
 *
 * <p>Pipeline modes:
 * <ul>
 *   <li>{@code STATIC} — {@code SniHandler} path (hostname→SslContext mapping)</li>
 *   <li>{@code ACCEPTOR} — {@code ConnectionAcceptHandler} with a
 *       {@link ServerTlsProvider} and no-op {@link ConnectionAcceptor}</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class HttpsConnectionBenchmark {

    private static final RequestHeaders HEADERS =
            RequestHeaders.builder(HttpMethod.GET, "/")
                          .add(HttpHeaderNames.CONNECTION, "close")
                          .build();

    @Param({"STATIC", "ACCEPTOR"})
    private String mode;

    final AtomicLong connectionsOpened = new AtomicLong();

    @Nullable
    private Server server;
    @Nullable
    private ClientFactory clientFactory;
    @Nullable
    private WebClient client;

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long success;
        public long failure;
        public long connectionsOpened;

        @Setup(Level.Iteration)
        public void reset(HttpsConnectionBenchmark bench) {
            success = 0;
            failure = 0;
            connectionsOpened = 0;
        }

        @TearDown(Level.Iteration)
        public void snapshot(HttpsConnectionBenchmark bench) {
            // Only the first thread to snapshot gets the real value;
            // the rest get 0. JMH sums EVENTS across threads, so
            // the total equals the one real snapshot.
            connectionsOpened = bench.connectionsOpened.getAndSet(0);
        }
    }

    @Setup(Level.Trial)
    public void startServer() throws Exception {
        final SelfSignedCertificate ssc = new SelfSignedCertificate();
        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(ssc.privateKey(), ssc.certificate());

        final ServerBuilder sb = Server.builder()
                                       .https(0)
                                       .service("/", (ctx, req) -> HttpResponse.of(200));

        switch (mode) {
            case "STATIC":
                sb.tls(ssc.certificate(), ssc.privateKey());
                break;
            case "ACCEPTOR": {
                final ServerTlsSpec spec = ServerTlsSpec.builder()
                                                        .tlsKeyPair(tlsKeyPair)
                                                        .build();
                final ServerTlsProvider provider = ctx -> spec;
                sb.tlsProvider(provider);
                sb.connectionAcceptor(ctx -> true);
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        server = sb.build();
        server.start().join();

        clientFactory = ClientFactory.builder()
                                     .tlsNoVerify()
                                     .connectionPoolListener(new ConnectionPoolListener() {
                                         @Override
                                         public void connectionOpen(
                                                 SessionProtocol protocol,
                                                 InetSocketAddress remoteAddr,
                                                 InetSocketAddress localAddr,
                                                 AttributeMap attrs) {
                                             connectionsOpened.incrementAndGet();
                                         }

                                         @Override
                                         public void connectionClosed(
                                                 SessionProtocol protocol,
                                                 InetSocketAddress remoteAddr,
                                                 InetSocketAddress localAddr,
                                                 AttributeMap attrs) {}
                                     })
                                     .build();
        client = WebClient.builder("h1://127.0.0.1:" + server.activeLocalPort())
                          .factory(clientFactory)
                          .build();
    }

    @TearDown(Level.Trial)
    public void stopServer() {
        if (clientFactory != null) {
            clientFactory.closeAsync().join();
        }
        if (server != null) {
            server.stop().join();
        }
    }

    @Benchmark
    @Threads(200)
    public void tlsConnect(Counters counters) {
        assert client != null;
        try {
            final int code = client.execute(HEADERS).aggregate().join().status().code();
            if (code == 200) {
                counters.success++;
            } else {
                counters.failure++;
            }
        } catch (Exception e) {
            counters.failure++;
        }
    }
}
