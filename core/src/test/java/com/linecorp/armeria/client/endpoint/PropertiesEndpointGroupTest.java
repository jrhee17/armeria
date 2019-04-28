/*
 * Copyright 2018 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.armeria.client.Endpoint;

public class PropertiesEndpointGroupTest {

    private static final Properties PROPS = new Properties();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    static {
        PROPS.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        PROPS.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        PROPS.setProperty("serverA.hosts.2", "127.0.0.1");
        PROPS.setProperty("serverB.hosts.0", "127.0.0.1:8082");
        PROPS.setProperty("serverB.hosts.1", "127.0.0.1:8083");
    }

    @Test
    public void propertiesWithoutDefaultPort() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(PROPS, "serverA.hosts");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    public void propertiesWithDefaultPort() {
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(PROPS, "serverA.hosts", 80);
        final PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.of(PROPS, "serverB.hosts", 8080);

        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        assertThat(endpointGroupB.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8082"),
                                                                         Endpoint.parse("127.0.0.1:8083"));
    }

    @Test
    public void resourceWithoutDefaultPort() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    public void resourceWithDefaultPort() {
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts", 80);
        final PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverB.hosts", 8080);

        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        assertThat(endpointGroupB.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8082"),
                                                                         Endpoint.parse("127.0.0.1:8083"));
    }

    @Test
    public void testWithPrefixThatEndsWithDot() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts.");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    public void containsNoHosts() {
        assertThatThrownBy(() -> PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverC.hosts", 8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains no hosts");
    }

    @Test
    public void illegalDefaultPort() {
        assertThatThrownBy(() -> PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPort");
    }

    @Test
    public void propertiesFileAddsCorrectly() throws IOException {
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts", 80);
        assert PropertiesEndpointGroup.endpointGroupMap.size() == 1;
        endpointGroupA.close();
        assert PropertiesEndpointGroup.endpointGroupMap.isEmpty();
    }

    @Test
    public void propertiesFileUpdatesCorrectly() throws Exception {

        final File file = folder.newFile("temp-file.properties");
        final URL url = file.getParentFile().toURI().toURL();
        final URLClassLoader classLoader = URLClassLoader.newInstance(new URL[]{url});

        OutputStream outputStream = new FileOutputStream(file);
        Properties props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.store(outputStream, "");
        outputStream.flush();
        outputStream.close();

        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                classLoader, file.getName(), "serverA.hosts", 80);

        await().atMost(60, TimeUnit.SECONDS).until(() -> endpointGroupA.endpoints().size() == 1);

        // Update resource
        outputStream = new FileOutputStream(file);
        props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        props.store(outputStream, "");
        outputStream.flush();
        outputStream.close();

        await().atMost(60, TimeUnit.SECONDS).until(() -> endpointGroupA.endpoints().size() == 2);

        endpointGroupA.close();
    }
}
