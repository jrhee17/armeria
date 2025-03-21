/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.common.HttpMethod.CONNECT;
import static com.linecorp.armeria.common.HttpMethod.DELETE;
import static com.linecorp.armeria.common.HttpMethod.GET;
import static com.linecorp.armeria.common.HttpMethod.HEAD;
import static com.linecorp.armeria.common.HttpMethod.OPTIONS;
import static com.linecorp.armeria.common.HttpMethod.PATCH;
import static com.linecorp.armeria.common.HttpMethod.POST;
import static com.linecorp.armeria.common.HttpMethod.PUT;
import static com.linecorp.armeria.common.HttpMethod.TRACE;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

/**
 * A skeletal {@link HttpService} for easier HTTP service implementation.
 *
 * <p>This class provides the methods that handles the HTTP requests of the methods their names signify.
 * For example, {@link #doGet(ServiceRequestContext, HttpRequest) doGet()} method handles a
 * {@code GET} request.
 * <ul>
 *   <li>{@link #doOptions(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doGet(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doHead(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doPost(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doPut(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doPatch(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doDelete(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doTrace(ServiceRequestContext, HttpRequest)}</li>
 * </ul>
 * These methods reject requests with a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response
 * by default. Override one of them to handle requests properly.
 */
public abstract class AbstractHttpService implements HttpService {

    /**
     * Serves the specified {@link HttpRequest} by delegating it to the matching {@code 'doMETHOD()'} method.
     */
    @Override
    public final HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpMethod method = req.method();
        if (method.equals(OPTIONS)) {
            return doOptions(ctx, req);
        } else if (method.equals(GET)) {
            return doGet(ctx, req);
        } else if (method.equals(HEAD)) {
            return doHead(ctx, req);
        } else if (method.equals(POST)) {
            return doPost(ctx, req);
        } else if (method.equals(PUT)) {
            return doPut(ctx, req);
        } else if (method.equals(PATCH)) {
            return doPatch(ctx, req);
        } else if (method.equals(DELETE)) {
            return doDelete(ctx, req);
        } else if (method.equals(TRACE)) {
            return doTrace(ctx, req);
        } else if (method.equals(CONNECT)) {
            return doConnect(ctx, req);
        }
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles an {@link HttpMethod#OPTIONS OPTIONS} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doOptions(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#GET GET} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#HEAD HEAD} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#POST POST} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#PUT PUT} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPut(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#PATCH PATCH} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPatch(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#DELETE DELETE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doDelete(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#TRACE TRACE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doTrace(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#CONNECT CONNECT} request. Note that Armeria handles only a {@code CONNECT}
     * request with a {@code :protocol} HTTP/2 pseudo header, as defined in <a
     * href="https://datatracker.ietf.org/doc/html/rfc8441#section-4">RFC8441, Bootstrapping WebSockets with
     * HTTP/2</a>. This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response
     * by default.
     */
    protected HttpResponse doConnect(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
