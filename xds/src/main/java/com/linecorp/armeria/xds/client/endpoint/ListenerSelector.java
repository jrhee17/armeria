/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Listenable;
import com.linecorp.armeria.internal.client.AbstractSelector;

final class ListenerSelector<T> extends AbstractSelector<T> implements Consumer<T> {

    @Nullable
    private volatile T current;

    ListenerSelector(Listenable<T> listenable) {
        listenable.addListener(this);
    }

    @Override
    public void accept(T current) {
        this.current = current;
        refresh();
    }

    @Override
    @Nullable
    protected T selectNow(ClientRequestContext ctx) {
        return current;
    }
}
