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

package com.linecorp.armeria.internal.server.annotation;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.*;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.ParameterizedType;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {

                @Post("/")
                @ExceptionHandler(MyExceptionHandlerFunction.class)
                public HttpResponse post(MySimpleRequest simpleRequest) {
                    return HttpResponse.of(200);
                };
            });
        }
    };

    private static final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
    private static final Exception throwable = new Exception();

    static class MySimpleRequest {
        @Param
        String string;

        @RequestConverter(MyRequestConverterFunction.class)
        SimpleRequest simpleRequest;
    }

    static class SimpleRequest {}

    static class MyExceptionHandlerFunction implements ExceptionHandlerFunction {

        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            throwableRef.set(cause);
            return HttpResponse.of(501);
        }
    }

    static class MyRequestConverterFunction implements RequestConverterFunction {

        @Override
        public @Nullable Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType, @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {
            if (true) {
                throw throwable;
            }
            return null;
        }
    }

    @Test
    void testAsdf() {
        final AggregatedHttpResponse res = server.blockingWebClient().post("/", "asdf");
        assertThat(res.status().code()).isEqualTo(501);
        assertThat(throwableRef).hasValue(throwable);
    }
}
