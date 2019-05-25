/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.it.client.endpoint;

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.ROUND_ROBIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.PropertiesEndpointGroup;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.server.ServerRule;

public class PropertiesEndpointGroupIntegrationTest {
    @Rule
    public final TestName name = new TestName();
    @Rule
    public final ServerRule serverOne = new IpServerRule();
    @Rule
    public final ServerRule serverTwo = new IpServerRule();
    @Rule
    public final ServerRule serverThree = new IpServerRule();
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testPropertiesEndpointGroupListenerUpdate() throws Exception {

        serverOne.start();
        serverTwo.start();
        serverThree.start();

        final File file = folder.newFile("temp-file.properties");
        final URL url = file.getParentFile().toURI().toURL();
        final URLClassLoader classLoader = URLClassLoader.newInstance(new URL[]{ url});

        final Properties props1 = new Properties();
        props1.setProperty("serverA.hosts.0", "127.0.0.1:" + serverOne.httpPort());
        props1.setProperty("serverA.hosts.1", "127.0.0.1:" + serverTwo.httpPort());
        writeProps(file, props1);

        final PropertiesEndpointGroup propertiesEndpointGroup = PropertiesEndpointGroup.of(
                classLoader, file.getName(), "serverA.hosts", 80, true);
        await().atMost(20, TimeUnit.SECONDS).until(() -> propertiesEndpointGroup.endpoints().size() == 2);

        final EndpointGroup staticEndpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", serverThree.httpPort()));

        final EndpointGroup orElseEndpointGroup = propertiesEndpointGroup.orElse(staticEndpointGroup);

        final String groupName = name.getMethodName();
        final String endpointGroupMark = "group:";
        EndpointGroupRegistry.register(groupName, orElseEndpointGroup, ROUND_ROBIN);

        final HelloService.Iface ipService = Clients.newClient(
                "ttext+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());

        writeProps(file, new Properties());
        await().atMost(20, TimeUnit.SECONDS).until(() -> propertiesEndpointGroup.endpoints().isEmpty());

        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());

        final Properties props2 = new Properties();
        props2.setProperty("serverA.hosts.0", "127.0.0.1:" + serverOne.httpPort());
        writeProps(file, props2);
        await().atMost(20, TimeUnit.SECONDS).until(() -> propertiesEndpointGroup.endpoints().size() == 1);

        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
    }

    @Test
    public void testNoHosts() throws Exception {

        serverOne.start();

        final File file = folder.newFile("temp-file.properties");
        final URL url = file.getParentFile().toURI().toURL();
        final URLClassLoader classLoader = URLClassLoader.newInstance(new URL[]{ url});

        writeProps(file, new Properties());

        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                classLoader, file.getName(), "serverA.hosts", 80, true);
        await().atMost(20, TimeUnit.SECONDS).until(() -> endpointGroup.endpoints().isEmpty());

        final String groupName = name.getMethodName();
        final String endpointGroupMark = "group:";
        EndpointGroupRegistry.register(groupName, endpointGroup, ROUND_ROBIN);

        final HelloService.Iface ipService = Clients.newClient(
                "ttext+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);

        assertThatThrownBy(() -> ipService.hello("ip"));
    }

    private static void writeProps(File file, Properties properties) throws IOException {
        final PrintWriter printWriter = new PrintWriter(file);
        properties.store(printWriter, "");
        printWriter.close();
    }

    private static class IpServerRule extends ServerRule {
        private final HelloService.Iface handler = dump -> "host:127.0.0.1:" + httpPort();

        protected IpServerRule() {
            super(false); // Disable auto-start.
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/serverIp", THttpService.of(handler));
        }
    }
}
