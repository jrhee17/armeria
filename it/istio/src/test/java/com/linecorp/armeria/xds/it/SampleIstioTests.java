package com.linecorp.armeria.xds.it;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SampleIstioTests {

    private static final Logger logger = LoggerFactory.getLogger(SampleIstioTests.class);

    @RegisterExtension
    static IstioClusterExtension istio = new IstioClusterExtension();

    @IstioPodTest
    void shouldRunInsideCluster() {
        // Body intentionally empty — real execution happens inside the K8s Job.
        logger.info("Running inside istio pod");
    }
}
