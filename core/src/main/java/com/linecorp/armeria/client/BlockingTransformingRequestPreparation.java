/*
 * Copyright 2022 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Prepares and executes a new {@link HttpRequest} for a {@link WebClient} or {@link BlockingWebClient}, and
 * transforms an {@link HttpResponse} into the {@code T} type object.
 */
@UnstableApi
public class BlockingTransformingRequestPreparation<T, R> extends TransformingRequestPreparation<T, R> {

    Predicate<HttpStatus> successFunction;
    Function<? super Throwable, ?> errorHandler;
    private final WebRequestPreparationSetters<T> delegate;
    private ResponseAs<T, R> responseAs;

    BlockingTransformingRequestPreparation(WebRequestPreparationSetters<T> delegate, ResponseAs<T, R> responseAs,
                                           Predicate<HttpStatus> successFunction,
                                           Function<? super Throwable, ?> errorHandler) {
        super(delegate, responseAs);
        this.delegate = delegate;
        this.responseAs = responseAs;
        this.successFunction = successFunction;
        this.errorHandler = errorHandler;
    }

    @Override
    public R execute() {
        try {
            return responseAs.as(delegate.execute());
        } catch (Exception e) {
            // unsafe cast
            return (R) errorHandler.apply(e);
        }
    }
}
