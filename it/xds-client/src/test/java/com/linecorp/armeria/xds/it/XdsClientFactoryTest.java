/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.Scheme;

class XdsClientFactoryTest {

    @Test
    void defaultFactorySupportsXdsScheme() {
        final ClientFactory factory = ClientFactory.ofDefault();
        final Scheme xdsScheme = Scheme.tryParse("xds");
        assertThat(xdsScheme).isNotNull();
        assertThat(factory.supportedSchemes()).contains(xdsScheme);
    }

    @Test
    void xdsSchemeInSupportedSchemes() {
        final ClientFactory factory = ClientFactory.ofDefault();
        final boolean hasXds = factory.supportedSchemes().stream()
                                      .anyMatch(s -> "xds".equals(s.discoveryProtocol()));
        assertThat(hasXds).isTrue();
    }

    @Test
    void xdsUriParsing() {
        final URI uri = URI.create("xds:///my-listener");
        assertThat(uri.getScheme()).isEqualTo("xds");
        assertThat(uri.getRawPath()).isEqualTo("/my-listener");
        assertThat(uri.getAuthority()).isNull();
    }

    @Test
    void validateUriAcceptsXdsScheme() {
        final ClientFactory factory = ClientFactory.ofDefault();
        final URI uri = URI.create("xds:///my-listener");
        final URI validated = factory.validateUri(uri);
        assertThat(validated).isNotNull();
        assertThat(validated.getScheme()).isEqualTo("xds");
        assertThat(validated.getRawPath()).isEqualTo("/my-listener");
    }
}
