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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;

class XdsSchemeTest {

    @Test
    void tryParse_xds() {
        final Scheme scheme = Scheme.tryParse("xds");
        assertThat(scheme).isNotNull();
        assertThat(scheme.serializationFormat()).isEqualTo(SerializationFormat.NONE);
        assertThat(scheme.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(scheme.discoveryProtocol()).isEqualTo("xds");
    }

    @Test
    void tryParse_gproto_xds() {
        final Scheme scheme = Scheme.tryParse("gproto+xds");
        assertThat(scheme).isNotNull();
        assertThat(scheme.serializationFormat()).isEqualTo(GrpcSerializationFormats.PROTO);
        assertThat(scheme.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(scheme.discoveryProtocol()).isEqualTo("xds");
        assertThat(scheme.uriText()).isEqualTo("gproto+xds");
        assertThat(scheme.shortUriText()).isEqualTo("gproto+xds");
    }

    @Test
    void tryParse_xds_caseInsensitive() {
        final Scheme lower = Scheme.tryParse("xds");
        final Scheme upper = Scheme.tryParse("XDS");
        assertThat(lower).isNotNull();
        assertThat(upper).isSameAs(lower);
    }

    @Test
    void tryParse_xds_cachedIdentity() {
        final Scheme first = Scheme.tryParse("xds");
        final Scheme second = Scheme.tryParse("xds");
        assertThat(first).isSameAs(second);
    }

    @Test
    void tryParse_gproto_xds_cachedIdentity() {
        final Scheme first = Scheme.tryParse("gproto+xds");
        final Scheme second = Scheme.tryParse("gproto+xds");
        assertThat(first).isSameAs(second);
    }

    @Test
    void uriText_xds() {
        final Scheme scheme = Scheme.tryParse("xds");
        assertThat(scheme).isNotNull();
        assertThat(scheme.uriText()).isEqualTo("xds");
        assertThat(scheme.shortUriText()).isEqualTo("xds");
    }

    @Test
    void of_withXdsDiscoveryProtocol() {
        final Scheme scheme = Scheme.of(SerializationFormat.NONE, "xds");
        assertThat(scheme.discoveryProtocol()).isEqualTo("xds");
        assertThat(scheme.uriText()).isEqualTo("xds");

        // Same instance from tryParse
        assertThat(Scheme.tryParse("xds")).isSameAs(scheme);
    }

    @Test
    void existingSchemesUnaffected() {
        final Scheme http = Scheme.tryParse("http");
        assertThat(http).isNotNull();
        assertThat(http.discoveryProtocol()).isNull();
        assertThat(http.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(http.serializationFormat()).isEqualTo(SerializationFormat.NONE);

        final Scheme https = Scheme.tryParse("https");
        assertThat(https).isNotNull();
        assertThat(https.discoveryProtocol()).isNull();
    }
}
