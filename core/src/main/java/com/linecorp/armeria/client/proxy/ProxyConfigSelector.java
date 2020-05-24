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

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ProxyConfigSelector {
    private static final Logger logger = LoggerFactory.getLogger(ProxyConfigSelector.class);

    public abstract ProxyConfig select(URI uri);

    public abstract void connectFailed(URI uri, SocketAddress sa, IOException ioe);

    static class WrappingProxyConfigSelector extends ProxyConfigSelector {

        final ProxySelector proxySelector;

        WrappingProxyConfigSelector(ProxySelector proxySelector) {
            this.proxySelector = proxySelector;
        }

        @Override
        public ProxyConfig select(URI uri) {
            try {
                final List<Proxy> proxies = proxySelector.select(uri);
                if (proxies == null || proxies.isEmpty()) {
                    return ProxyConfig.direct();
                }

                final Proxy proxy = proxies.get(0);

                if (proxies.size() > 1) {
                    logger.debug("Using the first proxy <{}> out of <{}>.", proxy, proxies);
                }

                return ProxyConfig.fromProxy(proxy);
            } catch (Exception e) {
                logger.warn("ProxySelector.select failed to select a uri: ", e);
                return ProxyConfig.direct();
            }
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            try {
                proxySelector.connectFailed(uri, sa, ioe);
            } catch (Exception e) {
                logger.warn("ProxySelector.connectFailed throws: ", e);
            }
        }
    }
}
