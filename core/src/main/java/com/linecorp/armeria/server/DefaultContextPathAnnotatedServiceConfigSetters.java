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

package com.linecorp.armeria.server;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

public class DefaultContextPathAnnotatedServiceConfigSetters<T> 
implements AnnotatedServiceConfigSetters {

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> pathPrefix(String pathPrefix) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestTimeout(Duration requestTimeout) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestTimeoutMillis(long requestTimeoutMillis) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> maxRequestLength(long maxRequestLength) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> verboseResponses(boolean verboseResponses) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> accessLogFormat(String accessLogFormat) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> decorators(Function<? super HttpService, ? extends HttpService>... decorators) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> defaultServiceName(String defaultServiceName) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> defaultLogName(String defaultLogName) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                                     boolean shutdownOnStop) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                     boolean shutdownOnStop) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(int numThreads) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> successFunction(SuccessFunction successFunction) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestAutoAbortDelay(Duration delay) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestAutoAbortDelayMillis(long delayMillis) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> multipartUploadsLocation(Path multipartUploadsLocation) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> addHeader(CharSequence name, Object value) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> setHeader(CharSequence name, Object value) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return null;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> errorHandler(ServiceErrorHandler serviceErrorHandler) {
        return null;
    }
    
    public final T build(Object service) {
        return null;
    }
}
