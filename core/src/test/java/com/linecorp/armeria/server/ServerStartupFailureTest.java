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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.EventLoopGroups;

import io.netty.channel.EventLoopGroup;

class ServerStartupFailureTest {

    @ValueSource(ints = { 0, 1 })
    @ParameterizedTest
    void portInitializationFailureStopsBindingAndRollsBack(int failingPortIndex) throws Exception {
        final InetAddress loopback = InetAddress.getLoopbackAddress();
        final List<ServerPort> ports = new ArrayList<>();
        final List<EventLoopGroup> bossGroups = new CopyOnWriteArrayList<>();
        final List<InetSocketAddress> boundAddresses = new CopyOnWriteArrayList<>();
        final ServerBuilder sb = Server.builder()
                                      .service("/", (ctx, req) -> HttpResponse.of(200))
                                      .serverListener(new ServerListenerAdapter() {
                                          @Override
                                          public void serverStopping(Server server) {
                                              boundAddresses.addAll(server.activePorts().keySet());
                                          }
                                      })
                                      .bossGroupFactory(threadName -> {
                                          final EventLoopGroup group = EventLoopGroups.newEventLoopGroup(1);
                                          bossGroups.add(group);
                                          return group;
                                      });
        for (int i = 0; i < 3; i++) {
            final ServerPort port = new ServerPort(new InetSocketAddress(loopback, 0), HTTP);
            ports.add(port);
            sb.port(port);
        }

        // Reproduce the stale actualPort state seen when restarting a server, without a preceding start.
        // The socket binds successfully, but ServerPortStartListener fails while recording its actual port.
        ports.get(failingPortIndex).setActualPort(12345);

        try (Server server = sb.build()) {
            assertThatThrownBy(() -> server.start().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("actualPort is already set to 12345");

            assertThat(server.activePorts()).isEmpty();
            // Startup must wait for rollback and must not attempt any ports after the failed one.
            assertThat(bossGroups).hasSize(failingPortIndex + 1)
                                  .allSatisfy(group -> assertThat(group.isTerminated()).isTrue());
            assertThat(ports).allSatisfy(port -> assertThat(port.actualPort()).isZero());
            assertThat(boundAddresses).hasSize(failingPortIndex);
            for (InetSocketAddress address : boundAddresses) {
                // Ports initialized before the failure must have released their listening sockets.
                try (ServerSocket rebound = new ServerSocket(address.getPort(), 0, address.getAddress())) {
                    assertThat(rebound.getLocalPort()).isEqualTo(address.getPort());
                }
            }
        } finally {
            for (EventLoopGroup group : bossGroups) {
                group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }
}
