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

package com.linecorp.armeria.it.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ThriftDecoratorTest {

    static final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    private static class ClassDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            queue.add("class");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            queue.add("class");
            return delegate.serve(ctx, req);
        }
    }

    @Decorator(ClassDecorator.class)
    private static class MyThriftClass implements HelloService.AsyncIface {

        @Override
        @Decorator(MethodDecorator.class)
        public void hello(String name, AsyncMethodCallback<String> resultHandler) throws TException {
            resultHandler.onComplete(name);
        }
    };

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService service = THttpService.builder()
                                                    .addService(new MyThriftClass())
                                                    .build();
            sb.service("/echo", service);
        }
    };

    @Test
    void testDecorator() throws Exception {
        final Iface client = ThriftClients.builder(server.httpUri()).path("/echo").build(Iface.class);
        assertThat(client.hello("world")).isEqualTo("world");
        assertThat(queue).hasSize(2);
        assertThat(queue.poll()).isEqualTo("server");
        assertThat(queue.poll()).isEqualTo("client");
    }
}
