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

package com.linecorp.armeria.xds;

import java.util.EnumMap;
import java.util.Map;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class CompositeSotwXdsStream implements SafeCloseable {

    private final Map<XdsType, SotwXdsStream> streamMap = new EnumMap<>(XdsType.class);

    SotwXdsStream() {
    }

    public CompositeSotwXdsStream(GrpcClientBuilder clientBuilder, Node node, Backoff backoff,
                                  EventExecutor eventLoop, XdsResponseHandler handler,
                                  SubscriberStorage subscriberStorage) {

    }

    @Override
    public void close() {

    }

    static class SotwXdsStream {

    }
}
