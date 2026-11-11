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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientTlsSpec;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.ImmediateExecutor;

class Http2CipherValidatingSslContextTest {

    @Test
    void usesRealEngineAndCachesSuccessfulValidation() {
        final SslContext delegate = mock(SslContext.class);
        final SSLEngine first = mock(SSLEngine.class);
        final SSLEngine second = mock(SSLEngine.class);
        when(delegate.newEngine(ByteBufAllocator.DEFAULT)).thenReturn(first, second);
        when(first.getEnabledProtocols()).thenReturn(new String[] { "TLSv1.3" });

        final SslContext context = Http2CipherValidatingSslContext.wrap(
                delegate, ImmutableSet.of("TLS_AES_128_GCM_SHA256"), false);
        verifyNoInteractions(delegate, first, second);

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT)).isSameAs(first);
        assertThat(context.newEngine(ByteBufAllocator.DEFAULT)).isSameAs(second);
        verify(delegate, times(2)).newEngine(ByteBufAllocator.DEFAULT);
        verify(first).getEnabledProtocols();
        verifyNoInteractions(second);
    }

    @ParameterizedTest
    @EnumSource(SslProvider.class)
    void validatesEveryEngineAndHandlerCreationPath(SslProvider provider) throws SSLException {
        assumeThat(SslProvider.isTlsv13Supported(provider)).isTrue();
        final SslContext delegate = newContext(provider, "TLS_AES_128_GCM_SHA256");
        final SslContext context = Http2CipherValidatingSslContext.wrap(
                delegate, ImmutableSet.copyOf(delegate.cipherSuites()), false);
        try {
            for (Function<SslContext, SSLEngine> factory : engineFactories()) {
                final SSLEngine engine = factory.apply(context);
                try {
                    assertThat(engine.getEnabledProtocols()).contains("TLSv1.3").doesNotContain("TLSv1.2");
                } finally {
                    ReferenceCountUtil.release(engine);
                }
                if (delegate instanceof ReferenceCounted) {
                    assertThat(((ReferenceCounted) delegate).refCnt()).isOne();
                }
            }
        } finally {
            ReferenceCountUtil.release(context);
        }
    }

    @ParameterizedTest
    @EnumSource(SslProvider.class)
    void rejectedEnginesAreReleasedForEveryCreationPath(SslProvider provider) throws SSLException {
        assumeThat(SslProvider.isTlsv13Supported(provider)).isTrue();
        final SslContext delegate = newContext(provider, "TLS_AES_256_GCM_SHA384");
        final SslContext context = Http2CipherValidatingSslContext.wrap(
                delegate, ImmutableSet.copyOf(delegate.cipherSuites()), false);
        try {
            for (Function<SslContext, SSLEngine> factory : engineFactories()) {
                assertThatThrownBy(() -> factory.apply(context))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("TLS_AES_128_GCM_SHA256 cipher suite enabled");
                if (delegate instanceof ReferenceCounted) {
                    // Creating an OpenSSL engine retains its context. A leaked engine leaves this at two.
                    assertThat(((ReferenceCounted) delegate).refCnt()).isOne();
                }
            }
        } finally {
            ReferenceCountUtil.release(context);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SslProvider.class, names = { "OPENSSL", "OPENSSL_REFCNT" })
    void forwardsReferenceCountingAndKeepsLiveEngineUsable(SslProvider provider) throws SSLException {
        final SslContext delegate = newContext(provider, "TLS_AES_128_GCM_SHA256");
        final SslContext context = Http2CipherValidatingSslContext.wrap(
                delegate, ImmutableSet.copyOf(delegate.cipherSuites()), false);
        final ReferenceCounted referenceCounted = (ReferenceCounted) context;
        final ReferenceCounted delegateReferenceCounted = (ReferenceCounted) delegate;
        SSLEngine engine = null;
        boolean contextReleased = false;
        try {
            assertThat(referenceCounted.retain()).isSameAs(context);
            assertThat(delegateReferenceCounted.refCnt()).isEqualTo(2);
            assertThat(referenceCounted.release()).isFalse();
            assertThat(referenceCounted.retain(2)).isSameAs(context);
            assertThat(delegateReferenceCounted.refCnt()).isEqualTo(3);
            assertThat(referenceCounted.release(2)).isFalse();
            assertThat(referenceCounted.touch()).isSameAs(context);
            assertThat(referenceCounted.touch("test")).isSameAs(context);

            engine = context.newEngine(ByteBufAllocator.DEFAULT);
            assertThat(delegateReferenceCounted.refCnt()).isEqualTo(2);
            assertThat(referenceCounted.release()).isFalse();
            contextReleased = true;
            assertThat(delegateReferenceCounted.refCnt()).isOne();
            assertThat(engine.getEnabledProtocols()).contains("TLSv1.3");
        } finally {
            ReferenceCountUtil.release(engine);
            if (!contextReleased) {
                ReferenceCountUtil.release(context);
            }
        }
        assertThat(delegateReferenceCounted.refCnt()).isZero();
    }

    @Test
    void http1ContextIsNotWrapped() {
        final SslContext context = SslContextUtil.toSslContext(
                ClientTlsSpec.builder().alpnProtocols(SslContextUtil.DEFAULT_HTTP1_ALPN_PROTOCOLS).build());
        try {
            assertThat(context).isNotInstanceOf(Http2CipherValidatingSslContext.class);
        } finally {
            ReferenceCountUtil.release(context);
        }
    }

    private static SslContext newContext(SslProvider provider, String cipher) throws SSLException {
        return SslContextBuilder.forClient().sslProvider(provider)
                                .protocols("TLSv1.3")
                                .ciphers(ImmutableList.of(cipher))
                                .build();
    }

    private static List<Function<SslContext, SSLEngine>> engineFactories() {
        return ImmutableList.of(
                ctx -> ctx.newEngine(ByteBufAllocator.DEFAULT),
                ctx -> ctx.newEngine(ByteBufAllocator.DEFAULT, "localhost", 443),
                ctx -> ctx.newHandler(ByteBufAllocator.DEFAULT).engine(),
                ctx -> ctx.newHandler(ByteBufAllocator.DEFAULT, "localhost", 443).engine(),
                ctx -> ctx.newHandler(ByteBufAllocator.DEFAULT, ImmediateExecutor.INSTANCE).engine(),
                ctx -> ctx.newHandler(ByteBufAllocator.DEFAULT, "localhost", 443,
                                      ImmediateExecutor.INSTANCE).engine());
    }
}
