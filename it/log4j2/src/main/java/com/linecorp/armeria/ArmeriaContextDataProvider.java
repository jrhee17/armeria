/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria;

import java.util.Collections;
import java.util.Map;

import org.apache.logging.log4j.core.util.ContextDataProvider;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestScopedMdc;
import com.linecorp.armeria.internal.common.FlagsLoaded;

public class ArmeriaContextDataProvider implements ContextDataProvider {
    @Override
    public Map<String, String> supplyContextData() {
        if (!FlagsLoaded.get()) {
            return Collections.emptyMap();
        }
        RequestContext currentOrNull = RequestContext.currentOrNull();
        if (currentOrNull == null) {
            return Collections.emptyMap();
        }
        return RequestScopedMdc.getAll(currentOrNull);
    }
}
