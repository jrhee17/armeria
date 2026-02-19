package com.linecorp.armeria.xds.it;

import org.junit.jupiter.api.Test;

class IstioImageBuildTest {
    @Test
    void dockerImageBuildsSuccessfully() {
        IstioTestImage.build().get();
    }
}
