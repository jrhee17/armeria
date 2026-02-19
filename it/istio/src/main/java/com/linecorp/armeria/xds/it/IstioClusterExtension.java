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

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dajudge.kindcontainer.KindContainer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * A JUnit extension that manages Kubernetes cluster lifecycle with Istio for testing.
 * 
 * <p>Default behavior:
 * <ul>
 *   <li>Reuses an existing Kind container if one is running and kubeconfig is available</li>
 *   <li>Otherwise, creates a new Kind container</li>
 *   <li>Istio is always installed on the cluster</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * class MyIstioTest {
 *     @RegisterExtension
 *     static IstioClusterExtension istio = new IstioClusterExtension();
 *     
 *     @Test
 *     void test() {
 *         KubernetesClient client = istio.client();
 *         // Test logic
 *     }
 * }
 * }</pre>
 * 
 * <p>For Istio reinstallation on each test:
 * <pre>{@code
 * @RegisterExtension
 * static IstioClusterExtension istio = IstioClusterExtension.builder()
 *     .runForEachTest(true)
 *     .build();
 * }</pre>
 */
public final class IstioClusterExtension extends AbstractAllOrEachExtension {

    private static final Logger logger = LoggerFactory.getLogger(IstioClusterExtension.class);
    
    @Nullable
    private KubernetesClient client;
    @Nullable
    private KindContainer<?> kindContainer;
    @Nullable
    private Path kubeconfigPath;
    private boolean deleteKubeconfig;
    
    private final boolean runForEachTest;
    private final String istioProfile;

    /**
     * Creates a new instance with default settings.
     */
    public IstioClusterExtension() {
        this(builder());
    }

    private IstioClusterExtension(Builder builder) {
        runForEachTest = builder.runForEachTest;
        istioProfile = builder.istioProfile;
    }

    @Override
    protected boolean runForEachTest() {
        return runForEachTest;
    }

    /**
     * Returns a new builder for configuring the extension.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void before(ExtensionContext context) throws Exception {
        kubeconfigPath = KubeconfigUtil.kubeconfigPath();

        // Check if we have an existing Kind container running
        final String existingContainerId = K8sClusterHelper.findExistingKindContainer();
        if (existingContainerId != null && Files.exists(kubeconfigPath)) {
            logger.info("Found existing Kind container: {}", existingContainerId);
            try {
                client = K8sClusterHelper.createClient(kubeconfigPath);
                // Verify the client can connect
                client.namespaces().list();
                logger.info("Successfully connected to existing Kind cluster");
            } catch (Exception e) {
                logger.warn("Failed to connect to existing cluster, creating a new one: {}", e.getMessage());
                setupKindCluster();
            }
        } else {
            // No existing cluster found, create a new one
            if (existingContainerId != null) {
                logger.info("Found container but no kubeconfig, creating new cluster");
            } else {
                logger.info("No existing Kind container found, creating new cluster");
            }
            setupKindCluster();
        }
        reinstallIstio();
    }

    @Override
    public void after(ExtensionContext context) throws Exception {
        // Always clean up test namespaces after each test for isolation
        if (client != null) {
            try {
                TestNamespaceManager.cleanupAllTestNamespaces(client);
            } catch (Exception e) {
                logger.warn("Failed to clean up test namespaces: {}", e.getMessage());
            }
        }
        if (client != null) {
            client.close();
            client = null;
        }
        if (kindContainer != null) {
            K8sClusterHelper.stopKindAndDeleteKubeconfig(
                    kindContainer, deleteKubeconfig ? kubeconfigPath : null);
            kindContainer = null;
        } else if (deleteKubeconfig && kubeconfigPath != null) {
            Files.deleteIfExists(kubeconfigPath);
        }
    }

    /**
     * Returns the Kubernetes client for interacting with the cluster.
     */
    public KubernetesClient client() {
        if (client == null) {
            throw new IllegalStateException("Kubernetes client not initialized. " +
                                            "Ensure the extension is properly registered.");
        }
        return client;
    }

    private void setupKindCluster() throws Exception {
        requireNonNull(kubeconfigPath, "kubeconfigPath");
        final Path parent = requireNonNull(kubeconfigPath.getParent(), "kubeconfigPath parent");
        
        // Start new Kind container
        logger.info("Starting new Kind cluster...");
        kindContainer = K8sClusterHelper.startKindAndWaitReady();
        Files.createDirectories(parent);
        Files.writeString(kubeconfigPath, kindContainer.getKubeconfig(), StandardCharsets.UTF_8);
        deleteKubeconfig = true;

        client = K8sClusterHelper.createClient(kubeconfigPath);
        logger.info("Kind cluster started with container ID: {}", kindContainer.getContainerId());
    }

    private void reinstallIstio() throws Exception {
        logger.info("Uninstalling existing Istio installation...");
        IstioInstaller.runIstioctlUninstall(requireNonNull(kubeconfigPath));
        
        logger.info("Waiting for Istio resources to be removed...");
        if (!IstioInstaller.waitForIstioRemoval(requireNonNull(client, "client"))) {
            throw new IllegalStateException("Timed out waiting for Istio to be removed");
        }
        
        logger.info("Installing fresh Istio instance with profile '{}'...", istioProfile);
        IstioInstaller.installIfNeeded(kubeconfigPath, istioProfile);
        
        if (!IstioInstaller.waitForIstiodReady(requireNonNull(client, "client"))) {
            throw new IllegalStateException("Istio failed to become ready after reinstallation");
        }
    }

    /**
     * Builder for configuring {@link IstioClusterExtension}.
     */
    public static final class Builder {
        private boolean runForEachTest;
        private String istioProfile = requireNonNull(
                System.getenv(IstioInstaller.ISTIO_PROFILE_ENV), 
                IstioInstaller.ISTIO_PROFILE_ENV + " must be set");

        private Builder() {}

        /**
         * Sets whether to run the extension for each test method.
         * When true, Istio will be reinstalled before each test.
         * Default is false.
         */
        public Builder runForEachTest(boolean runForEachTest) {
            this.runForEachTest = runForEachTest;
            return this;
        }

        /**
         * Sets the Istio profile to use for installation.
         * Defaults to the value of ISTIO_PROFILE environment variable.
         */
        public Builder istioProfile(String istioProfile) {
            this.istioProfile = requireNonNull(istioProfile, "istioProfile");
            return this;
        }

        /**
         * Builds the configured {@link IstioClusterExtension}.
         */
        public IstioClusterExtension build() {
            return new IstioClusterExtension(this);
        }
    }
}
