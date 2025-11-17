/*
 * Copyright 2025 LY Corporation
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

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import com.linecorp.armeria.common.TlsPeerVerifier;
import com.linecorp.armeria.common.TlsPeerVerifier.TlsPeerVerifierFactory;

final class VerifierBasedTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;
    private final List<TlsPeerVerifierFactory> verifierFactories;

    VerifierBasedTrustManager(X509ExtendedTrustManager delegate,
                              List<TlsPeerVerifierFactory> verifierFactories) {
        this.delegate = delegate;
        this.verifierFactories = verifierFactories;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        TlsPeerVerifier verifier = (unused0, unused1, unused2) ->
                delegate.checkServerTrusted(chain, authType, engine);
        for (TlsPeerVerifierFactory verifierFactory : verifierFactories) {
            verifier = verifierFactory.create(verifier);
        }
        verifier.verify(chain, engine.getPeerHost(), engine.getHandshakeSession());
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        TlsPeerVerifier verifier = (unused0, unused1, unused2) ->
                delegate.checkClientTrusted(chain, authType, engine);
        for (TlsPeerVerifierFactory verifierFactory : verifierFactories) {
            verifier = verifierFactory.create(verifier);
        }
        verifier.verify(chain, engine.getPeerHost(), engine.getHandshakeSession());
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
