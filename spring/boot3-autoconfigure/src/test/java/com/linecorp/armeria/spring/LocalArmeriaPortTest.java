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
package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.LocalArmeriaPortTest.TestConfiguration;

/**
 * Tests for {@link LocalArmeriaPort}.
 */
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class LocalArmeriaPortTest {

    @SpringBootApplication
    @Import(ArmeriaOkServiceConfiguration.class)
    static class TestConfiguration {}

    @Inject
    private Server server;
    @LocalArmeriaPort
    private Integer portField;
    private Integer portMethod;

    @LocalArmeriaPort
    public void setPortField(Integer port) {
        portMethod = port;
    }

    private String newUrl(String scheme) {
        return scheme + "://127.0.0.1:" + portField;
    }

    @Test
    void testPortConfigurationFromFieldInjection() throws Exception {
        final Integer actualPort = server.activeLocalPort();
        assertThat(actualPort).isEqualTo(portField);
    }

    @Test
    void testPortConfigurationFromMethodInjection() throws Exception {
        final Integer actualPort = server.activeLocalPort();
        assertThat(actualPort).isEqualTo(portMethod);
    }

    @Test
    void testHttpServiceRegistrationBean() throws Exception {
        final WebClient client = WebClient.of(newUrl("h1c"));
        final HttpResponse response = client.get("/ok");
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("ok");
    }
}
