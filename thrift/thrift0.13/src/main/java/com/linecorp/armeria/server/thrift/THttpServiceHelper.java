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

package com.linecorp.armeria.server.thrift;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.internal.common.thrift.ThriftFunction;
import com.linecorp.armeria.server.ServiceRequestContext;

interface THttpServiceHelper  {

    void invoke(ServiceRequestContext ctx, SerializationFormat serializationFormat, int seqId,
                ThriftFunction func, RpcRequest call, CompletableFuture<HttpResponse> res,
                HttpRequest req, String serviceName, String methodName);
}
