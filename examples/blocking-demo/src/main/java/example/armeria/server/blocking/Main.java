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

package example.armeria.server.blocking;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.ShutdownHooks;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;

import example.armeria.server.blocking.Hello.HelloReply;
import example.armeria.server.blocking.Hello.HelloRequest;
import example.armeria.server.blocking.HelloServiceGrpc.HelloServiceImplBase;
import io.grpc.stub.StreamObserver;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080);

        server.closeOnJvmShutdown();

        server.start().join();

        logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs",
                    server.activeLocalPort());
    }

    /**
     * Returns a new {@link Server} instance configured with annotated HTTP services.
     *
     * @param port the port that the server is to be bound to
     */
    private static Server newServer(int port) {
        final ServerBuilder sb = Server.builder();
        sb.http(port);
        configureServices(sb);
        return sb.build();
    }

    static void configureServices(ServerBuilder sb) {

        // example for using an annotated service
        sb.annotatedService()
          .pathPrefix("/annotated")
          .useBlockingTaskExecutor(true)
          .build(new Object() {
              @Get("/hello")
              public String world() {
                  logger.info("{}", Thread.currentThread());
                  try {
                      Thread.sleep(3000);
                  } catch (InterruptedException e) {
                      e.printStackTrace();
                  }
                  return "world";
              }
          });

        // example for using a grpc service
        sb.service(GrpcService.builder()
                              .useBlockingTaskExecutor(true)
                              .addService(new HelloServiceImplBase() {
                                  @Override
                                  public void hello(HelloRequest request,
                                                    StreamObserver<HelloReply> responseObserver) {
                                      logger.info("{}", Thread.currentThread());
                                      try {
                                          Thread.sleep(3000);
                                      } catch (InterruptedException e) {
                                          e.printStackTrace();
                                      }
                                      responseObserver.onNext(HelloReply.newBuilder().setMessage("world").build());
                                      responseObserver.onCompleted();
                                  }
                              })
                              .build());

        // using a custom executor
        final ThreadFactory threadFactory = ThreadFactories.newThreadFactory("custom-blocking", false);
        final ExecutorService es = RequestContext.makeContextPropagating(
                Executors.newScheduledThreadPool(20, threadFactory));
        sb.service("/custom/hello", (ctx, req) -> {
            logger.info("Outside: {}", Thread.currentThread());
            return HttpResponse.from(CompletableFuture.supplyAsync(() -> {
                logger.info("Inside: {}", Thread.currentThread());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                }
                return HttpResponse.of("world");
            }, es));
        });

        // ensures ExecutorService is shut down when jvm shuts down
        ShutdownHooks.addClosingTask(es::shutdown);

        sb.serviceUnder("/docs", DocService.builder().build());
    }
}
