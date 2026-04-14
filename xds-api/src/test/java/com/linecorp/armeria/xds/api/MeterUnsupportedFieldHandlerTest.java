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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MeterUnsupportedFieldHandlerTest {

    @Test
    void recordsMetricsPerFieldPath() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MeterIdPrefix prefix = new MeterIdPrefix("armeria.xds");
        final MeterUnsupportedFieldHandler handler =
                new MeterUnsupportedFieldHandler(registry, prefix);

        handler.handle("cluster.outlier_detection");
        handler.handle("cluster.health_checks");

        final Counter outlierCounter = registry.find("armeria.xds.unsupported.fields")
                                               .tag("field", "cluster.outlier_detection")
                                               .counter();
        assertThat(outlierCounter).isNotNull();
        assertThat(outlierCounter.count()).isEqualTo(1.0);

        final Counter healthCounter = registry.find("armeria.xds.unsupported.fields")
                                              .tag("field", "cluster.health_checks")
                                              .counter();
        assertThat(healthCounter).isNotNull();
        assertThat(healthCounter.count()).isEqualTo(1.0);
    }

    @Test
    void incrementsOnRepeatedCalls() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MeterIdPrefix prefix = new MeterIdPrefix("armeria.xds");
        final MeterUnsupportedFieldHandler handler =
                new MeterUnsupportedFieldHandler(registry, prefix);

        handler.handle("cluster.outlier_detection");
        handler.handle("cluster.outlier_detection");

        final Counter counter = registry.find("armeria.xds.unsupported.fields")
                                        .tag("field", "cluster.outlier_detection")
                                        .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }
}
