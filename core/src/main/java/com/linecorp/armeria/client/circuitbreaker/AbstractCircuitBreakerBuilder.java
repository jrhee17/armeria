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

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TBU.
 */
public abstract class AbstractCircuitBreakerBuilder {

    private List<CircuitBreakerListener> listeners = Collections.emptyList();

    /**
     * Adds a {@link CircuitBreakerListener}.
     */
    public AbstractCircuitBreakerBuilder listener(CircuitBreakerListener listener) {
        requireNonNull(listener, "listener");
        if (listeners.isEmpty()) {
            listeners = new ArrayList<>(3);
        }
        listeners.add(listener);
        return this;
    }

    /**
     * TBU.
     */
    protected List<CircuitBreakerListener> listeners() {
        return listeners;
    }
}
