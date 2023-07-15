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
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * TBU.
 */
public final class ContextPathServiceBindingBuilder<T> extends AbstractServiceBindingBuilder {

    private final DefaultContextPathServicesBuilder<T> contextPathServicesBuilder;
    private final Set<String> contextPaths;

    ContextPathServiceBindingBuilder(DefaultContextPathServicesBuilder<T> contextPathServicesBuilder,
                                     Set<String> contextPaths) {
        this.contextPathServicesBuilder = contextPathServicesBuilder;
        this.contextPaths = contextPaths;
    }

    @Override
    public ContextPathServiceBindingBuilder<T> requestTimeout(Duration requestTimeout) {
        return (ContextPathServiceBindingBuilder<T>) super.requestTimeout(requestTimeout);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> requestTimeoutMillis(long requestTimeoutMillis) {
        return (ContextPathServiceBindingBuilder<T>) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> maxRequestLength(long maxRequestLength) {
        return (ContextPathServiceBindingBuilder<T>) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> verboseResponses(boolean verboseResponses) {
        return (ContextPathServiceBindingBuilder<T>) super.verboseResponses(verboseResponses);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> accessLogFormat(String accessLogFormat) {
        return (ContextPathServiceBindingBuilder<T>) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> accessLogWriter(AccessLogWriter accessLogWriter,
                                                               boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder<T>) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServiceBindingBuilder<T>) super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServiceBindingBuilder<T>) super.decorator(decorator);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (ContextPathServiceBindingBuilder<T>) super.decorators(decorators);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (ContextPathServiceBindingBuilder<T>) super.decorators(decorators);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> defaultServiceName(String defaultServiceName) {
        return (ContextPathServiceBindingBuilder<T>) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return (ContextPathServiceBindingBuilder<T>) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> defaultLogName(String defaultLogName) {
        return (ContextPathServiceBindingBuilder<T>) super.defaultLogName(defaultLogName);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder<T>) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                shutdownOnStop);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                                    boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder<T>) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                shutdownOnStop);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> blockingTaskExecutor(int numThreads) {
        return (ContextPathServiceBindingBuilder<T>) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> successFunction(SuccessFunction successFunction) {
        return (ContextPathServiceBindingBuilder<T>) super.successFunction(successFunction);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> requestAutoAbortDelay(Duration delay) {
        return (ContextPathServiceBindingBuilder<T>) super.requestAutoAbortDelay(delay);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> requestAutoAbortDelayMillis(long delayMillis) {
        return (ContextPathServiceBindingBuilder<T>) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> multipartUploadsLocation(Path multipartUploadsLocation) {
        return (ContextPathServiceBindingBuilder<T>) super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (ContextPathServiceBindingBuilder<T>) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> addHeader(CharSequence name, Object value) {
        return (ContextPathServiceBindingBuilder<T>) super.addHeader(name, value);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathServiceBindingBuilder<T>) super.addHeaders(defaultHeaders);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> setHeader(CharSequence name, Object value) {
        return (ContextPathServiceBindingBuilder<T>) super.setHeader(name, value);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathServiceBindingBuilder<T>) super.setHeaders(defaultHeaders);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> errorHandler(ServiceErrorHandler serviceErrorHandler) {
        return (ContextPathServiceBindingBuilder<T>) super.errorHandler(serviceErrorHandler);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> path(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.path(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> pathPrefix(String prefix) {
        return (ContextPathServiceBindingBuilder<T>) super.pathPrefix(prefix);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> get(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.get(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> post(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.post(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> put(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.put(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> patch(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.patch(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> delete(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.delete(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> options(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.options(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> head(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.head(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> trace(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.trace(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> connect(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.connect(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> methods(HttpMethod... methods) {
        return (ContextPathServiceBindingBuilder<T>) super.methods(methods);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> methods(Iterable<HttpMethod> methods) {
        return (ContextPathServiceBindingBuilder<T>) super.methods(methods);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> consumes(MediaType... consumeTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> consumes(Iterable<MediaType> consumeTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> produces(MediaType... produceTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.produces(produceTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> produces(Iterable<MediaType> produceTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.produces(produceTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> matchesParams(String... paramPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> matchesParams(Iterable<String> paramPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> matchesParams(String paramName,
                                                             Predicate<? super String> valuePredicate) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> matchesHeaders(String... headerPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> matchesHeaders(Iterable<String> headerPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> matchesHeaders(CharSequence headerName,
                                                              Predicate<? super String> valuePredicate) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> addRoute(Route route) {
        return (ContextPathServiceBindingBuilder<T>) super.addRoute(route);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> exclude(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.exclude(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder<T> exclude(Route excludedRoute) {
        return (ContextPathServiceBindingBuilder<T>) super.exclude(excludedRoute);
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        contextPathServicesBuilder.addServiceConfigSetters(serviceConfigBuilder);
    }

    /**
     * Sets the {@link HttpService} and returns the object that this
     * {@link ServiceBindingBuilder} was created from.
     */
    public DefaultContextPathServicesBuilder<T> build(HttpService service) {
        for (String contextPath: contextPaths) {
            build0(service, contextPath);
        }
        return contextPathServicesBuilder;
    }
}
