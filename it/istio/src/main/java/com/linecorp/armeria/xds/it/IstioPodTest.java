package com.linecorp.armeria.xds.it;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test method to run inside a Kubernetes Job in the Istio-enabled K3s cluster
 * rather than locally. The local test body is skipped; the extension submits a Job,
 * waits for it, and propagates success or failure back to JUnit.
 *
 * <p>The enclosing test class must register {@link IstioClusterExtension}:
 * <pre>{@code
 * class MyTest {
 *     @RegisterExtension
 *     static IstioClusterExtension istio = new IstioClusterExtension();
 *
 *     @IstioPodTest
 *     void myTest() {
 *         // runs inside the K8s Job
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
@ExtendWith(IstioTestExtension.class)
public @interface IstioPodTest {}
