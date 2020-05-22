/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.proxy;

import java.net.SocketAddress;
import java.net.URI;

/**
 * TODO: add javadocs.
 */
public final class StaticProxyConfigSelector extends ProxyConfigSelector {

    /**
     * TODO: add javadocs.
     */
    public static StaticProxyConfigSelector of(ProxyConfig proxyConfig) {
        return new StaticProxyConfigSelector(proxyConfig);
    }

    private final ProxyConfig proxyConfig;

    private StaticProxyConfigSelector(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Override
    public ProxyConfig select(URI uri) {
        return proxyConfig;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
        // do nothing
    }
}
