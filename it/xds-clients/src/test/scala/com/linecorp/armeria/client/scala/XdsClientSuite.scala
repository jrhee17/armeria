/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.scala

import com.google.common.collect.ImmutableList
import com.linecorp.armeria.client.{ClientOptions, Clients, WebClient}
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import com.linecorp.armeria.xds.XdsBootstrap
import com.linecorp.armeria.xds.client.endpoint.XdsContextInitializer
import com.linecorp.armeria.xds.internal.XdsTestResources
import io.envoyproxy.controlplane.cache.v3.{SimpleCache, Snapshot}
import io.envoyproxy.controlplane.server.V3DiscoveryServer
import munit.FunSuite
import org.assertj.core.api.Assertions.assertThat

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Using

class XdsClientSuite extends FunSuite {

  val BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster"
  private val cache = new SimpleCache[String](node => "GROUP")

  override def beforeAll(): Unit = {
    server.beforeAll(null)
    controlPlaneServer.beforeAll(null)
  }

  override def afterAll(): Unit = {
    server.afterAll(null)
    controlPlaneServer.afterAll(null)
  }

  val server: ServerExtension = new ServerExtension() {
    override protected def configure(sb: ServerBuilder): Unit = {
      sb.service("/hello", (ctx, req) => HttpResponse.ofJson(MyResponse("world")));
    }
  }

  val controlPlaneServer: ServerExtension = new ServerExtension() {
    override protected def configure(sb: ServerBuilder): Unit = {
      val v3DiscoveryServer = new V3DiscoveryServer(cache)
      sb.service(GrpcService.builder.addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl).addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl).addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl).addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl).addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl).build)
    }
  }

  override def beforeEach(context: BeforeEach): Unit = {
    val loadAssignment = XdsTestResources.loadAssignment("cluster", server.httpUri)
    val httpCluster = XdsTestResources.createStaticCluster("cluster", loadAssignment)
    val httpListener = XdsTestResources.staticResourceListener
    cache.setSnapshot("GROUP", Snapshot.create(ImmutableList.of(httpCluster), ImmutableList.of, ImmutableList.of(httpListener), ImmutableList.of, ImmutableList.of, "1"))
  }

  test("Derived ScalaRestClient correctly uses xds") {
    val configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME)
    val uri = controlPlaneServer.httpUri
    val loadAssignment = XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME, uri.getHost, uri.getPort)
    val bootstrapCluster = XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, loadAssignment)
    val bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster)
    Using.Manager { use =>
      val xdsBootstrap = use(XdsBootstrap.of(bootstrap))
      val preparation = use(XdsContextInitializer.of("listener", xdsBootstrap))
      var restClient = ScalaRestClient(WebClient.of(preparation))
      var content = Await.result(restClient.get("/hello").execute[MyResponse](), Duration.Inf).content()
      assertThat(content).isEqualTo(MyResponse("world"))

      val maxResponseLength = restClient.options().maxResponseLength()
      restClient = Clients.newDerivedClient(restClient, ClientOptions.MAX_RESPONSE_LENGTH.newValue(maxResponseLength + 1))
      content = Await.result(restClient.get("/hello").execute[MyResponse](), Duration.Inf).content()
      assertThat(content).isEqualTo(MyResponse("world"))
    }.get
  }
}

case class MyResponse(hello: String)
