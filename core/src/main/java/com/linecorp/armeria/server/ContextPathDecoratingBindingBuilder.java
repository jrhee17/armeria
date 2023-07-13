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

import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.server.RouteDecoratingService;

public class ContextPathDecoratingBindingBuilder<T> extends AbstractBindingBuilder {

    private final DefaultContextPathServicesBuilder<T> builder;
    ContextPathDecoratingBindingBuilder(DefaultContextPathServicesBuilder<T> builder) {
        this.builder = builder;
    }

    @Override
    public AbstractBindingBuilder path(String pathPattern) {
        return super.path(pathPattern);
    }

    @Override
    public AbstractBindingBuilder pathPrefix(String prefix) {
        return super.pathPrefix(prefix);
    }

    @Override
    public AbstractBindingBuilder get(String pathPattern) {
        return super.get(pathPattern);
    }

    @Override
    public AbstractBindingBuilder post(String pathPattern) {
        return super.post(pathPattern);
    }

    @Override
    public AbstractBindingBuilder put(String pathPattern) {
        return super.put(pathPattern);
    }

    @Override
    public AbstractBindingBuilder patch(String pathPattern) {
        return super.patch(pathPattern);
    }

    @Override
    public AbstractBindingBuilder delete(String pathPattern) {
        return super.delete(pathPattern);
    }

    @Override
    public AbstractBindingBuilder options(String pathPattern) {
        return super.options(pathPattern);
    }

    @Override
    public AbstractBindingBuilder head(String pathPattern) {
        return super.head(pathPattern);
    }

    @Override
    public AbstractBindingBuilder trace(String pathPattern) {
        return super.trace(pathPattern);
    }

    @Override
    public AbstractBindingBuilder connect(String pathPattern) {
        return super.connect(pathPattern);
    }

    @Override
    public AbstractBindingBuilder methods(HttpMethod... methods) {
        return super.methods(methods);
    }

    @Override
    public AbstractBindingBuilder methods(Iterable<HttpMethod> methods) {
        return super.methods(methods);
    }

    @Override
    public AbstractBindingBuilder consumes(MediaType... consumeTypes) {
        return super.consumes(consumeTypes);
    }

    @Override
    public AbstractBindingBuilder consumes(Iterable<MediaType> consumeTypes) {
        return super.consumes(consumeTypes);
    }

    @Override
    public AbstractBindingBuilder produces(MediaType... produceTypes) {
        return super.produces(produceTypes);
    }

    @Override
    public AbstractBindingBuilder produces(Iterable<MediaType> produceTypes) {
        return super.produces(produceTypes);
    }

    @Override
    public AbstractBindingBuilder matchesParams(String... paramPredicates) {
        return super.matchesParams(paramPredicates);
    }

    @Override
    public AbstractBindingBuilder matchesParams(Iterable<String> paramPredicates) {
        return super.matchesParams(paramPredicates);
    }

    @Override
    public AbstractBindingBuilder matchesParams(String paramName, Predicate<? super String> valuePredicate) {
        return super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public AbstractBindingBuilder matchesHeaders(String... headerPredicates) {
        return super.matchesHeaders(headerPredicates);
    }

    @Override
    public AbstractBindingBuilder matchesHeaders(Iterable<String> headerPredicates) {
        return super.matchesHeaders(headerPredicates);
    }

    @Override
    public AbstractBindingBuilder matchesHeaders(CharSequence headerName,
                                                 Predicate<? super String> valuePredicate) {
        return super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public AbstractBindingBuilder addRoute(Route route) {
        return super.addRoute(route);
    }

    @Override
    public AbstractBindingBuilder exclude(String pathPattern) {
        return super.exclude(pathPattern);
    }

    @Override
    public AbstractBindingBuilder exclude(Route excludedRoute) {
        return super.exclude(excludedRoute);
    }

    /**
     * Sets the {@code decorator} and returns {@link ServerBuilder} that this
     * {@link DecoratingServiceBindingBuilder} was created from.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    public DefaultContextPathServicesBuilder<T> build(Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(decorator, "decorator");
        buildRouteList().forEach(
                route -> builder.addRouteDecoratingService(new RouteDecoratingService(route, decorator)));
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
