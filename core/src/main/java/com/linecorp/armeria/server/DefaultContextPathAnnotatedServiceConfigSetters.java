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

import static java.util.Objects.requireNonNull;

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

public class DefaultContextPathAnnotatedServiceConfigSetters<T> extends AbstractAnnotatedServiceBindingBuilder {

    private final DefaultContextPathServicesBuilder<T> builder;

    DefaultContextPathAnnotatedServiceConfigSetters(DefaultContextPathServicesBuilder<T> builder) {
        this.builder = builder;
    }

    /**
     * TBU.
     */
    public DefaultContextPathServicesBuilder<T> build(Object service) {
        requireNonNull(service, "service");
        service(service);
        builder.annotatedService(this);
        return builder;
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> pathPrefix(String pathPrefix) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>) super.pathPrefix(pathPrefix);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.responseConverters(responseConverterFunctions);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.responseConverters(responseConverterFunctions);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.requestConverters(requestConverterFunctions);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.requestConverters(requestConverterFunctions);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.useBlockingTaskExecutor(useBlockingTaskExecutor);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> queryDelimiter(String delimiter) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.queryDelimiter(delimiter);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestTimeout(Duration requestTimeout) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.requestTimeout(requestTimeout);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestTimeoutMillis(long requestTimeoutMillis) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> maxRequestLength(long maxRequestLength) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.maxRequestLength(maxRequestLength);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> verboseResponses(boolean verboseResponses) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.verboseResponses(verboseResponses);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> accessLogFormat(String accessLogFormat) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.accessLogFormat(accessLogFormat);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> accessLogWriter(AccessLogWriter accessLogWriter,
                                                                  boolean shutdownOnStop) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.decorator(decorator);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.decorators(decorators);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> defaultServiceName(String defaultServiceName) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.defaultServiceName(defaultServiceName);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> defaultLogName(String defaultLogName) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.defaultLogName(defaultLogName);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor, boolean shutdownOnStop) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(int numThreads) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.blockingTaskExecutor(numThreads);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> successFunction(SuccessFunction successFunction) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.successFunction(successFunction);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestAutoAbortDelay(Duration delay) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.requestAutoAbortDelay(delay);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestAutoAbortDelayMillis(long delayMillis) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> multipartUploadsLocation(Path multipartUploadsLocation) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> addHeader(CharSequence name, Object value) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.addHeader(name, value);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.addHeaders(defaultHeaders);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> setHeader(CharSequence name, Object value) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.setHeader(name, value);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.setHeaders(defaultHeaders);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> errorHandler(ServiceErrorHandler serviceErrorHandler) {
        return (DefaultContextPathAnnotatedServiceConfigSetters<T>)  super.errorHandler(serviceErrorHandler);
    }
}
