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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class for starting a Kind cluster with Istio.
 * If a cluster is already running, it returns early.
 * The container will be automatically cleaned up by testcontainers when the process terminates.
 */
public final class LocalDevClusterMain {

    private static final Logger logger = LoggerFactory.getLogger(LocalDevClusterMain.class);

    public static void main(String[] args) throws Exception {
        try (IstioState state = IstioState.connectOrCreate()) {
            logger.info("K3s cluster with Istio is ready. Kubeconfig: {}", state.kubeconfigPath());
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
