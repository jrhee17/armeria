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
package com.linecorp.armeria.dropwizard;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.dropwizard.testing.junit5.DropwizardAppExtension;

class DropwizardArmeriaApplicationTest {
    static final DropwizardAppExtension<TestConfiguration> appExtension =
            new DropwizardAppExtension<>(TestApplication.class,
                                         resourceFilePath("dropwizard-armeria-app.yaml"));

    @BeforeAll
    static void beforeAll() throws Exception {
        appExtension.before();
    }

    @Test
    void helloWorld() {
        final String content = appExtension
                .client().target("http://127.0.0.1:" + appExtension.getLocalPort() + "/armeria")
                .request().get(String.class);
        assertThat(content).isEqualTo("Hello, Armeria!");
    }
}
