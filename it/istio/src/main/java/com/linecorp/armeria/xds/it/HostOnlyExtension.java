package com.linecorp.armeria.xds.it;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;

/**
 * Base class for JUnit extensions that must only run when executing on the host,
 * not inside a Kubernetes pod. Subclass this instead of {@link AbstractAllOrEachExtension}
 * when the extension manages host-side infrastructure (cluster lifecycle, container
 * management, etc.) that must not execute inside the in-cluster test job.
 *
 * <p>Implement {@link #setUp} and optionally {@link #tearDown}. Both are no-ops
 * when {@code RUNNING_IN_K8S_POD=true}.
 */
public abstract class HostOnlyExtension extends AbstractAllOrEachExtension {

    static final String RUNNING_IN_K8S_POD_ENV = "RUNNING_IN_K8S_POD";

    static boolean notRunningInPod() {
        return !Boolean.parseBoolean(System.getenv(RUNNING_IN_K8S_POD_ENV));
    }

    @Override
    protected final void before(ExtensionContext context) throws Exception {
        if (notRunningInPod()) {
            setUp(context);
        }
    }

    @Override
    protected final void after(ExtensionContext context) throws Exception {
        if (notRunningInPod()) {
            tearDown(context);
        }
    }

    protected abstract void setUp(ExtensionContext context) throws Exception;

    protected void tearDown(ExtensionContext context) throws Exception {}
}
