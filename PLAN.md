# PLAN

## Goal
Use a **single** `HttpJsonToGrpcTranscodingService` decorator implementation for both:
- the existing `GrpcService` HTTP/JSON transcoding feature, and
- a standalone decorator for proxying HTTP/JSON requests to upstream gRPC services,
while clarifying serialization semantics and enabling optional gRPC PROTO hop.

## Example (Decorator-only, full options + upstream WebClient)
```java
// Shared options (defaults).
HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.of();

// Delegate that forwards the (already transcoded) gRPC request upstream.
WebClient upstream = WebClient.builder("h2c://upstream.example.com")
                              .build();
HttpService proxy = (ctx, req) -> upstream.execute(req);

Function<? super HttpService, ? extends HttpService> transcodingDecorator =
    HttpJsonToGrpcTranscodingService.newBuilder()
        .descriptorSet(Paths.get("api.pb"))
        .options(options)
        .transcodedGrpcSerializationFormat(GrpcSerializationFormats.JSON) // default
        .newDecorator();

Server.builder()
      .contextPath("/proxy", ctx -> {
          ctx.decorator(transcodingDecorator);
          ctx.route().pathPrefix("/").build(proxy);
      })
      .build();
```

## Key Conclusions So Far
- `supportedSerializationFormats()` currently means **gRPC wire formats** a service can decode/encode.
- `HttpJsonTranscodingService` today always emits **gRPC JSON** (`application/grpc+json`) to its delegate.
- For a decorator/transcoder, `supportedSerializationFormats()` is misleading because the **incoming side** is HTTP/JSON, not gRPC wire formats.
- A new decorator-facing API should explicitly expose the **gRPC hop format**, e.g.:
  - `SerializationFormat transcodedGrpcSerializationFormat()`
- If the decorator supports PROTO on the gRPC hop, it must convert JSON ↔ proto **messages**.

## API Direction
- **Phase 1: no new public API.** Rewire internals to use the shared decorator implementation while
  preserving existing public surface.
- Revisit a decorator-facing API (e.g., `transcodedGrpcSerializationFormat()`) only after internal
  consolidation is proven.

## Behavior Modes
### 1) Current / JSON gRPC hop
- HTTP/JSON → gRPC **JSON** (framed) → delegate
- No message conversion required; just frame JSON bytes.
- Delegate must support JSON in `supportedSerializationFormats()`.

### 2) PROTO gRPC hop (new)
- HTTP/JSON → **proto message** → protobuf bytes → gRPC **PROTO** (framed)
- Requires JSON ↔ proto message conversion.

## Implementation Approach for PROTO Hop
### Request path
- Reuse existing `convertToJson(...)` to apply HTTP rule mapping.
- Parse JSON into a proto message using `GrpcJsonMarshaller`:
  - `jsonMarshaller.deserializeMessage(requestMarshaller, jsonInputStream)`
- Serialize message to protobuf bytes using request marshaller or `GrpcMessageMarshaller`.
- Frame and send with `application/grpc+proto`.

### Response path
- Deframe to get protobuf bytes.
- Deserialize proto bytes into a message.
- If response type is `HttpBody`, build HTTP response directly from fields.
- Otherwise serialize message to JSON via `GrpcJsonMarshaller` and apply existing `responseBody` extraction logic.

## Decorator Options (HttpJsonToGrpcTranscodingService)
**Required**
- Descriptor sources (at least one):
  - `serviceDescriptor(...)` / `serviceDescriptors(...)`
  - `descriptorSet(Path|byte[])`
- `options(HttpJsonTranscodingOptions)` (covers: ignoreProtoHttpRule, additionalHttpRules, conflictStrategy,
  queryParamMatchRules, errorHandler).

**Optional / advanced**
- `transcodedGrpcSerializationFormat(SerializationFormat)` (default: gRPC JSON).
- `jsonMarshallerFactory(Function<ServiceDescriptor, GrpcJsonMarshaller>)` (only needed to override the default
  Gson-based marshaller; PROTO hop requires a marshaller that can handle DynamicMessage).

**Validation**
- PROTO hop: require `PrototypeMarshaller` and delegate supports PROTO.
- JSON hop: require delegate supports JSON.

**Descriptor source merge**
- Both service descriptors and descriptor set may be provided.
- Merge order follows **builder call order** (“first wins” on duplicate method full name).
- Route‑level duplicates still throw (unchanged).

## Pooling Conclusions (Decorator Context)
- No pooling changes are needed just because the shared decorator is used for both `GrpcService` and standalone use.
- Request path:
  - Default (unpooled) aggregation avoids extra copies given current parsing uses `content.array()`.
  - Pooled aggregation would copy into a pooled `ByteBuf` and then copy again on `array()`, so no benefit
    unless parsing is refactored to stream/ByteBuf access.
- Response path:
  - Pooled deframed data is zero‑copy when the response is passed through (the common case).
  - If responses are always transformed (e.g., PROTO↔JSON), pooling helps less unless conversion avoids `array()`.
- Decorator safety rule:
  - If a decorator ignores the delegate response, it must `abort()` it to avoid pooled buffer leaks.

## Notes / Risks
- gRPC JSON (`application/grpc+json`) is not commonly supported by default gRPC servers.
- PROTO hop is more compatible with “normal” gRPC servers, but requires conversion logic.

## Decorator Implementation Note
- The same decorator implementation will be used by `GrpcService` internally and by standalone decorators.
- `GrpcServiceBuilder.enableHttpJsonTranscoding(...)` should attach the decorator rather than return a
  separate wrapper service.
- Preserve the same delegate-chain assumption: a FramedGrpcService is internally available and can be
  resolved via the delegate chain.
- Path prefix / rewrite is intentionally not part of the initial decorator API. Use route binding or a
  separate rewrite decorator if needed; add later only if necessary.

## Next Steps (if proceeding)
1) Rewire internals to use the shared decorator implementation without changing public APIs or behavior.
   - Preserve decorator order: existing user/server decorators should run **before** HTTP/JSON transcoding
     (i.e., apply the transcoding decorator last in `GrpcServiceBuilder.enableHttpJsonTranscoding(...)`).
2) Adjust type hierarchy:
   - `UnframedGrpcService` implements `GrpcService`.
   - `HttpJsonTranscodingService` becomes a pure decorator (no `GrpcService`).
3) Expose the decorator and its builder as public API after the internal consolidation lands.
4) Expose the decorator via DocService once the public API is stable.
