/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.internal.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.Serializers;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import testing.thrift.main.HelloRequest;
import testing.thrift.main.HelloResponse;
import testing.thrift.main.HelloResponse._Fields;
import testing.thrift.main.ThriftMetadataService;

class LoggingServiceTest {

    static final ObjectMapper objectMapper = new ObjectMapper();
    static {
        objectMapper.registerModule(new Module() {
            @Override
            public String getModuleName() {
                return "my-module";
            }

            @Override
            public Version version() {
                return new Version(1, 1, 1, "asdf", null, null);
            }

            @Override
            public void setupModule(SetupContext context) {
                context.addSerializers(new MyJacksonSerializers());
            }
        });
    }

    private static final Logger serverLogger =
            (Logger) LoggerFactory.getLogger(LoggingServiceTest.class.getName() + "@class");
    private static final TestAppender serverAppender = new TestAppender();
    static {
        serverAppender.start();
        serverLogger.addAppender(serverAppender);
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final LogWriter writer = LogWriter.builder()
                                              .logFormatter(LogFormatter.builderForText().build())
                                              .logger(serverLogger)
                                              .build();
            sb.decorator(LoggingService.builder().logWriter(writer).newDecorator());
            sb.service("/",
                       THttpService.builder()
                                   .addService((ThriftMetadataService.Iface) req -> response())
                                   .build());
        }
    };

    @AfterEach
    void afterEach() {
        assertThat(serverAppender.events).isEmpty();
    }

    @Test
    void testHello(TestInfo testInfo) throws Exception {
        final Logger clientLogger = (Logger) LoggerFactory.getLogger(testInfo.getTestClass().get().getName() +
                                                                     '.' + testInfo.getDisplayName());
        final TestAppender clientAppender = new TestAppender();
        clientAppender.start();
        clientLogger.addAppender(clientAppender);
        final LogWriter logWriter = LogWriter
                .builder()
                .logFormatter(LogFormatter.builderForText()
                                          .requestContentSanitizer((ctx, content) -> {
                                              try {
                                                  return objectMapper.writer().writeValueAsString(content);
                                              } catch (JsonProcessingException e) {
                                                  throw new RuntimeException(e);
                                              }
                                          })
                                          .responseContentSanitizer((ctx, content) -> {
                                              try {
                                                  return objectMapper.writer().writeValueAsString(content);
                                              } catch (JsonProcessingException e) {
                                                  throw new RuntimeException(e);
                                              }
                                          })
                                          .build())
                .logger(clientLogger)
                .build();
        final ThriftMetadataService.Iface iface =
                ThriftClients.builder(server.httpUri())
                             .decorator(LoggingClient.builder()
                                                     .logWriter(logWriter)
                                                     .newDecorator())
                             .build(ThriftMetadataService.Iface.class);
        final HelloResponse res = iface.hello(request());
        assertThat(res).isEqualTo(response());
        await().untilAsserted(() -> assertThat(clientAppender.events).hasSize(2));
        await().untilAsserted(() -> assertThat(serverAppender.events).hasSize(2));
        System.out.println(clientAppender.events);
        System.out.println(serverAppender.events);
        serverAppender.events.clear();

        final Map<? extends TFieldIdEnum, FieldMetaData> metadata = FieldMetaData.getStructMetaDataMap(
                HelloResponse.class);
        System.out.println(metadata.get(_Fields.MASK_FIELD).getFieldAnnotations());
    }

    private static final class TestAppender extends AppenderBase<ILoggingEvent> {
        private final BlockingQueue<ILoggingEvent> events = new LinkedBlockingQueue<>();

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
        }
    }

    private static HelloRequest request() {
        return new HelloRequest()
                .setPublicField("public")
                .setMaskField("mask")
                .setOmitField("omit");
    }

    private static HelloResponse response() {
        return new HelloResponse()
                .setPublicField("public")
                .setMaskField("mask")
                .setOmitField("omit");
    }

    public static class ThriftToJsonSerializer extends JsonSerializer<TBase> {

        @Override
        public void serialize(TBase o, JsonGenerator jsonGenerator,
                              SerializerProvider serializerProvider) throws IOException {
            jsonGenerator.writeStartObject();
            final Map<TFieldIdEnum, FieldMetaData> metadatas = FieldMetaData.getStructMetaDataMap(o.getClass());
            for (Map.Entry<TFieldIdEnum, FieldMetaData> entry : metadatas.entrySet()) {
                final FieldMetaData fieldMetaData = entry.getValue();
                final Map<String, String> annotations = fieldMetaData.getFieldAnnotations();
                final String logging = annotations.getOrDefault("logging", "public");
                if ("omit".equals(logging)) {
                    continue;
                }
                final Object value = o.getFieldValue(entry.getKey());
                if (value != null) {
                    if ("mask".equals(logging)) {
                        jsonGenerator.writeObjectField(fieldMetaData.fieldName, "***");
                    } else {
                        jsonGenerator.writeObjectField(fieldMetaData.fieldName, value);
                    }
                }
            }
            jsonGenerator.writeEndObject();
        }
    }

    public static class RpcRequestSerializer extends JsonSerializer<RpcRequest> {

        @Override
        public void serialize(RpcRequest value, JsonGenerator jsonGenerator, SerializerProvider serializers)
                throws IOException {
            jsonGenerator.writeStartObject();
            value.serviceName();
            value.method();
            value.params();
            jsonGenerator.writeStringField("service", value.serviceName());
            jsonGenerator.writeStringField("method", value.method());
            jsonGenerator.writeArrayFieldStart("params");
            for (Object param : value.params()) {
                jsonGenerator.writeObject(param);
            }
            jsonGenerator.writeEndArray();
            jsonGenerator.writeEndObject();
        }
    }

    private static class MyJacksonSerializers extends Serializers.Base {
        @Override
        public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type,
                                                BeanDescription beanDesc) {
            final Class<?> rawType = type.getRawClass();
            if (RpcRequest.class.isAssignableFrom(rawType)) {
                return new RpcRequestSerializer();
            }
            if (TBase.class.isAssignableFrom(rawType)) {
                return new ThriftToJsonSerializer();
            }
            return super.findSerializer(config, type, beanDesc);
        }
    }
}
