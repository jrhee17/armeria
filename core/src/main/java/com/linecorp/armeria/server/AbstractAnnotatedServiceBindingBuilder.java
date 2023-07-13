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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceElement;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

class AbstractAnnotatedServiceBindingBuilder implements AnnotatedServiceConfigSetters {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();

    @Nullable
    private String queryDelimiter;
    private boolean useBlockingTaskExecutor;
    private String pathPrefix = "/";
    @Nullable
    private Object service;

    @Override
    public AbstractAnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.addAll(exceptionHandlerFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.add(responseConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.addAll(responseConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.add(requestConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.addAll(requestConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return this;
    }

    /**
     * Sets the delimiter for a query parameter value. Multiple values delimited by the specified
     * {@code delimiter} will be automatically split into a list of values.
     *
     * <p>It is disabled by default.
     *
     * <p>Note that this delimiter works only when the resolve target class type is collection and the number
     * of values of the query parameter is one. For example with the query delimiter {@code ","}:
     * <ul>
     *     <li>{@code ?query=a,b,c} will be resolved to {@code "a"}, {@code "b"} and {@code "c"}</li>
     *     <li>{@code ?query=a,b,c&query=d,e,f} will be resolved to {@code "a,b,c"} and {@code "d,e,f"}</li>
     * </ul>
     */
    @UnstableApi
    public AbstractAnnotatedServiceBindingBuilder queryDelimiter(String delimiter) {
        this.queryDelimiter = requireNonNull(delimiter, "delimiter");
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        defaultServiceConfigSetters.requestTimeout(requestTimeout);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        defaultServiceConfigSetters.accessLogFormat(accessLogFormat);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                                     boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (AbstractAnnotatedServiceBindingBuilder) AnnotatedServiceConfigSetters.super.decorator(
                decoratingHttpServiceFunction);
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    @Override
    @SafeVarargs
    public final AbstractAnnotatedServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        defaultServiceConfigSetters.defaultServiceName(defaultServiceName);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceConfigSetters.defaultServiceNaming(defaultServiceNaming);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder defaultLogName(String defaultLogName) {
        defaultServiceConfigSetters.defaultLogName(defaultLogName);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor,
            boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor,
            boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        defaultServiceConfigSetters.blockingTaskExecutor(numThreads);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder successFunction(SuccessFunction successFunction) {
        defaultServiceConfigSetters.successFunction(successFunction);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder requestAutoAbortDelay(Duration delay) {
        defaultServiceConfigSetters.requestAutoAbortDelay(delay);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder requestAutoAbortDelayMillis(long delayMillis) {
        defaultServiceConfigSetters.requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder multipartUploadsLocation(Path multipartUploadsLocation) {
        defaultServiceConfigSetters.multipartUploadsLocation(multipartUploadsLocation);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        defaultServiceConfigSetters.requestIdGenerator(requestIdGenerator);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder addHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.addHeader(name, value);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.addHeaders(defaultHeaders);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder setHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.setHeader(name, value);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.setHeaders(defaultHeaders);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceBindingBuilder errorHandler(ServiceErrorHandler serviceErrorHandler) {
        defaultServiceConfigSetters.errorHandler(serviceErrorHandler);
        return this;
    }

    protected final void service(Object service) {
        this.service = service;
    }

    /**
     * Builds the {@link ServiceConfigBuilder}s created with the configured
     * {@link AnnotatedServiceExtensions} to the {@link VirtualHostBuilder}.
     *
     * @param extensions the {@link AnnotatedServiceExtensions} at the virtual host level.
     * @param dependencyInjector the {@link DependencyInjector} to inject dependencies.
     */
    List<ServiceConfigBuilder> buildServiceConfigBuilder(AnnotatedServiceExtensions extensions,
                                                         DependencyInjector dependencyInjector) {
        final List<RequestConverterFunction> requestConverterFunctions =
                requestConverterFunctionBuilder.addAll(extensions.requestConverters()).build();
        final List<ResponseConverterFunction> responseConverterFunctions =
                responseConverterFunctionBuilder.addAll(extensions.responseConverters()).build();
        final List<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlerFunctionBuilder.addAll(extensions.exceptionHandlers()).build();

        assert service != null;

        final List<AnnotatedServiceElement> elements =
                AnnotatedServiceFactory.find(
                        pathPrefix, service, useBlockingTaskExecutor, requestConverterFunctions,
                        responseConverterFunctions, exceptionHandlerFunctions, dependencyInjector,
                        queryDelimiter);
        return elements.stream().map(element -> {
            final HttpService decoratedService =
                    element.buildSafeDecoratedService(defaultServiceConfigSetters.decorator());
            return defaultServiceConfigSetters.toServiceConfigBuilder(element.route(), decoratedService);
        }).collect(toImmutableList());
    }
}
