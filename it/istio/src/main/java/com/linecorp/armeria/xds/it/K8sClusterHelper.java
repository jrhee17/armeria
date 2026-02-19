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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import com.dajudge.kindcontainer.KindContainer;
import com.linecorp.armeria.common.annotation.Nullable;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

final class K8sClusterHelper {

    private static final Logger logger = LoggerFactory.getLogger(K8sClusterHelper.class);

    static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(3);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);

    private K8sClusterHelper() {}

    private static final String KIND_CONTAINER_NAME = "armeria-istio-test-kind";
    private static final String KIND_CONTAINER_LABEL = "armeria-istio-test";

    static KindContainer<?> startKind() {
        final KindContainer<?> kind = new KindContainer<>()
                .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("kind"))
                .withKubectl(kubectl ->
                        kubectl.withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("kubectl")))
                .withCreateContainerCmdModifier(cmd -> {
                    cmd.withName(KIND_CONTAINER_NAME);
                    cmd.withLabels(java.util.Map.of("app", KIND_CONTAINER_LABEL));
                });
        kind.start();
        return kind;
    }

    static KindContainer<?> startKindAndWaitReady() {
        return startKindAndWaitReady(DEFAULT_READY_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    static KindContainer<?> startKindAndWaitReady(Duration timeout, Duration pollInterval) {
        final KindContainer<?> kind = startKind();

        try (KubernetesClient client = createClient(kind.getKubeconfig())) {
            if (!waitForReadyNode(client, timeout, pollInterval)) {
                throw new IllegalStateException("Timed out waiting for Kind cluster to be Ready.");
            }
        } catch (RuntimeException e) {
            stopKindAndDeleteKubeconfig(kind, null);
            throw e;
        }
        return kind;
    }

    static KubernetesClient createClient(Path kubeconfigPath) throws IOException {
        return createClient(Files.readString(kubeconfigPath));
    }

    static KubernetesClient createClient(String kubeconfig) {
        return new KubernetesClientBuilder()
                .withConfig(Config.fromKubeconfig(kubeconfig))
                .build();
    }

    static boolean waitForReadyNode(KubernetesClient client, Duration timeout, Duration pollInterval) {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (hasReadyNodeSafely(client)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            sleep(pollInterval);
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
        }
    }

    private static boolean hasReadyNodeSafely(KubernetesClient client) {
        try {
            return hasReadyNode(client);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasReadyNode(KubernetesClient client) {
        final List<Node> nodes = client.nodes().list().getItems();
        return nodes.stream().anyMatch(K8sClusterHelper::isReady);
    }

    private static boolean isReady(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return false;
        }
        return node.getStatus().getConditions().stream()
                   .anyMatch(K8sClusterHelper::isReadyCondition);
    }

    private static boolean isReadyCondition(NodeCondition condition) {
        return "Ready".equals(condition.getType()) && "True".equals(condition.getStatus());
    }

    private static void sleep(Duration interval) {
        final long millis = interval.toMillis();
        try {
            Thread.sleep(millis > 0 ? millis : 1L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void stopKindAndDeleteKubeconfig(KindContainer<?> kind, @Nullable Path kubeconfigPath) {
        final List<String> volumes = listContainerVolumes(kind);
        try {
            kind.stop();
        } catch (Exception e) {
            logger.warn("Failed to stop Kind container", e);
        }
        removeDockerVolumes(volumes);
        if (kubeconfigPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(kubeconfigPath);
        } catch (Exception e) {
            logger.warn("Failed to delete kubeconfig: {}", kubeconfigPath, e);
        }
    }

    private static List<String> listContainerVolumes(KindContainer<?> kind) {
        try {
            final String containerId = kind.getContainerId();
            if (containerId == null || containerId.isEmpty()) {
                return List.of();
            }
            return listContainerVolumes(containerId);
        } catch (Exception e) {
            logger.debug("Failed to resolve Kind container ID for volume cleanup", e);
            return List.of();
        }
    }

    private static List<String> listContainerVolumes(String containerId) {
        final ProcessBuilder builder = new ProcessBuilder(
                "docker",
                "inspect",
                "-f",
                "{{range .Mounts}}{{if eq .Type \"volume\"}}{{.Name}}{{\"\\n\"}}{{end}}{{end}}",
                containerId);
        builder.redirectErrorStream(true);

        final Set<String> volumes = new LinkedHashSet<>();
        try {
            final Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        volumes.add(trimmed);
                    }
                }
            }
            if (process.waitFor() != 0) {
                return List.of();
            }
        } catch (Exception e) {
            logger.debug("Failed to list Docker volumes for container: {}", containerId, e);
            return List.of();
        }
        return new ArrayList<>(volumes);
    }

    private static void removeDockerVolumes(List<String> volumes) {
        if (volumes.isEmpty()) {
            return;
        }
        for (String volume : volumes) {
            final ProcessBuilder builder = new ProcessBuilder("docker", "volume", "rm", volume);
            builder.redirectErrorStream(true);
            try {
                final Process process = builder.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                        // Drain output to avoid blocking.
                    }
                }
                if (process.waitFor() != 0) {
                    logger.debug("Failed to remove Docker volume: {}", volume);
                }
            } catch (Exception e) {
                logger.debug("Failed to remove Docker volume: {}", volume, e);
            }
        }
    }

    static boolean isContainerRunning(String containerId) {
        try {
            final ProcessBuilder builder = new ProcessBuilder(
                    "docker", "inspect", "-f", "{{.State.Running}}", containerId);
            builder.redirectErrorStream(true);
            
            final Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                final String output = reader.readLine();
                return process.waitFor() == 0 && "true".equals(output);
            }
        } catch (Exception e) {
            logger.debug("Failed to check container status", e);
            return false;
        }
    }
    
    @Nullable
    static String findExistingKindContainer() {
        try {
            // First try to find by name
            final ProcessBuilder nameBuilder = new ProcessBuilder(
                    "docker", "ps", "-q", "-f", "name=" + KIND_CONTAINER_NAME);
            nameBuilder.redirectErrorStream(true);
            
            final Process nameProcess = nameBuilder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nameProcess.getInputStream(), StandardCharsets.UTF_8))) {
                final String containerId = reader.readLine();
                if (nameProcess.waitFor() == 0 && containerId != null && !containerId.trim().isEmpty()) {
                    return containerId.trim();
                }
            }
            
            // If not found by name, try by label
            final ProcessBuilder labelBuilder = new ProcessBuilder(
                    "docker", "ps", "-q", "-f", "label=app=" + KIND_CONTAINER_LABEL);
            labelBuilder.redirectErrorStream(true);
            
            final Process labelProcess = labelBuilder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(labelProcess.getInputStream(), StandardCharsets.UTF_8))) {
                final String containerId = reader.readLine();
                if (labelProcess.waitFor() == 0 && containerId != null && !containerId.trim().isEmpty()) {
                    return containerId.trim();
                }
            }
            
            return null;
        } catch (Exception e) {
            logger.debug("Failed to find existing Kind container", e);
            return null;
        }
    }
}
