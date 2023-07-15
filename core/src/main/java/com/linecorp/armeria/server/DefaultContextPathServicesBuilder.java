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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.internal.server.RouteDecoratingService;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

public final class DefaultContextPathServicesBuilder<T>
        implements ContextPathRouteBuilder<DefaultContextPathServicesBuilder<T>> {

    private final LinkedList<RouteDecoratingService> routeDecoratingServices = new LinkedList<>();
    private final List<ServiceConfigSetters> serviceConfigSetters = new ArrayList<>();
    private final Set<String> contextPaths;

    private final T parent;
    private final Consumer<ServiceConfigSetters> consumer;

    public DefaultContextPathServicesBuilder(T parent) {
        this.parent = parent;
        contextPaths = Collections.singleton("/");
        this.consumer = serviceConfigSetters::add;
    }

    public DefaultContextPathServicesBuilder(T parent, Consumer<ServiceConfigSetters> consumer) {
        this.parent = parent;
        contextPaths = Collections.singleton("/");
        this.consumer = consumer;
    }

    public DefaultContextPathServicesBuilder(T parent, String ...contextPaths) {
        this.parent = parent;
        this.contextPaths = ImmutableSet.copyOf(contextPaths);
        this.consumer = serviceConfigSetters::add;
    }

    public DefaultContextPathServicesBuilder(T parent, Consumer<ServiceConfigSetters> consumer,
                                             String ...contextPaths) {
        this.parent = parent;
        this.contextPaths = ImmutableSet.copyOf(contextPaths);
        this.consumer = consumer;
    }

    LinkedList<RouteDecoratingService> routeDecoratingServices() {
        return routeDecoratingServices;
    }

    List<ServiceConfigSetters> serviceConfigSetters() {
        return serviceConfigSetters;
    }

    @Override
    public ContextPathServiceBindingBuilder<T> route() {
        return new ContextPathServiceBindingBuilder<>(this, contextPaths);
    }

    @Override
    public ContextPathDecoratingBindingBuilder<T> routeDecorator() {
        return new ContextPathDecoratingBindingBuilder<>(this);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> serviceUnder(String pathPrefix, HttpService service) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        final HttpServiceWithRoutes serviceWithRoutes = service.as(HttpServiceWithRoutes.class);
        for (String contextPath: contextPaths) {
            if (serviceWithRoutes != null) {
                serviceWithRoutes.routes().forEach(route -> {
                    final ServiceConfigBuilder serviceConfigBuilder =
                            new ServiceConfigBuilder(route.withPrefix(pathPrefix)
                                                          .withPrefix(contextPath), service);
                    serviceConfigBuilder.addMappedRoute(route);
                    addServiceConfigSetters(serviceConfigBuilder);
                });
            } else {
                service(Route.builder().pathPrefix(pathPrefix).build(), service);
            }
        }
        return this;
    }

    @Override
    public DefaultContextPathServicesBuilder<T> service(String pathPattern, HttpService service) {
        return service(Route.builder().path(pathPattern).build(), service);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> service(Route route, HttpService service) {
        for (String contextPath: contextPaths) {
            addServiceConfigSetters(new ServiceConfigBuilder(route.withPrefix(contextPath), service));
        }
        return this;
    }

    @Override
    public DefaultContextPathServicesBuilder<T> service(HttpServiceWithRoutes serviceWithRoutes,
                                                        Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(serviceWithRoutes, "serviceWithRoutes");
        requireNonNull(serviceWithRoutes.routes(), "serviceWithRoutes.routes()");
        requireNonNull(decorators, "decorators");

        final HttpService decorated = decorate(serviceWithRoutes, decorators);
        serviceWithRoutes.routes().forEach(route -> service(route, decorated));
        return this;
    }

    @SafeVarargs
    @Override
    public final DefaultContextPathServicesBuilder<T> service(HttpServiceWithRoutes serviceWithRoutes,
                                                        Function<? super HttpService, ? extends HttpService>... decorators) {
        return service(serviceWithRoutes, ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(Object service) {
        return annotatedService("/", service, Function.identity(), ImmutableList.of());
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(Object service, Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(Object service, Function<? super HttpService, ? extends HttpService> decorator,
                                                                 Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(String pathPrefix, Object service) {
        return annotatedService(pathPrefix, service, Function.identity(), ImmutableList.of());
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(String pathPrefix, Object service, Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(String pathPrefix, Object service,
                                                                 Function<? super HttpService, ? extends HttpService> decorator,
                                                                 Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(String pathPrefix, Object service, Iterable<?> exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                requireNonNull(exceptionHandlersAndConverters,
                                               "exceptionHandlersAndConverters"));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> annotatedService(String pathPrefix, Object service,
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
    public DefaultContextPathServicesBuilder<T> annotatedService(String pathPrefix, Object service,
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
        return new DefaultContextPathAnnotatedServiceConfigSetters<>(this, contextPaths);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.ofCatchAll(), decorator);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.ofCatchAll(), decoratingHttpServiceFunction);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decorator(String pathPattern, Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().path(pathPattern).build(), decorator);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decorator(String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().path(pathPattern).build(), decoratingHttpServiceFunction);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decorator(Route route, Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(route, "route");
        requireNonNull(decorator, "decorator");
        return addRouteDecoratingService(new RouteDecoratingService(route, decorator));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decorator(Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return decorator(route, delegate -> new FunctionalDecoratingHttpService(
                delegate, decoratingHttpServiceFunction));
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decoratorUnder(String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decoratingHttpServiceFunction);
    }

    @Override
    public DefaultContextPathServicesBuilder<T> decoratorUnder(String prefix, Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decorator);
    }

    DefaultContextPathServicesBuilder<T> addServiceConfigSetters(ServiceConfigSetters serviceConfigSetters) {
        this.consumer.accept(serviceConfigSetters);
        return this;
    }

    DefaultContextPathServicesBuilder<T> addRouteDecoratingService(RouteDecoratingService routeDecoratingService) {
        if (Flags.useLegacyRouteDecoratorOrdering()) {
            // The first inserted decorator is applied first.
            routeDecoratingServices.addLast(routeDecoratingService);
        } else {
            // The last inserted decorator is applied first.
            routeDecoratingServices.addFirst(routeDecoratingService);
        }
        return this;
    }

    public T and() {
        return parent;
    }
}
