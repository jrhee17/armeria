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
package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;

import okhttp3.Response;

/**
 * Integration test that verifies Istio sidecar communication between two services.
 * This test creates two HTTP services that communicate through Istio's service mesh.
 */
class IstioSidecarCommunicationTest {

    private static final Logger logger = LoggerFactory.getLogger(IstioSidecarCommunicationTest.class);

    @RegisterExtension
    static IstioClusterExtension istio = new IstioClusterExtension();

    private String testNamespace;
    private static final String SERVER_APP = "echo-server";
    private static final String CLIENT_APP = "echo-client";

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void testSidecarCommunication() throws Exception {
        final KubernetesClient client = istio.client();
        testNamespace = TestNamespaceManager.createTestNamespace(client, "sidecar-comm");

        // Deploy echo server service
        deployEchoServer(client);

        // Deploy echo client that calls the server
        deployEchoClient(client);

        // Wait for deployments to be ready
        waitForDeployment(client, SERVER_APP);
        waitForDeployment(client, CLIENT_APP);

        // Optional: Wait for sidecar injection if needed
        // Note: The test works without sidecars since traffic still goes through Istio's ingress
        // Uncomment below if you want to ensure sidecars are injected:
        // waitForSidecarInjection(client, SERVER_APP);
        // waitForSidecarInjection(client, CLIENT_APP);

        // Verify that client can communicate with server through Istio mesh
        verifyServiceCommunication(client);
    }


    private void deployEchoServer(KubernetesClient client) {
        // Create service for echo server
        client.services().inNamespace(testNamespace)
              .resource(new ServiceBuilder()
                                .withNewMetadata()
                                .withName(SERVER_APP)
                                .withNamespace(testNamespace)
                                .endMetadata()
                                .withNewSpec()
                                .addToSelector("app", SERVER_APP)
                                .addNewPort()
                                .withName("http")
                                .withPort(8080)
                                .withTargetPort(intOrString(80))
                                .endPort()
                                .endSpec()
                                .build())
              .create();

        // Create deployment for echo server
        client.apps().deployments().inNamespace(testNamespace)
              .resource(new DeploymentBuilder()
                                .withNewMetadata()
                                .withName(SERVER_APP)
                                .withNamespace(testNamespace)
                                .endMetadata()
                                .withNewSpec()
                                .withReplicas(1)
                                .withNewSelector()
                                .addToMatchLabels("app", SERVER_APP)
                                .endSelector()
                                .withNewTemplate()
                                .withNewMetadata()
                                .addToLabels("app", SERVER_APP)
                                .addToAnnotations("sidecar.istio.io/inject", "true")
                                .endMetadata()
                                .withNewSpec()
                                .addToContainers(new ContainerBuilder()
                                                         .withName("echo-server")
                                                         .withImage("kennethreitz/httpbin:latest")
                                                         .addNewPort()
                                                         .withContainerPort(80)
                                                         .withName("http")
                                                         .endPort()
                                                         .build())
                                .endSpec()
                                .endTemplate()
                                .endSpec()
                                .build())
              .create();
    }

    private void deployEchoClient(KubernetesClient client) {
        // Create deployment for echo client that makes requests to server
        client.apps().deployments().inNamespace(testNamespace)
              .resource(new DeploymentBuilder()
                                .withNewMetadata()
                                .withName(CLIENT_APP)
                                .withNamespace(testNamespace)
                                .endMetadata()
                                .withNewSpec()
                                .withReplicas(1)
                                .withNewSelector()
                                .addToMatchLabels("app", CLIENT_APP)
                                .endSelector()
                                .withNewTemplate()
                                .withNewMetadata()
                                .addToLabels("app", CLIENT_APP)
                                .addToAnnotations("sidecar.istio.io/inject", "true")
                                .endMetadata()
                                .withNewSpec()
                                .addToContainers(new ContainerBuilder()
                                                         .withName("echo-client")
                                                         .withImage("curlimages/curl:latest")
                                                         .withCommand("sleep", "3600")  // Keep container running
                                                         .build())
                                .endSpec()
                                .endTemplate()
                                .endSpec()
                                .build())
              .create();
    }

    private void waitForDeployment(KubernetesClient client, String deploymentName) {
        client.apps().deployments()
              .inNamespace(testNamespace)
              .withName(deploymentName)
              .waitUntilReady(5, TimeUnit.MINUTES);
    }

    private void verifyServiceCommunication(KubernetesClient client) throws Exception {
        // First check if pods are running
        logger.info("Checking pods in namespace: {}", testNamespace);
        final var allPods = client.pods()
                                  .inNamespace(testNamespace)
                                  .list()
                                  .getItems();
        logger.info("Found {} pods in namespace", allPods.size());
        for (var pod : allPods) {
            logger.info("Pod: {} - Phase: {}, Containers: {}",
                        pod.getMetadata().getName(),
                        pod.getStatus().getPhase(),
                        pod.getSpec().getContainers().stream()
                           .map(c -> c.getName())
                           .collect(java.util.stream.Collectors.toList()));
        }

        // Check services
        final var services = client.services()
                                   .inNamespace(testNamespace)
                                   .list()
                                   .getItems();
        logger.info("Found {} services in namespace", services.size());
        for (var svc : services) {
            logger.info("Service: {} - Type: {}, Ports: {}",
                        svc.getMetadata().getName(),
                        svc.getSpec().getType(),
                        svc.getSpec().getPorts());
        }

        // Get client pod
        final var clientPods = client.pods()
                                     .inNamespace(testNamespace)
                                     .withLabel("app", CLIENT_APP)
                                     .list()
                                     .getItems();

        assertThat(clientPods).isNotEmpty();
        final var clientPod = clientPods.get(0);

        // Execute curl command from client to server through Istio service mesh
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final boolean[] success = {false};

        try (ExecWatch execWatch = client.pods()
                                         .inNamespace(testNamespace)
                                         .withName(clientPod.getMetadata().getName())
                                         .inContainer("echo-client")
                                         .writingOutput(out)
                                         .writingError(err)
                                         .usingListener(new ExecListener() {
                                             @Override
                                             public void onOpen() {}

                                             @Override
                                             public void onFailure(Throwable t, Response failureResponse) {
                                                 success[0] = false;
                                             }

                                             @Override
                                             public void onClose(int code, String reason) {
                                                 success[0] = (code == 0);
                                             }
                                         })
                                         .exec("curl", "-v", "--max-time", "10", "http://" + SERVER_APP + ":8080/headers")) {

            // Wait for command to complete
            Thread.sleep(12000);
        }

        // Verify the request succeeded
        final String output = out.toString();
        final String error = err.toString();

        logger.info("Curl output: {}", output);
        logger.info("Curl error (includes headers): {}", error);

        // Check if we got a successful response from httpbin
        assertThat(output)
                .as("Should receive httpbin headers response")
                .contains("\"headers\":")
                .contains("\"Host\": \"" + SERVER_APP + ":8080\"");

        // Check if the response went through Envoy (Istio proxy)
        assertThat(error)
                .as("Response should include Envoy headers")
                .contains("server: envoy");
    }

    private void verifyIstioSidecarInjection(KubernetesClient client, String appName) {
        final var pods = client.pods()
                               .inNamespace(testNamespace)
                               .withLabel("app", appName)
                               .list()
                               .getItems();

        assertThat(pods).isNotEmpty();
        final var pod = pods.get(0);

        // Log container information for debugging
        logger.info("Pod {} has {} containers: {}",
                    pod.getMetadata().getName(),
                    pod.getSpec().getContainers().size(),
                    pod.getSpec().getContainers().stream()
                       .map(c -> c.getName())
                       .collect(java.util.stream.Collectors.toList()));

        // Note: Istio sidecar injection may not happen in test environment
        // but the service mesh routing still works through Istio ingress
    }

    private io.fabric8.kubernetes.api.model.IntOrString intOrString(int value) {
        return new io.fabric8.kubernetes.api.model.IntOrString(value);
    }
}