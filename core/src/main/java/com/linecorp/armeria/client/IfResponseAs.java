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

package com.linecorp.armeria.client;

import java.util.function.Predicate;

public class IfResponseAs<T, R, V> {
    private final Predicate<R> predicate;
    private ResponseAs<R, V> then;
    private ResponseAs<T, R> delegate;

    IfResponseAs(ResponseAs<T, R> delegate, Predicate<R> predicate) {
        this.delegate = delegate;
        this.predicate = predicate;
    }

    AggregatedElseResponseAs<V> then(ResponseAs<R, V> then) {
        this.then = then;
        return null;
    }

    public ResponseAs<T, R> delegate() {
        return delegate;
    }
}
