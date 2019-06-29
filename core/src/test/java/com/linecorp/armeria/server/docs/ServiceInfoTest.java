/*
 *  Copyright 2018 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

class ServiceInfoTest {

    private static MethodInfo createMethodInfo(String methodName, HttpMethod method,
                                               String endpointPathMapping) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", endpointPathMapping)
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        return new MethodInfo(methodName, TypeSignature.ofBase("T"), ImmutableList.of(), ImmutableList.of(),
                              ImmutableList.of(endpoint), method, null);
    }

    @Test
    public void testCollectMethodsWithDifferentMethods() {

        final MethodInfo barMethodInfo = createMethodInfo("bar", HttpMethod.GET, "exact:/bar");
        final MethodInfo fooMethodInfo = createMethodInfo("foo", HttpMethod.GET, "exact:/foo");

        final List<MethodInfo> inputMethodInfos = ImmutableList.of(barMethodInfo, fooMethodInfo);
        final List<MethodInfo> collectMethods =
                ImmutableList.copyOf(ServiceInfo.collectMethods(inputMethodInfos));

        assertEquals(inputMethodInfos, collectMethods);
    }

    @Test
    public void testCollectMethodGrouping() {

        final MethodInfo fooGet1 = createMethodInfo("foo", HttpMethod.GET, "exact:/foo1");
        final MethodInfo fooGet2 = createMethodInfo("foo", HttpMethod.GET, "exact:/foo2");
        final MethodInfo fooPost3 = createMethodInfo("foo", HttpMethod.POST, "exact:/foo3");
        final MethodInfo fooPost4 = createMethodInfo("foo", HttpMethod.POST, "exact:/foo4");

        final List<MethodInfo> inputMethodInfos = ImmutableList.of(fooGet1, fooGet2, fooPost3, fooPost4);
        final List<MethodInfo> collectMethods =
                ImmutableList.copyOf(ServiceInfo.collectMethods(inputMethodInfos));

        assertEquals(2, collectMethods.size());
    }
}
