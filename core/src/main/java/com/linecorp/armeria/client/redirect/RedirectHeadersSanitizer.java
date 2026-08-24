/*
 * Copyright 2026 LINE Corporation
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
package com.linecorp.armeria.client.redirect;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A sanitizer that modifies request headers when following a cross-origin redirect.
 * This is invoked when the redirect target differs from the original request in scheme, host, or port.
 *
 * <p>The default implementation strips {@link HttpHeaderNames#AUTHORIZATION},
 * {@link HttpHeaderNames#COOKIE}, and {@link HttpHeaderNames#PROXY_AUTHORIZATION} headers
 * to prevent credential leakage to untrusted third parties.
 *
 * @see RedirectConfigBuilder#headersSanitizer(RedirectHeadersSanitizer)
 */
@UnstableApi
@FunctionalInterface
public interface RedirectHeadersSanitizer {

    /**
     * Returns the default {@link RedirectHeadersSanitizer} that strips sensitive credential headers.
     */
    static RedirectHeadersSanitizer ofDefault() {
        return DefaultRedirectHeadersSanitizer.INSTANCE;
    }

    /**
     * Returns a {@link RedirectHeadersSanitizer} that does not strip any headers.
     * Use this only when you trust all possible redirect targets.
     */
    static RedirectHeadersSanitizer ofNoOp() {
        return NoOpRedirectHeadersSanitizer.INSTANCE;
    }

    /**
     * Sanitizes the request headers before sending them to a cross-origin redirect target.
     *
     * @param ctx the {@link ClientRequestContext} of the original request
     * @param headersBuilder the builder for the headers that will be sent to the redirect target
     */
    void sanitize(ClientRequestContext ctx, HttpHeadersBuilder headersBuilder);
}
