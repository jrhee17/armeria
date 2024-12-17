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

package com.linecorp.armeria.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebSocketItTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route()
              .maxRequestLength(10)
              .path("/request-length")
              .build(WebSocketService.of((ctx, in) -> {
                  final WebSocketWriter writer = WebSocket.streaming();
                  in.subscribe(new Subscriber<WebSocketFrame>() {
                      @Override
                      public void onSubscribe(Subscription subscription) {
                          subscription.request(Long.MAX_VALUE);
                      }

                      @Override
                      public void onNext(WebSocketFrame webSocketFrame) {
                          writer.write(webSocketFrame);
                      }

                      @Override
                      public void onError(Throwable throwable) {
                          writer.close(throwable);
                      }

                      @Override
                      public void onComplete() {
                          writer.close();
                      }
                  });
                  return writer;
              }));
        }
    };

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void requestLengthExceeded() throws Exception {
        final SessionProtocol protocol = SessionProtocol.H2C;
        final WebSocketSession session =
                WebSocketClient.of()
                               .connect(server.uri(protocol, SerializationFormat.WS) + "/request-length")
                               .join();
        final WebSocketWriter outbound = session.outbound();

        eventLoop.get().scheduleWithFixedDelay(() -> {
            outbound.write("abcd");
        }, 100, 100, TimeUnit.MILLISECONDS);
        final List<WebSocketFrame> frames = session.inbound().collect().join();
        final WebSocketFrame lastFrame = frames.get(frames.size() - 1);
        assertThat(lastFrame).isInstanceOf(CloseWebSocketFrame.class);
        assertThat(((CloseWebSocketFrame) lastFrame).reasonPhrase()).isEqualTo("413 Request Entity Too Large");

        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final RequestLog serviceLog = sctx.log().whenComplete().join();
        assertThat(serviceLog.requestCause()).isInstanceOf(ContentTooLargeException.class);
    }
}
