/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.xds.XdsBootstrap;

public class XdsHttpClient {

interface EndpointHint {

    Function<HttpClient, HttpClient> endpointSelectionFilter();

    HttpClient applyInitializeDecorate(HttpClient client);
}

    XdsHttpClient() {
EndpointHint endpointHint = null;
WebClient wc = WebClient.builder(endpointHint).build();
        WebClient.builder(EndpointGroup.of("a.com")).decorator(new XdsClient()).build().execute()

        WebClient.builder(EndpointSelectingClient).build().execute();
        XdsBootstrap bootstrap;

//        XdsEndpointGroup xdsEndpointGroup;
//        EndpointGroup.of(EndpointGroup.of(), xdsEndpointGroup);
//        HealthCheckedEndpointGroup.of(xdsEndpointGroup, "/");

        WebClient.builder(XdsEndpoint.of(bootstrap)).decorator(XdsHttpClient.of()).build().execute()
        XdsHttpClient
    }

    EndpointGroup endpointGroup;

    HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception;
}
