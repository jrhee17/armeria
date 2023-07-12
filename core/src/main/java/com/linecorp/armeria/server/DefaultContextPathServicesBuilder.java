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

import static com.linecorp.armeria.server.ServerBuilder.decorate;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

public class DefaultContextPathServicesBuilder<T> implements ContextPathRouteBuilder<T> {

    private final List<ServiceConfigSetters> serviceConfigSetters = new ArrayList<>();

    private final T parent;
    public DefaultContextPathServicesBuilder(T parent, String s) {
        this.parent = parent;
    }

    @Override
    public AbstractServiceBindingBuilder route() {
        return null;
    }

    @Override
    public AbstractBindingBuilder routeDecorator() {
        return null;
    }

    @Override
    public T serviceUnder(String pathPrefix, HttpService service) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        final HttpServiceWithRoutes serviceWithRoutes = service.as(HttpServiceWithRoutes.class);
        if (serviceWithRoutes != null) {
            serviceWithRoutes.routes().forEach(route -> {
                final ServiceConfigBuilder serviceConfigBuilder =
                        new ServiceConfigBuilder(route.withPrefix(pathPrefix), service);
                serviceConfigBuilder.addMappedRoute(route);
                addServiceConfigSetters(serviceConfigBuilder);
            });
        } else {
            service(Route.builder().pathPrefix(pathPrefix).build(), service);
        }
        return parent;
    }

    @Override
    public T service(String pathPattern, HttpService service) {
        return service(Route.builder().path(pathPattern).build(), service);
    }

    @Override
    public T service(Route route, HttpService service) {
        return addServiceConfigSetters(new ServiceConfigBuilder(route, service));
    }

    @Override
    public T service(HttpServiceWithRoutes serviceWithRoutes,
                     Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(serviceWithRoutes, "serviceWithRoutes");
        requireNonNull(serviceWithRoutes.routes(), "serviceWithRoutes.routes()");
        requireNonNull(decorators, "decorators");

        final HttpService decorated = decorate(serviceWithRoutes, decorators);
        serviceWithRoutes.routes().forEach(route -> service(route, decorated));
        return parent;
    }

    @Override
    public T service(HttpServiceWithRoutes serviceWithRoutes,
                     Function<? super HttpService, ? extends HttpService>... decorators) {
        return service(serviceWithRoutes, ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    @Override
    public T annotatedService(Object service) {
        return annotatedService("/", service, Function.identity(), ImmutableList.of());
    }

    @Override
    public T annotatedService(Object service, Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public T annotatedService(Object service, Function<? super HttpService, ? extends HttpService> decorator,
                              Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public T annotatedService(String pathPrefix, Object service) {
        return annotatedService(pathPrefix, service, Function.identity(), ImmutableList.of());
    }

    @Override
    public T annotatedService(String pathPrefix, Object service, Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public T annotatedService(String pathPrefix, Object service,
                              Function<? super HttpService, ? extends HttpService> decorator,
                              Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public T annotatedService(String pathPrefix, Object service, Iterable<?> exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                requireNonNull(exceptionHandlersAndConverters,
                                               "exceptionHandlersAndConverters"));
    }

    @Override
    public T annotatedService(String pathPrefix, Object service,
                              Function<? super HttpService, ? extends HttpService> decorator,
                              Iterable<?> exceptionHandlersAndConverters) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlersAndConverters, "exceptionHandlersAndConverters");
        final AnnotatedServiceExtensions configurator =
                AnnotatedServiceExtensions
                        .ofExceptionHandlersAndConverters(exceptionHandlersAndConverters);
        return annotatedService(pathPrefix, service, decorator, configurator.exceptionHandlers(),
                                configurator.requestConverters(), configurator.responseConverters());
    }

    @Override
    public T annotatedService(String pathPrefix, Object service,
                              Function<? super HttpService, ? extends HttpService> decorator,
                              Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
                              Iterable<? extends RequestConverterFunction> requestConverterFunctions,
                              Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        return annotatedService().pathPrefix(pathPrefix)
                                 .decorator(decorator)
                                 .exceptionHandlers(exceptionHandlerFunctions)
                                 .requestConverters(requestConverterFunctions)
                                 .responseConverters(responseConverterFunctions)
                                 .build(service);
    }

    @Override
    public DefaultContextPathAnnotatedServiceConfigSetters<T> annotatedService() {
        return null;
    }

    @Override
    public T decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        return null;
    }

    @Override
    public T decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return null;
    }

    @Override
    public T decorator(String pathPattern, Function<? super HttpService, ? extends HttpService> decorator) {
        return null;
    }

    @Override
    public T decorator(String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return null;
    }

    @Override
    public T decorator(Route route, Function<? super HttpService, ? extends HttpService> decorator) {
        return null;
    }

    @Override
    public T decorator(Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return null;
    }

    @Override
    public T decoratorUnder(String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return null;
    }

    @Override
    public T decoratorUnder(String prefix, Function<? super HttpService, ? extends HttpService> decorator) {
        return null;
    }

    T addServiceConfigSetters(ServiceConfigSetters serviceConfigSetters) {
        this.serviceConfigSetters.add(serviceConfigSetters);
        return parent;
    }
}
