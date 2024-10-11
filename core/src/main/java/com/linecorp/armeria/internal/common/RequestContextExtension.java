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

package com.linecorp.armeria.internal.common;

import com.linecorp.armeria.common.AttributesGetters;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.util.AttributeKey;

/**
 * This class exposes extension methods for {@link RequestContext}
 * which are used internally by Armeria but aren't intended for public usage.
 */
public interface RequestContextExtension extends RequestContext {

    /**
     * Returns the {@link AttributesGetters} which stores the pairs of an {@link AttributeKey} and an object
     * set via {@link #setAttr(AttributeKey, Object)}.
     */
    AttributesGetters attributes();

    /**
     * Returns the original {@link Request} that is specified when this {@link RequestContext} is created.
     */
    Request originalRequest();

    void sessionProtocol(SessionProtocol protocol);
}
