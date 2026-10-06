/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebClientJsonTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new Object() {
                @Post("/echo")
                @ConsumesJson
                @ProducesJson
                public Item echoPost(Item item) {
                    return item;
                }

                @Put("/echo")
                @ConsumesJson
                @ProducesJson
                public Item echoPut(Item item) {
                    return item;
                }

                @Patch("/echo")
                @ConsumesJson
                @ProducesJson
                public Item echoPatch(Item item) {
                    return item;
                }

                @Get("/items")
                @ProducesJson
                public List<Item> listItems() {
                    return ImmutableList.of(new Item("item1", 1), new Item("item2", 2));
                }

                @Post("/error")
                @ConsumesJson
                public HttpResponse error(Item item) {
                    return HttpResponse.ofJson(HttpStatus.BAD_REQUEST,
                                               ImmutableMap.of("error", "bad request"));
                }
            });
        }
    };

    @Test
    void postJson_toJson() {
        final WebClient client = server.webClient();
        final Item item = new Item("Armeria", 42);

        final ResponseEntity<Item> entity =
                client.postJson("/echo", item)
                      .asJson(Item.class)
                      .join();

        assertThat(entity.status()).isEqualTo(HttpStatus.OK);
        assertThat(entity.content().name).isEqualTo("Armeria");
        assertThat(entity.content().value).isEqualTo(42);
    }

    @Test
    void putJson_toJson() {
        final WebClient client = server.webClient();
        final Item item = new Item("updated", 99);

        final ResponseEntity<Item> entity =
                client.putJson("/echo", item)
                      .asJson(Item.class)
                      .join();

        assertThat(entity.status()).isEqualTo(HttpStatus.OK);
        assertThat(entity.content().name).isEqualTo("updated");
        assertThat(entity.content().value).isEqualTo(99);
    }

    @Test
    void patchJson_toJson() {
        final WebClient client = server.webClient();
        final Item item = new Item("patched", 7);

        final ResponseEntity<Item> entity =
                client.patchJson("/echo", item)
                      .asJson(Item.class)
                      .join();

        assertThat(entity.status()).isEqualTo(HttpStatus.OK);
        assertThat(entity.content().name).isEqualTo("patched");
        assertThat(entity.content().value).isEqualTo(7);
    }

    @Test
    void postJson_withCustomObjectMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        final WebClient client = server.webClient();
        final Item item = new Item("custom-mapper", 100);

        final ResponseEntity<Item> entity =
                client.postJson("/echo", item, mapper)
                      .asJson(Item.class, mapper)
                      .join();

        assertThat(entity.status()).isEqualTo(HttpStatus.OK);
        assertThat(entity.content().name).isEqualTo("custom-mapper");
        assertThat(entity.content().value).isEqualTo(100);
    }

    @Test
    void asJson_withTypeReference() {
        final WebClient client = server.webClient();

        final ResponseEntity<List<Item>> entity =
                client.get("/items")
                      .asJson(new TypeReference<List<Item>>() {})
                      .join();

        assertThat(entity.status()).isEqualTo(HttpStatus.OK);
        assertThat(entity.content()).hasSize(2);
        assertThat(entity.content().get(0).name).isEqualTo("item1");
        assertThat(entity.content().get(1).name).isEqualTo("item2");
    }

    @Test
    void asJson_nonSuccessStatus_throwsInvalidHttpResponseException() {
        final WebClient client = server.webClient();
        final Item item = new Item("bad", 0);

        assertThatThrownBy(() -> {
            client.postJson("/error", item)
                  .asJson(Item.class)
                  .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(InvalidHttpResponseException.class)
          .satisfies(e -> {
              assertThat(e.getCause().getMessage()).contains("400");
          });
    }

    @Test
    void httpRequest_ofJson() {
        final Item item = new Item("standalone", 1);

        final HttpRequest request = HttpRequest.ofJson(HttpMethod.POST, "/echo", item);

        assertThat(request.headers().method()).isEqualTo(HttpMethod.POST);
        assertThat(request.headers().path()).isEqualTo("/echo");
        assertThat(request.headers().contentType()).isEqualTo(MediaType.JSON);

        // Verify the request can be executed through WebClient
        final WebClient client = server.webClient();
        final ResponseEntity<Item> entity =
                client.execute(request)
                      .asJson(Item.class)
                      .join();

        assertThat(entity.content().name).isEqualTo("standalone");
        assertThat(entity.content().value).isEqualTo(1);
    }

    @Test
    void as_withResponseAs() {
        final WebClient client = server.webClient();
        final Item item = new Item("via-responseAs", 77);

        // FutureResponseAs now extends Function, so it can be passed directly to HttpResponse.as()
        final ResponseEntity<Item> entity =
                client.postJson("/echo", item)
                      .as(ResponseAs.json(Item.class))
                      .join();

        assertThat(entity.status()).isEqualTo(HttpStatus.OK);
        assertThat(entity.content().name).isEqualTo("via-responseAs");
        assertThat(entity.content().value).isEqualTo(77);
    }

    @Test
    void as_withCustomFunction() {
        final WebClient client = server.webClient();
        final Item item = new Item("raw-string", 33);

        // FutureResponseAs is a @FunctionalInterface, so lambdas work too
        final String body =
                client.postJson("/echo", item)
                      .as(response -> response.aggregate()
                                              .thenApply(agg -> agg.contentUtf8()))
                      .join();

        assertThat(body).contains("raw-string");
        assertThat(body).contains("33");
    }

    @Test
    void as_withTypeReference() {
        final WebClient client = server.webClient();

        final ResponseEntity<List<Item>> entity =
                client.get("/items")
                      .as(ResponseAs.json(new TypeReference<List<Item>>() {}))
                      .join();

        assertThat(entity.status()).isEqualTo(HttpStatus.OK);
        assertThat(entity.content()).hasSize(2);
        assertThat(entity.content().get(0).name).isEqualTo("item1");
    }

    @Test
    void postJson_withMapPayload() {
        final WebClient client = server.webClient();
        final Map<String, Object> payload = ImmutableMap.of("name", "from-map", "value", 55);

        final ResponseEntity<Item> entity =
                client.postJson("/echo", payload)
                      .asJson(Item.class)
                      .join();

        assertThat(entity.content().name).isEqualTo("from-map");
        assertThat(entity.content().value).isEqualTo(55);
    }

    static class Item {
        @JsonProperty
        final String name;
        @JsonProperty
        final int value;

        @JsonCreator
        Item(@JsonProperty("name") String name, @JsonProperty("value") int value) {
            this.name = name;
            this.value = value;
        }
    }
}
