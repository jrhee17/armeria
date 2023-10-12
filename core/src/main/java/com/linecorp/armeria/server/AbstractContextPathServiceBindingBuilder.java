/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

abstract class AbstractContextPathServiceBindingBuilder<T extends ServiceConfigsBuilder>
        extends AbstractServiceBindingBuilder {

    private final AbstractContextPathServicesBuilder<T> contextPathServicesBuilder;

    AbstractContextPathServiceBindingBuilder(AbstractContextPathServicesBuilder<T> builder) {
        super(builder.contextPaths());
        this.contextPathServicesBuilder = builder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        contextPathServicesBuilder.addServiceConfigSetters(serviceConfigBuilder);
    }

    AbstractContextPathServicesBuilder<T> build(HttpService service) {
        requireNonNull(service, "service");
        build0(service);
        return contextPathServicesBuilder;
    }
}
