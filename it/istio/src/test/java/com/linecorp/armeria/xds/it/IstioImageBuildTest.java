package com.linecorp.armeria.xds.it;

import org.junit.jupiter.api.Test;

@EnabledIfDockerAvailable
class IstioImageBuildTest {
    @Test
    void dockerImageBuildsSuccessfully() {
        IstioTestImage.build().get();
    }
}
