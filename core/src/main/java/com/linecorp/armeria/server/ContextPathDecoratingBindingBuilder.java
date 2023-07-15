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

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.server.RouteDecoratingService;

public class ContextPathDecoratingBindingBuilder<T> extends AbstractBindingBuilder {

    private final DefaultContextPathServicesBuilder<T> builder;
    private final Set<String> contextPaths;

    ContextPathDecoratingBindingBuilder(DefaultContextPathServicesBuilder<T> builder,
                                        Set<String> contextPaths) {
        this.builder = builder;
        this.contextPaths = contextPaths;
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> path(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.path(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> pathPrefix(String prefix) {
        return (ContextPathDecoratingBindingBuilder<T>) super.pathPrefix(prefix);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> get(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.get(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> post(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.post(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> put(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.put(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> patch(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.patch(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> delete(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.delete(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> options(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.options(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> head(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.head(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> trace(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.trace(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> connect(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.connect(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> methods(HttpMethod... methods) {
        return (ContextPathDecoratingBindingBuilder<T>) super.methods(methods);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> methods(Iterable<HttpMethod> methods) {
        return (ContextPathDecoratingBindingBuilder<T>) super.methods(methods);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> consumes(MediaType... consumeTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> consumes(Iterable<MediaType> consumeTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> produces(MediaType... produceTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.produces(produceTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> produces(Iterable<MediaType> produceTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.produces(produceTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesParams(String... paramPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesParams(Iterable<String> paramPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesParams(String paramName, Predicate<? super String> valuePredicate) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesHeaders(String... headerPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesHeaders(Iterable<String> headerPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesHeaders(CharSequence headerName,
                                                                 Predicate<? super String> valuePredicate) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> addRoute(Route route) {
        return (ContextPathDecoratingBindingBuilder<T>) super.addRoute(route);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> exclude(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.exclude(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> exclude(Route excludedRoute) {
        return (ContextPathDecoratingBindingBuilder<T>) super.exclude(excludedRoute);
    }

    /**
     * Sets the {@code decorator} and return (ContextPathDecoratingBindingBuilder)s {@link ServerBuilder} that this
     * {@link DecoratingServiceBindingBuilder} was created from.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    public DefaultContextPathServicesBuilder<T> build(
            Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(decorator, "decorator");
        buildRouteList().forEach(
                route -> contextPaths.forEach(contextPath -> builder.addRouteDecoratingService(
                        new RouteDecoratingService(route.withPrefix(contextPath), decorator))));
        return builder;
    }

    /**
     * Sets the {@link DecoratingHttpServiceFunction} and returns {@link ServerBuilder} that this
     * {@link DecoratingServiceBindingBuilder} was created from.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}
     */
    public DefaultContextPathServicesBuilder<T> build(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return build(delegate -> new FunctionalDecoratingHttpService(delegate, decoratingHttpServiceFunction));
    }
}
