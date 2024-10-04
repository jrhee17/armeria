/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import com.linecorp.armeria.internal.client.ResponseFactory;
import com.linecorp.armeria.xds.internal.common.XdsAttributeKeys;

import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;

final class XdsClientAttributeKeys {

    public static final AttributeKey<ResponseFactory<?>> RESPONSE_FACTORY =
            AttributeKey.valueOf(XdsAttributeKeys.class, "RESPONSE_FACTORY");
    public static final AttributeKey<EventExecutor> TEMPORARY_EVENT_LOOP =
            AttributeKey.valueOf(XdsAttributeKeys.class, "TEMPORARY_EVENT_LOOP");
    public static final AttributeKey<Router> ROUTER =
            AttributeKey.valueOf(XdsAttributeKeys.class, "ROUTER");

    private XdsClientAttributeKeys() {}
}
