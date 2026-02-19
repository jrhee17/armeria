package com.linecorp.armeria.xds.it;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * JUnit 5 extension that intercepts {@link IstioPodTest}-annotated methods and runs them
 * inside a Kubernetes Job in the K3s cluster instead of locally.
 *
 * <p>Requires {@link IstioClusterExtension} to be registered on the same test class so
 * the {@link KubernetesClient} and K3s container are available in the {@link ExtensionContext.Store}.
 */
public final class IstioTestExtension implements InvocationInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(IstioTestExtension.class);

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        // When running inside the K8s Job itself, execute the test body normally.
        if (Boolean.parseBoolean(System.getenv(HostOnlyExtension.RUNNING_IN_K8S_POD_ENV))) {
            invocation.proceed();
            return;
        }

        invocation.skip();

        final ExtensionContext classContext = extensionContext.getParent()
                .orElseThrow(() -> new IllegalStateException("No parent context found"));
        final ExtensionContext.Store store = classContext.getStore(IstioClusterExtension.NAMESPACE);

        final KubernetesClient client = store.get(IstioClusterExtension.K8S_CLIENT_KEY,
                                                   KubernetesClient.class);
        if (client == null) {
            throw new IllegalStateException(
                    "KubernetesClient not found in store. " +
                    "Ensure IstioClusterExtension is registered on this test class.");
        }

        final String testClass = invocationContext.getTargetClass().getName();
        final String testMethod = invocationContext.getExecutable().getName();
        final String namespace = "default";
        final String podName = createTestPod(client, namespace, testClass, testMethod);

        logger.info("Created K8s Pod '{}' for {}.{}", podName, testClass, testMethod);

        String logs = "";
        try {
            waitForPodHealthy(client, podName, namespace);
            final int exitCode = waitForTestContainerTerminated(client, podName, namespace);
            logs = collectPodLogs(client, podName, namespace);
            if (exitCode == 0) {
                logger.info("Pod '{}' succeeded for {}.{}\nPod logs:\n{}", podName, testClass, testMethod, logs);
            } else {
                logger.error("Pod '{}' failed (exit {}) for {}.{}\nPod logs:\n{}", podName, exitCode, testClass, testMethod, logs);
                throw new AssertionError(
                        "Istio test pod failed for " + testClass + "#" + testMethod +
                        "\nPod logs:\n" + logs);
            }
        } finally {
            client.pods().inNamespace(namespace).withName(podName).delete();
        }
    }

    private static String createTestPod(KubernetesClient client, String namespace,
                                         String testClass, String testMethod) {
        final String podName = "istio-test-" +
                               UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        client.pods().inNamespace(namespace)
              .resource(new PodBuilder()
                                .withNewMetadata()
                                .withName(podName)
                                .withNamespace(namespace)
                                .withAnnotations(Map.of("sidecar.istio.io/inject", "true"))
                                .endMetadata()
                                .withNewSpec()
                                .withRestartPolicy("Never")
                                .addNewContainer()
                                .withName("test")
                                .withImage(IstioTestImage.IMAGE_NAME)
                                .withImagePullPolicy("Never")
                                .withArgs("--class", testClass, "--method", testMethod)
                                .addNewEnv()
                                .withName(HostOnlyExtension.RUNNING_IN_K8S_POD_ENV)
                                .withValue("true")
                                .endEnv()
                                .endContainer()
                                .endSpec()
                                .build())
              .create();
        return podName;
    }

    private static void waitForPodHealthy(KubernetesClient client,
                                           String podName, String namespace) {
        final boolean healthy = K8sClusterHelper.poll(
                Duration.ofMinutes(5), K8sClusterHelper.DEFAULT_POLL_INTERVAL, () -> {
            final Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null || pod.getStatus() == null) {
                return false;
            }
            if (!"Running".equals(pod.getStatus().getPhase())) {
                return false;
            }
            final var statuses = pod.getStatus().getContainerStatuses();
            if (statuses == null) {
                return false;
            }
            if (statuses.stream().noneMatch(cs -> "istio-proxy".equals(cs.getName()))) {
                throw new IllegalStateException(
                        "Pod '" + podName + "' is running but has no istio-proxy container — " +
                        "sidecar injection did not occur");
            }
            return statuses.stream()
                           .filter(cs -> "istio-proxy".equals(cs.getName()))
                           .anyMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
        });
        if (!healthy) {
            throw new IllegalStateException(
                    "Timed out waiting for pod '" + podName + "' to become healthy");
        }
        logger.info("Pod '{}' is running with istio-proxy ready", podName);
    }

    private static int waitForTestContainerTerminated(KubernetesClient client,
                                                       String podName, String namespace) {
        final int[] exitCode = {1};
        final boolean terminated = K8sClusterHelper.poll(
                Duration.ofMinutes(5), K8sClusterHelper.DEFAULT_POLL_INTERVAL, () -> {
            final Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null || pod.getStatus() == null ||
                pod.getStatus().getContainerStatuses() == null) {
                return false;
            }
            return pod.getStatus().getContainerStatuses().stream()
                      .filter(cs -> "test".equals(cs.getName()))
                      .filter(cs -> cs.getState() != null && cs.getState().getTerminated() != null)
                      .findFirst()
                      .map(cs -> { exitCode[0] = cs.getState().getTerminated().getExitCode(); return true; })
                      .orElse(false);
        });
        if (!terminated) {
            logger.warn("Timed out waiting for test container in pod '{}' to terminate", podName);
        }
        return exitCode[0];
    }

    private static String collectPodLogs(KubernetesClient client,
                                          String podName, String namespace) {
        try {
            return client.pods().inNamespace(namespace).withName(podName)
                         .inContainer("test").getLog();
        } catch (Exception e) {
            return "Failed to retrieve logs: " + e.getMessage();
        }
    }
}
