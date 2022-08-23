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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * TBU.
 */
@UnstableApi
public abstract class AbstractCircuitBreakerMappingBuilder {

    private boolean perHost;
    private boolean perMethod;
    private boolean perPath;

    /**
     * TBU.
     */
    protected AbstractCircuitBreakerMappingBuilder() {}

    /**
     * Adds host dimension to the mapping Key.
     */
    public AbstractCircuitBreakerMappingBuilder perHost() {
        perHost = true;
        return this;
    }

    /**
     * Adds method dimension to the mapping Key.
     */
    public AbstractCircuitBreakerMappingBuilder perMethod() {
        perMethod = true;
        return this;
    }

    /**
     * Adds path dimension to the mapping Key.
     */
    public AbstractCircuitBreakerMappingBuilder perPath() {
        perPath = true;
        return this;
    }

    /**
     * TBU.
     */
    protected boolean isPerHost() {
        return perHost;
    }

    /**
     * TBU.
     */
    protected boolean isPerMethod() {
        return perMethod;
    }

    /**
     * TBU.
     */
    protected boolean isPerPath() {
        return perPath;
    }

    /**
     * TBU.
     */
    protected boolean validateMappingKeys() {
        return perHost || perMethod || perPath;
    }
}
