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

package com.linecorp.armeria.internal.common;

import java.util.Set;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.TlsPeerVerifier;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;

public final class IgnoreHostsPeerVerifierFactory implements TlsPeerVerifierFactory {

    private final Set<String> hosts;

    public IgnoreHostsPeerVerifierFactory(Set<String> hosts) {
        this.hosts = hosts;
    }

    @Override
    public TlsPeerVerifier create(TlsPeerVerifier delegate) {
        return (chain, authType, engine) -> {
            final String peerHost = engine.getPeerHost();
            if (hosts.contains(peerHost)) {
                return;
            }
            delegate.verify(chain, authType, engine);
        };
    }

    @Override
    public String signature() {
        return MoreObjects.toStringHelper(this)
                          .add("hosts", hosts)
                          .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final IgnoreHostsPeerVerifierFactory that = (IgnoreHostsPeerVerifierFactory) o;
        return Objects.equal(hosts, that.hosts);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(hosts);
    }

    @Override
    public String toString() {
        return signature();
    }
}
