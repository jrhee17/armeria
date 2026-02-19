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

import java.nio.file.Path;
import java.nio.file.Paths;

final class KubeconfigUtil {

    private KubeconfigUtil() {}

    static final String LOCAL_KUBE_ENV = "LOCAL_KUBE";
    private static final Path DEFAULT_KUBECONFIG_PATH = Paths.get("build/kubeconfig/kubeconfig.yaml");
    private static final Path CONTAINER_METADATA_PATH = Paths.get("build/kubeconfig/istio-kind.metadata");

    static boolean useLocalKube() {
        return "true".equalsIgnoreCase(System.getenv(LOCAL_KUBE_ENV));
    }

    static Path kubeconfigPath() {
        return DEFAULT_KUBECONFIG_PATH;
    }

    static Path containerMetadataPath() {
        return CONTAINER_METADATA_PATH;
    }
}
