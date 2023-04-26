/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import reactor.blockhound.BlockHound.Builder;
import reactor.blockhound.integration.BlockHoundIntegration;

public final class ArmeriaBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(Builder builder) {

        builder.allowBlockingCallsInside("com.linecorp.armeria.client.HttpClientFactory",
                                         "pool");

        // a single blocking call is incurred for the first invocation, but the result is cached.
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.client.PublicSuffix",
                                         "get");
        builder.allowBlockingCallsInside("java.util.ServiceLoader$LazyClassPathLookupIterator",
                                         "parse");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.common.util.ReentrantShortLock",
                                         "lock");

        // custom implementations for test class usage.
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "sleep");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "join");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "acquireUninterruptibly");

        // sometimes we make assertions in tests which should never reach production code and is thus safe.
        builder.allowBlockingCallsInside("org.assertj.core.api.Assertions", "assertThat");
        builder.allowBlockingCallsInside("net.javacrumbs.jsonunit.fluent.JsonFluentAssert",
                                         "assertThatJson");
        builder.allowBlockingCallsInside("com.linecorp.armeria.testing.server.ServiceRequestContextCaptor$2",
                                         "serve");
    }
}
