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

package com.linecorp.armeria.xds.api;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * An {@link UnsupportedFieldHandler} that records metrics for unsupported xDS field usage.
 * A counter is incremented for each unsupported field path detected, allowing users to
 * visualize which unsupported fields their xDS configuration relies on.
 *
 * <p>The counter name is {@code <prefix>.unsupported.fields} with a {@code field} tag
 * containing the dotted field path.
 */
@UnstableApi
public final class MeterUnsupportedFieldHandler implements UnsupportedFieldHandler {

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix meterIdPrefix;

    /**
     * Creates a new handler that records unsupported field metrics.
     *
     * @param meterRegistry the registry to record metrics in
     * @param meterIdPrefix the prefix for meter IDs (e.g., {@code "armeria.xds"})
     */
    public MeterUnsupportedFieldHandler(MeterRegistry meterRegistry, MeterIdPrefix meterIdPrefix) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
    }

    @Override
    public void handle(String fieldPath) {
        final Counter counter = Counter.builder(meterIdPrefix.name("unsupported.fields"))
                                       .tags(meterIdPrefix.tags())
                                       .tag("field", fieldPath)
                                       .register(meterRegistry);
        counter.increment();
    }
}
