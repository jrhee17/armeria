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
 *   <li>If LOCAL_KUBE environment variable is set, uses the existing external cluster</li>
 *   <li>Otherwise, creates or reuses a Kind container</li>
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

        if (KubeconfigUtil.useLocalKube()) {
            setupExternalCluster();
        } else {
            setupKindCluster();
        }

        if (runForEachTest() && context.getTestMethod().isPresent()) {
            logger.info("Reinstalling Istio for test: {}", context.getDisplayName());
            reinstallIstio();
        } else {
            IstioInstaller.installIfNeeded(kubeconfigPath, istioProfile);
        }
    }

    @Override
    public void after(ExtensionContext context) throws Exception {
        // Only clean up if we're running for all tests (not each test)
        if (!runForEachTest() || context.getTestMethod().isEmpty()) {
            if (client != null) {
                client.close();
                client = null;
            }
            if (kindContainer != null) {
                kindContainer.stop();
                kindContainer = null;
            }
            if (deleteKubeconfig && kubeconfigPath != null) {
                Files.deleteIfExists(kubeconfigPath);
            }
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

    private void setupExternalCluster() throws Exception {
        requireNonNull(kubeconfigPath, "kubeconfigPath");
        if (!Files.exists(kubeconfigPath)) {
            throw new IllegalStateException("Kubeconfig not found at " + kubeconfigPath +
                                            " while " + KubeconfigUtil.LOCAL_KUBE_ENV + " is set.");
        }
        client = K8sClusterHelper.createClient(kubeconfigPath);
        logger.info("Using existing kubeconfig: {}", kubeconfigPath);
    }

    private void setupKindCluster() throws Exception {
        logger.info("{} is not set. Using Kind cluster.", KubeconfigUtil.LOCAL_KUBE_ENV);
        
        requireNonNull(kubeconfigPath, "kubeconfigPath");
        final Path parent = requireNonNull(kubeconfigPath.getParent(), "kubeconfigPath parent");
        
        // Check if we already have a running Kind container from metadata
        final Path metadataPath = parent.resolve("istio-kind.metadata");
        if (Files.exists(metadataPath)) {
            final String containerId = Files.readString(metadataPath, StandardCharsets.UTF_8).trim();
            if (K8sClusterHelper.isContainerRunning(containerId)) {
                logger.info("Reusing existing Kind container: {}", containerId);
                client = K8sClusterHelper.createClient(kubeconfigPath);
                return;
            }
        }

        // Start new Kind container
        kindContainer = K8sClusterHelper.startKindAndWaitReady();
        Files.createDirectories(parent);
        Files.writeString(kubeconfigPath, kindContainer.getKubeconfig(), StandardCharsets.UTF_8);
        
        // Write metadata for reuse
        Files.writeString(metadataPath, kindContainer.getContainerId(), StandardCharsets.UTF_8);
        deleteKubeconfig = true;

        client = K8sClusterHelper.createClient(kubeconfigPath);
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