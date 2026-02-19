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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.client.KubernetesClient;

final class IstioInstaller {

    static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(5);
    static final String DEFAULT_NAMESPACE = "istio-system";

    static final String ISTIO_VERSION_ENV = "ISTIO_VERSION";
    static final String ISTIO_PROFILE_ENV = "ISTIO_PROFILE";
    static final String ISTIOCTL_PATH_ENV = "ISTIOCTL_PATH";
    static final String ISTIO_NAMESPACE_ENV = "ISTIO_NAMESPACE";

    private static final Logger logger = LoggerFactory.getLogger(IstioInstaller.class);

    private IstioInstaller() {}

    static void installIfNeeded(Path kubeconfigPath) throws Exception {
        final String profile = requireEnv(ISTIO_PROFILE_ENV);
        installIfNeeded(kubeconfigPath, profile);
    }

    public static void installIfNeeded(Path kubeconfigPath, String profile) throws Exception {
        final String version = requireEnv(ISTIO_VERSION_ENV);
        final String namespace = istioNamespace();

        try (KubernetesClient client = K8sClusterHelper.createClient(kubeconfigPath)) {
            if (isIstioInstalled(client, namespace)) {
                logger.info("Istio is already installed in namespace '{}'.", namespace);
                if (!waitForIstiodReady(client, namespace, DEFAULT_READY_TIMEOUT,
                                        K8sClusterHelper.DEFAULT_POLL_INTERVAL)) {
                    throw new IllegalStateException("Timed out waiting for Istio to be Ready.");
                }
                return;
            }
        }

        final Path istioctl = requireIstioctlPath();
        logger.info("Installing Istio {} with profile '{}'.", version, profile);
        runIstioctlInstall(istioctl, kubeconfigPath, profile);

        try (KubernetesClient client = K8sClusterHelper.createClient(kubeconfigPath)) {
            if (!waitForIstiodReady(client, namespace, DEFAULT_READY_TIMEOUT,
                                    K8sClusterHelper.DEFAULT_POLL_INTERVAL)) {
                throw new IllegalStateException("Timed out waiting for Istio to be Ready.");
            }
        }
    }

    static boolean waitForIstiodReady(KubernetesClient client) {
        return waitForIstiodReady(client, istioNamespace(), DEFAULT_READY_TIMEOUT,
                                  K8sClusterHelper.DEFAULT_POLL_INTERVAL);
    }
    
    public static boolean waitForIstioRemoval(KubernetesClient client) {
        return waitForIstioRemoval(client, istioNamespace(), DEFAULT_READY_TIMEOUT,
                                   K8sClusterHelper.DEFAULT_POLL_INTERVAL);
    }
    
    static boolean waitForIstioRemoval(KubernetesClient client, String namespace, 
                                      Duration timeout, Duration pollInterval) {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            // Check if istiod deployment is gone
            if (!isIstioInstalled(client, namespace)) {
                // Also check if the namespace itself is gone (in case of namespace deletion)
                if (client.namespaces().withName(namespace).get() == null || 
                    // Or if namespace exists but has no pods
                    client.pods().inNamespace(namespace).list().getItems().isEmpty()) {
                    return true;
                }
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

    static String istioNamespace() {
        return envOrDefault(ISTIO_NAMESPACE_ENV, DEFAULT_NAMESPACE);
    }

    static Path requireIstioctlPath() {
        final String value = System.getenv(ISTIOCTL_PATH_ENV);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(ISTIOCTL_PATH_ENV + " must be set. " +
                                            "Run :it:istio:prepareIstioctl or set the path manually.");
        }
        final Path istioctl = Paths.get(value.trim());
        if (!Files.isExecutable(istioctl)) {
            throw new IllegalStateException("istioctl is not executable: " + istioctl);
        }
        return istioctl;
    }

    public static void runIstioctlUninstall(Path kubeconfigPath) throws Exception {
        final Path istioctl = requireIstioctlPath();
        final Path istioctlDir = requireParent(istioctl, "istioctl");
        runCommand(List.of(istioctl.toString(),
                           "uninstall",
                           "--purge",
                           "-y",
                           "--kubeconfig", kubeconfigPath.toAbsolutePath().toString()),
                   istioctlDir,
                   "istioctl");
    }

    private static void runIstioctlInstall(Path istioctl, Path kubeconfigPath, String profile) throws Exception {
        final Path istioctlDir = requireParent(istioctl, "istioctl");
        runCommand(List.of(istioctl.toString(),
                           "install",
                           "--set", "profile=" + profile,
                           "--skip-confirmation",
                           "--kubeconfig", kubeconfigPath.toAbsolutePath().toString()),
                   istioctlDir,
                   "istioctl");
    }

    private static void runCommand(List<String> command, Path workDir, String logPrefix) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);

        final Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[{}] {}", logPrefix, line);
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + exitCode + "): " + command);
        }
    }

    private static boolean isIstioInstalled(KubernetesClient client, String namespace) {
        return client.apps().deployments().inNamespace(namespace).withName("istiod").get() != null;
    }

    private static boolean waitForIstiodReady(KubernetesClient client, String namespace,
                                              Duration timeout, Duration pollInterval) {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (isDeploymentReady(client, namespace, "istiod")) {
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

    private static boolean isDeploymentReady(KubernetesClient client, String namespace, String name) {
        final Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (deployment == null || deployment.getStatus() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(hasAvailableCondition(deployment))) {
            return true;
        }
        final Integer availableReplicas = deployment.getStatus().getAvailableReplicas();
        return availableReplicas != null && availableReplicas > 0;
    }

    @Nullable
    private static Boolean hasAvailableCondition(Deployment deployment) {
        if (deployment.getStatus() == null || deployment.getStatus().getConditions() == null) {
            return null;
        }
        for (DeploymentCondition condition : deployment.getStatus().getConditions()) {
            if ("Available".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                return true;
            }
        }
        return null;
    }

    private static void sleep(Duration interval) {
        final long millis = interval.toMillis();
        try {
            Thread.sleep(millis > 0 ? millis : 1L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path requireParent(Path path, String description) {
        final Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalStateException("Missing parent directory for " + description + ": " + path);
        }
        return parent;
    }

    private static String envOrDefault(String name, String defaultValue) {
        final String value = System.getenv(name);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return defaultValue;
    }

    private static String requireEnv(String name) {
        final String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " must be set.");
        }
        return value.trim();
    }
}
