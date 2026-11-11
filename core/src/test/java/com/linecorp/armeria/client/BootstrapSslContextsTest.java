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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.TlsEngineType;

import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCounted;

class BootstrapSslContextsTest {

    @Test
    void releasesValidationEngineAndBothDefaultContexts() {
        final ClientFactoryOptions options = ClientFactoryOptions.of(
                ClientFactoryOptions.TLS_ENGINE_TYPE.newValue(TlsEngineType.OPENSSL));
        final BootstrapSslContexts contexts =
                new BootstrapSslContexts(ClientTlsSpec.builder().build(), options);
        final SslContext http1 = contexts.getSslContext(HttpPreference.HTTP1_REQUIRED);
        final SslContext http2 = contexts.getSslContext(HttpPreference.HTTP2_REQUIRED);
        try {
            // A leaked validation engine would retain the HTTP/2 context and leave its refCnt at two.
            assertThat(((ReferenceCounted) http1).refCnt()).isOne();
            assertThat(((ReferenceCounted) http2).refCnt()).isOne();
        } finally {
            contexts.release();
        }
        assertThat(((ReferenceCounted) http1).refCnt()).isZero();
        assertThat(((ReferenceCounted) http2).refCnt()).isZero();
    }
}
