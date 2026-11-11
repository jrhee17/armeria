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

import java.util.Set;

import javax.net.ssl.SSLEngine;

import com.google.common.collect.ImmutableSet;

import io.netty.handler.ssl.DelegatingSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

/**
 * Validates HTTP/2 ciphers once, using the enabled protocols of the first engine created for a context.
 */
class Http2CipherValidatingSslContext extends DelegatingSslContext {

    static SslContext wrap(SslContext delegate, Set<String> ciphers, boolean allowUnsafeCiphers) {
        if (delegate instanceof ReferenceCounted) {
            return new ReferenceCountedValidatingSslContext(delegate, ciphers, allowUnsafeCiphers);
        }
        return new Http2CipherValidatingSslContext(delegate, ciphers, allowUnsafeCiphers);
    }

    private final Set<String> ciphers;
    private final boolean allowUnsafeCiphers;
    private volatile boolean validated;

    private Http2CipherValidatingSslContext(SslContext delegate, Set<String> ciphers,
                                           boolean allowUnsafeCiphers) {
        super(delegate);
        this.ciphers = ImmutableSet.copyOf(ciphers);
        this.allowUnsafeCiphers = allowUnsafeCiphers;
    }

    @Override
    protected void initEngine(SSLEngine engine) {
        if (validated) {
            return;
        }

        boolean success = false;
        try {
            SslContextUtil.validateHttp2Ciphers(ciphers, allowUnsafeCiphers,
                                               ImmutableSet.copyOf(engine.getEnabledProtocols()));
            // Concurrent first engines can both validate. Publish success only after validation completes.
            validated = true;
            success = true;
        } finally {
            if (!success) {
                // DelegatingSslContext does not release an engine when initEngine() throws.
                ReferenceCountUtil.release(engine);
            }
        }
    }

    private static final class ReferenceCountedValidatingSslContext
            extends Http2CipherValidatingSslContext implements ReferenceCounted {

        private final ReferenceCounted delegate;

        ReferenceCountedValidatingSslContext(SslContext delegate, Set<String> ciphers,
                                            boolean allowUnsafeCiphers) {
            super(delegate, ciphers, allowUnsafeCiphers);
            this.delegate = (ReferenceCounted) delegate;
        }

        @Override
        public int refCnt() {
            return delegate.refCnt();
        }

        @Override
        public ReferenceCounted retain() {
            delegate.retain();
            return this;
        }

        @Override
        public ReferenceCounted retain(int increment) {
            delegate.retain(increment);
            return this;
        }

        @Override
        public ReferenceCounted touch() {
            delegate.touch();
            return this;
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            delegate.touch(hint);
            return this;
        }

        @Override
        public boolean release() {
            return delegate.release();
        }

        @Override
        public boolean release(int decrement) {
            return delegate.release(decrement);
        }
    }
}
