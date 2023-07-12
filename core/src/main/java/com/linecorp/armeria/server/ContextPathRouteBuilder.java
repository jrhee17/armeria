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

import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

public interface ContextPathRouteBuilder<T> {
//    T withRoute(Consumer<? super V> customizer);

    AbstractServiceBindingBuilder route();

    AbstractBindingBuilder routeDecorator();

    T serviceUnder(String pathPrefix, HttpService service);

    T service(String pathPattern, HttpService service);

    T service(Route route, HttpService service);

    T service(HttpServiceWithRoutes serviceWithRoutes,
              Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators);

    T service(HttpServiceWithRoutes serviceWithRoutes,
              Function<? super HttpService, ? extends HttpService>... decorators);

    T annotatedService(Object service);

    T annotatedService(Object service,
                                   Object... exceptionHandlersAndConverters);

    T annotatedService(Object service,
                       Function<? super HttpService, ? extends HttpService> decorator,
                       Object... exceptionHandlersAndConverters);

    T annotatedService(String pathPrefix, Object service);

    T annotatedService(String pathPrefix, Object service,
                                   Object... exceptionHandlersAndConverters);

    T annotatedService(String pathPrefix, Object service,
                                   Function<? super HttpService, ? extends HttpService> decorator,
                                   Object... exceptionHandlersAndConverters);

    T annotatedService(String pathPrefix, Object service,
                                   Iterable<?> exceptionHandlersAndConverters);

    T annotatedService(String pathPrefix, Object service,
                                   Function<? super HttpService, ? extends HttpService> decorator,
                                   Iterable<?> exceptionHandlersAndConverters);

    T annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions);

    AnnotatedServiceConfigSetters annotatedService();

    T decorator(Function<? super HttpService, ? extends HttpService> decorator);

    T decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    T decorator(
            String pathPattern, Function<? super HttpService, ? extends HttpService> decorator);

    T decorator(
            String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    T decorator(
            Route route, Function<? super HttpService, ? extends HttpService> decorator);

    T decorator(
            Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    T decoratorUnder(
            String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    T decoratorUnder(String prefix,
                     Function<? super HttpService, ? extends HttpService> decorator);
}
