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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dajudge.kindcontainer.KindContainer;

/**
 * Main class for starting a Kind cluster with Istio.
 * If a cluster is already running, it returns early.
 */
public final class IstioMain {

    private static final Logger logger = LoggerFactory.getLogger(IstioMain.class);

    public static void main(String[] args) throws Exception {
        final Path kubeconfigPath = KubeconfigUtil.kubeconfigPath();
        final Path metadataPath = KubeconfigUtil.containerMetadataPath();
        Files.createDirectories(kubeconfigPath.getParent());

        // Check if cluster is already running
        if (Files.exists(metadataPath) && Files.exists(kubeconfigPath)) {
            final String containerId = Files.readString(metadataPath, StandardCharsets.UTF_8).trim();
            if (!containerId.isEmpty() && K8sClusterHelper.isContainerRunning(containerId)) {
                logger.info("Kind cluster is already running. Kubeconfig: {}", kubeconfigPath);
                logger.info("Container ID: {}", containerId);
                return;
            } else {
                logger.warn("Found stale metadata. Container is not running. Cleaning up...");
                Files.deleteIfExists(metadataPath);
                Files.deleteIfExists(kubeconfigPath);
            }
        }

        // Start new cluster
        logger.info("Starting new Kind cluster...");
        final KindContainer<?> kind = K8sClusterHelper.startKindAndWaitReady();
        final String kubeconfig = kind.getKubeconfig();
        Files.writeString(kubeconfigPath, kubeconfig, StandardCharsets.UTF_8);
        Files.writeString(metadataPath, kind.getContainerId(), StandardCharsets.UTF_8);

        // Install Istio
        IstioInstaller.installIfNeeded(kubeconfigPath);

        logger.info("Kind cluster with Istio is ready.");
        logger.info("Kubeconfig: {}", kubeconfigPath);
        logger.info("Container ID: {}", kind.getContainerId());
        logger.info("Run './gradlew :it:istio:stopIstio' to stop the cluster.");
    }
}