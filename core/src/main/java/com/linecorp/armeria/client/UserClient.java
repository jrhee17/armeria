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
package com.linecorp.armeria.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A base class for implementing a user's entry point for sending a {@link Request}.
 *
 * <p>It provides the utility methods for easily forwarding a {@link Request} from a user to a {@link Client}.
 *
 * <p>Note that this class is not a subtype of {@link Client}, although its name may mislead.
 *
 * @param <I> the request type
 * @param <O> the response type
 */
public abstract class UserClient<I extends Request, O extends Response>
        extends AbstractUnwrappable<Client<I, O>>
        implements ClientBuilderParams {

    private final ClientBuilderParams params;
    private final MeterRegistry meterRegistry;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;

    /**
     * Creates a new instance.
     *
     * @param params the parameters used for constructing the client
     * @param delegate the {@link Client} that will process {@link Request}s
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param futureConverter the {@link Function} that converts a {@link CompletableFuture} of response
     *                        into a response, e.g. {@link HttpResponse#of(CompletionStage)}
     *                        and {@link RpcResponse#from(CompletionStage)}
     * @param errorResponseFactory the {@link BiFunction} that returns a new response failed with
     *                             the given exception
     */
    protected UserClient(ClientBuilderParams params, Client<I, O> delegate, MeterRegistry meterRegistry,
                         Function<CompletableFuture<O>, O> futureConverter,
                         BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        super(delegate);
        this.params = params;
        this.meterRegistry = meterRegistry;
        this.futureConverter = futureConverter;
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public final Scheme scheme() {
        return params.scheme();
    }

    @Nullable
    @Override
    public final EndpointGroup endpointGroup() {
        return params.endpointGroup();
    }

    @Override
    public RequestExecutionFactory executionFactory() {
        return params.executionFactory();
    }

    @Override
    public final String absolutePathRef() {
        return params.absolutePathRef();
    }

    @Override
    public final URI uri() {
        return params.uri();
    }

    @Override
    public final Class<?> clientType() {
        return params.clientType();
    }

    @Override
    public final ClientOptions options() {
        return params.options();
    }

    /**
     * Executes the specified {@link Request} via the delegate.
     *
     * @param protocol the {@link SessionProtocol} to use
     * @param method the method of the {@link Request}
     * @param reqTarget the {@link RequestTarget} of the {@link Request}
     * @param req the {@link Request}
     */
    protected final O execute(SessionProtocol protocol, HttpMethod method, RequestTarget reqTarget, I req) {
        return execute(protocol, method, reqTarget, req, RequestOptions.of());
    }

    /**
     * Executes the specified {@link Request} via the delegate.
     *
     * @param protocol the {@link SessionProtocol} to use
     * @param method the method of the {@link Request}
     * @param reqTarget the {@link RequestTarget} of the {@link Request}
     * @param req the {@link Request}
     * @param requestOptions the {@link RequestOptions} of the {@link Request}
     */
    protected final O execute(SessionProtocol protocol, HttpMethod method, RequestTarget reqTarget,
                              I req, RequestOptions requestOptions) {
        return execute(protocol, endpointGroup(), method, reqTarget, req, requestOptions);
    }

    /**
     * Executes the specified {@link Request} via the delegate.
     *
     * @param protocol the {@link SessionProtocol} to use
     * @param endpointGroup the {@link EndpointGroup} of the {@link Request}
     * @param method the method of the {@link Request}
     * @param reqTarget the {@link RequestTarget} of the {@link Request}
     * @param req the {@link Request}
     */
    protected final O execute(SessionProtocol protocol, @Nullable EndpointGroup endpointGroup,
                              HttpMethod method, RequestTarget reqTarget, I req) {
        return execute(protocol, endpointGroup, method, reqTarget, req, RequestOptions.of());
    }

    /**
     * Executes the specified {@link Request} via the delegate.
     *
     * @param protocol the {@link SessionProtocol} to use
     * @param endpointGroup the {@link EndpointGroup} of the {@link Request}
     * @param method the method of the {@link Request}
     * @param reqTarget the {@link RequestTarget} of the {@link Request}
     * @param req the {@link Request}
     * @param requestOptions the {@link RequestOptions} of the {@link Request}
     */
    protected final O execute(SessionProtocol protocol, @Nullable EndpointGroup endpointGroup,
                              HttpMethod method, RequestTarget reqTarget, I req,
                              RequestOptions requestOptions) {

        final HttpRequest httpReq;
        final RpcRequest rpcReq;

        if (req instanceof HttpRequest) {
            httpReq = (HttpRequest) req;
            rpcReq = null;
        } else {
            httpReq = HttpRequest.of(method, "/");
            rpcReq = (RpcRequest) req;
        }

        final RequestExecution execution =
                params.executionFactory().prepare(httpReq, rpcReq, reqTarget, requestOptions,
                                                  params.options());
        try {
            return execution.execute(unwrap(), req);
        } catch (Throwable t) {
            return errorResponseFactory.apply(execution.ctx(), t);
        }
    }
}
