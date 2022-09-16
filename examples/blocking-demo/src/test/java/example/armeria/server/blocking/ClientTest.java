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

package example.armeria.server.blocking;

import static example.armeria.server.blocking.Main.configureServices;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.server.blocking.Hello.HelloRequest;
import example.armeria.server.blocking.HelloServiceGrpc.HelloServiceBlockingStub;

class ClientTest {
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            configureServices(sb);
        }
    };

    @Test
    void testAnnotated() {
        assertThat(server.blockingWebClient().get("/annotated/hello").contentUtf8())
                .isEqualTo("world");
    }

    @Test
    void testCustom() {
        assertThat(server.blockingWebClient().get("/custom/hello").contentUtf8())
                .isEqualTo("world");
    }

    @Test
    void testGrpc() {
        final HelloServiceBlockingStub client = GrpcClients.builder(server.httpUri()).build(
                HelloServiceBlockingStub.class);
        assertThat(client.hello(HelloRequest.getDefaultInstance()).getMessage()).isEqualTo("world");
    }
}
