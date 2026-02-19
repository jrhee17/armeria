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

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Utility class for managing test namespaces with proper cleanup.
 * Ensures test isolation by cleaning up all test resources after each test.
 */
final class TestNamespaceManager {

    private static final Logger logger = LoggerFactory.getLogger(TestNamespaceManager.class);
    private static final String TEST_NAMESPACE_PREFIX = "armeria-test-";
    private static final long NAMESPACE_DELETE_TIMEOUT_SECONDS = 60;

    private TestNamespaceManager() {}

    /**
     * Creates a unique test namespace with Istio injection enabled.
     * The namespace name is prefixed to identify it as a test namespace.
     */
    static String createTestNamespace(KubernetesClient client, String testName) {
        final String namespace = TEST_NAMESPACE_PREFIX + testName.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // Delete existing namespace if it exists
        cleanupNamespace(client, namespace);

        logger.info("Creating test namespace: {}", namespace);
        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                            .withName(namespace)
                            .addToLabels("istio-injection", "enabled")
                            .addToLabels("test-namespace", "true")
                            .addToLabels("created-by", "armeria-test")
                        .endMetadata()
                        .build())
                .create();

        // Wait for namespace to be ready
        client.namespaces().withName(namespace).waitUntilReady(30, TimeUnit.SECONDS);
        logger.info("Test namespace created and ready: {}", namespace);
        
        return namespace;
    }

    /**
     * Cleans up a specific test namespace and waits for deletion to complete.
     */
    static void cleanupNamespace(KubernetesClient client, String namespace) {
        if (client.namespaces().withName(namespace).get() == null) {
            return; // Namespace doesn't exist
        }

        logger.info("Cleaning up test namespace: {}", namespace);
        client.namespaces().withName(namespace).delete();
        
        // Wait for namespace to be fully deleted
        try {
            client.namespaces().withName(namespace).waitUntilCondition(
                    ns -> ns == null, NAMESPACE_DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.info("Test namespace deleted: {}", namespace);
        } catch (Exception e) {
            logger.warn("Timeout waiting for namespace {} to be deleted: {}", namespace, e.getMessage());
        }
    }

    /**
     * Cleans up ALL test namespaces created by this test suite.
     * This is useful for global cleanup between test runs.
     */
    static void cleanupAllTestNamespaces(KubernetesClient client) {
        logger.info("Cleaning up all test namespaces with label test-namespace=true");
        
        final var testNamespaces = client.namespaces()
                .withLabel("test-namespace", "true")
                .list()
                .getItems();

        if (testNamespaces.isEmpty()) {
            logger.info("No test namespaces found to clean up");
            return;
        }

        logger.info("Found {} test namespaces to clean up", testNamespaces.size());
        for (var namespace : testNamespaces) {
            final String name = namespace.getMetadata().getName();
            cleanupNamespace(client, name);
        }
    }

    /**
     * Emergency cleanup that force-deletes stuck namespaces.
     * Should only be used when normal cleanup fails.
     */
    static void forceCleanupAllTestNamespaces(KubernetesClient client) {
        logger.warn("Performing FORCE cleanup of all test namespaces");
        
        final var testNamespaces = client.namespaces()
                .withLabel("test-namespace", "true")
                .list()
                .getItems();

        for (var namespace : testNamespaces) {
            final String name = namespace.getMetadata().getName();
            try {
                logger.warn("Force deleting namespace: {}", name);
                client.namespaces().withName(name).withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
            } catch (Exception e) {
                logger.error("Failed to force delete namespace {}: {}", name, e.getMessage());
            }
        }
    }
}