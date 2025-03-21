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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * HTTP request method.
 */
public final class HttpMethod {

    // Forked from Netty 4.1.34 at ff7484864b1785103cbc62845ff3a392c93822b7

    /**
     * The OPTIONS method which represents a request for information about the communication options
     * available on the request/response chain identified by the Request-URI. This method allows
     * the client to determine the options and/or requirements associated with a resource, or the
     * capabilities of a server, without implying a resource action or initiating a resource
     * retrieval.
     */
    public static final HttpMethod OPTIONS = new HttpMethod("OPTIONS");

    /**
     * The GET method which means retrieve whatever information (in the form of an entity) is identified
     * by the Request-URI.  If the Request-URI refers to a data-producing process, it is the
     * produced data which shall be returned as the entity in the response and not the source text
     * of the process, unless that text happens to be the output of the process.
     */
    public static final HttpMethod GET = new HttpMethod("GET");

    /**
     * The HEAD method which is identical to GET except that the server MUST NOT return a message-body
     * in the response.
     */
    public static final HttpMethod HEAD = new HttpMethod("HEAD");

    /**
     * The POST method which is used to request that the origin server accept the entity enclosed in the
     * request as a new subordinate of the resource identified by the Request-URI in the
     * Request-Line.
     */
    public static final HttpMethod POST = new HttpMethod("POST");

    /**
     * The PUT method which requests that the enclosed entity be stored under the supplied Request-URI.
     */
    public static final HttpMethod PUT = new HttpMethod("PUT");

    /**
     * The PATCH method which requests that a set of changes described in the
     * request entity be applied to the resource identified by the Request-URI.
     */
    public static final HttpMethod PATCH = new HttpMethod("PATCH");

    /**
     * The DELETE method which requests that the origin server delete the resource identified by the
     * Request-URI.
     */
    public static final HttpMethod DELETE = new HttpMethod("DELETE");

    /**
     * The TRACE method which is used to invoke a remote, application-layer loop-back of the request
     * message.
     */
    public static final HttpMethod TRACE = new HttpMethod("TRACE");

    /**
     * The CONNECT method which is used for a proxy that can dynamically switch to being a tunnel or for
     * <a href="https://datatracker.ietf.org/doc/rfc8441/">bootstrapping WebSockets with HTTP/2</a>.
     * Note that Armeria handles a {@code CONNECT} request only for bootstrapping WebSockets.
     */
    public static final HttpMethod CONNECT = new HttpMethod("CONNECT");

    /**
     * A special constant returned by {@link RequestHeaders#method()} to signify that a request has a method
     * not defined in this enum.
     */
    public static final HttpMethod UNKNOWN = new HttpMethod("UNKNOWN");

    private static final List<HttpMethod> allMethods;
    private static final Set<HttpMethod> knownMethods; // ImmutableEnumSet
    private static final Set<HttpMethod> idempotentMethods = Sets.newHashSet(GET, HEAD, PUT, DELETE);
    private static final Map<String, HttpMethod> nameToMethod;

    static {
        allMethods = ImmutableList.of(OPTIONS, GET, HEAD, POST, PUT, PATCH, DELETE, TRACE, CONNECT, UNKNOWN);
        knownMethods = ImmutableSet.of(OPTIONS, GET, HEAD, POST, PUT, PATCH, DELETE, TRACE, CONNECT);
        nameToMethod = allMethods.stream().collect(ImmutableMap.toImmutableMap(HttpMethod::name,
                                                                               Function.identity()));
    }

    private static final HttpMethod[] allMethodsArr = allMethods.toArray(new HttpMethod[0]);

    /**
     * Returns whether the specified {@link String} is one of the supported method names.
     *
     * @return {@code true} if supported. {@code false} otherwise.
     */
    public static boolean isSupported(String value) {
        requireNonNull(value, "value");
        switch (value) {
            case "OPTIONS":
            case "GET":
            case "HEAD":
            case "POST":
            case "PUT":
            case "PATCH":
            case "DELETE":
            case "TRACE":
            case "CONNECT":
                return true;
        }

        return false;
    }

    /**
     * Returns the <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotent</a>
     * HTTP methods - {@link #GET}, {@link #HEAD}, {@link #PUT} and {@link #DELETE}.
     */
    public static Set<HttpMethod> idempotentMethods() {
        return idempotentMethods;
    }

    /**
     * Returns all {@link HttpMethod}s except {@link #UNKNOWN}.
     */
    public static Set<HttpMethod> knownMethods() {
        return knownMethods;
    }

    /**
     * Parses the specified {@link String} into an {@link HttpMethod}. This method will return the same
     * {@link HttpMethod} instance for equal values of {@code method}. Note that this method will not
     * treat {@code "UNKNOWN"} as a valid value and thus will return {@code null} when {@code "UNKNOWN"}
     * is given.
     *
     * @return {@code null} if there is no such {@link HttpMethod} available
     */
    @Nullable
    public static HttpMethod tryParse(@Nullable String method) {
        if (method == null) {
            return null;
        }

        switch (method) {
            case "OPTIONS":
                return OPTIONS;
            case "GET":
                return GET;
            case "HEAD":
                return HEAD;
            case "POST":
                return POST;
            case "PUT":
                return PUT;
            case "PATCH":
                return PATCH;
            case "DELETE":
                return DELETE;
            case "TRACE":
                return TRACE;
            case "CONNECT":
                return CONNECT;
            default:
                return null;
        }
    }

    /**
     * TBU.
     */
    public static HttpMethod[] values() {
        return allMethodsArr;
    }

    private final String name;

    private HttpMethod(String name) {
        this.name = requireNonNull(name, "name");
    }

    /**
     * TBU.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * TBU.
     */
    public static HttpMethod valueOf(String name) {
        final HttpMethod method = nameToMethod.get(name);
        requireNonNull(method, "cannot find method: " + name);
        return method;
    }

    @Override
    public String toString() {
        return name;
    }
}
