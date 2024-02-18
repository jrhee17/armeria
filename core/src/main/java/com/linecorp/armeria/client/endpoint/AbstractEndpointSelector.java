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
package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.IdentityHashStrategy;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenCustomHashSet;

/**
 * A skeletal {@link EndpointSelector} implementation. This abstract class implements the
 * {@link #select(ClientRequestContext, ScheduledExecutorService)} method by listening to
 * the change events emitted by {@link EndpointGroup} specified at construction time.
 */
public abstract class AbstractEndpointSelector extends AsyncEndpointSelector {

    private final EndpointGroup endpointGroup;

    /**
     * Creates a new instance that selects an {@link Endpoint} from the specified {@link EndpointGroup}.
     */
    protected AbstractEndpointSelector(EndpointGroup endpointGroup) {
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
    }

    /**
     * Returns the {@link EndpointGroup} being selected by this {@link EndpointSelector}.
     */
    protected final EndpointGroup group() {
        return endpointGroup;
    }

    @Override
    public final CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                    ScheduledExecutorService executor) {
        return select(ctx, executor, endpointGroup.selectionTimeoutMillis());
    }

    /**
     * Initialize this {@link EndpointSelector} to listen to the new endpoints emitted by the
     * {@link EndpointGroup}. The new endpoints will be passed to {@link #updateNewEndpoints(List)}.
     */
    @UnstableApi
    protected final void initialize() {
        endpointGroup.addListener(this::refreshEndpoints, true);
    }

    private void refreshEndpoints(List<Endpoint> endpoints) {
        // Allow subclasses to update the endpoints first.
        updateNewEndpoints(endpoints);

        notifyPendingFutures();
    }

    /**
     * Invoked when the {@link EndpointGroup} has been updated.
     */
    @UnstableApi
    protected void updateNewEndpoints(List<Endpoint> endpoints) {}

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpointGroup", endpointGroup)
                          .toString();
    }
}
