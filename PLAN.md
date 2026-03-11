# PLAN

## Goal
Use a **single** `HttpJsonToGrpcTranscodingService` service implementation for both:
- the existing `GrpcService` HTTP/JSON transcoding feature, and
- a standalone `HttpServiceWithRoutes` for proxying HTTP/JSON requests to upstream gRPC services,
while clarifying serialization semantics and enabling optional gRPC PROTO hop.

## Example (Standalone service, default options + upstream WebClient)
```java
// Shared options (defaults).
HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.of();

// Delegate that forwards the (already transcoded) gRPC request upstream.
WebClient upstream = WebClient.builder("h2c://upstream.example.com")
                              .build();
HttpService delegate = (ctx, req) -> upstream.execute(req);

HttpJsonToGrpcTranscodingService transcoder =
    HttpJsonToGrpcTranscodingService.newBuilder(delegate)
        .descriptorSet(Paths.get("api.pb"))
        .options(options)
        .transcodedGrpcSerializationFormat(GrpcSerializationFormats.JSON) // default
        .build();

Server.builder()
      .service("/proxy", transcoder)
      .build();
```

## Key Conclusions So Far
- `supportedSerializationFormats()` currently means **gRPC wire formats** a service can decode/encode.
- `HttpJsonTranscodingService` today always emits **gRPC JSON** (`application/grpc+json`) to its delegate.
- For a standalone transcoding service, `supportedSerializationFormats()` is misleading because the **incoming side** is HTTP/JSON, not gRPC wire formats.
- A new service-facing API should explicitly expose the **gRPC hop format**, e.g.:
  - `SerializationFormat transcodedGrpcSerializationFormat()`
- A standalone service can own the transcoded routes, avoiding path-remapping issues and making DocService exposure straightforward.
- If the service supports PROTO on the gRPC hop, it must convert JSON ↔ proto **messages**.

## API Direction
- **Phase 1: no new public API.** Rewire internals to use the shared service implementation while
  preserving existing public surface.
- Revisit a service-facing API (e.g., `transcodedGrpcSerializationFormat()`) only after internal
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

## Service Options (HttpJsonToGrpcTranscodingService)
**Required**
- Descriptor sources (at least one):
  - `serviceDescriptor(...)` / `serviceDescriptors(...)`
  - `descriptorSet(Path|byte[])`
- `options(HttpJsonTranscodingOptions)` (covers: ignoreProtoHttpRule, additionalHttpRules, conflictStrategy,
  queryParamMatchRules, errorHandler).
- `delegate(HttpService)` (or equivalent functional delegate) for upstream forwarding.

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

## Pooling Conclusions (Transcoding Service Context)
- No pooling changes are needed just because the shared service is used for both `GrpcService` and standalone use.
- Request path:
  - Default (unpooled) aggregation avoids extra copies given current parsing uses `content.array()`.
  - Pooled aggregation would copy into a pooled `ByteBuf` and then copy again on `array()`, so no benefit
    unless parsing is refactored to stream/ByteBuf access.
- Response path:
  - Pooled deframed data is zero‑copy when the response is passed through (the common case).
  - If responses are always transformed (e.g., PROTO↔JSON), pooling helps less unless conversion avoids `array()`.
- Delegate safety rule:
  - If the transcoding service ignores the delegate response, it must `abort()` it to avoid pooled buffer leaks.

## Notes / Risks
- gRPC JSON (`application/grpc+json`) is not commonly supported by default gRPC servers.
- PROTO hop is more compatible with “normal” gRPC servers, but requires conversion logic.

## Service Implementation Note
- The same implementation will be used by `GrpcService` internally and by a standalone `HttpServiceWithRoutes`.
- `GrpcServiceBuilder.enableHttpJsonTranscoding(...)` should use the shared engine + service adapter rather than
  introducing a public decorator-only surface.
- Preserve the same delegate-chain assumption: a FramedGrpcService is internally available and can be
  resolved via the delegate chain.
- Path prefix / rewrite is intentionally not part of the initial service API. Use route binding or a
  separate rewrite decorator if needed; add later only if necessary.
## Shared Transcoding Engine (Composition)
- Extract the HTTP/JSON ↔ gRPC conversion logic into a small, package‑private “engine” that has no direct
  dependency on server‑only types beyond what’s needed for request/response conversion.
- Thin adapters (server `GrpcService`, standalone `HttpServiceWithRoutes`, and a future client/proxy adapter)
  should delegate to the same engine.
- Keep `HttpJsonTranscodingService` as a `GrpcService` to preserve `routes()` and existing behaviors;
  introduce a separate standalone service that uses the same engine.

## Next Steps (if proceeding)
1) Rewire internals to use a shared transcoding engine + adapter without changing public APIs or behavior.
2) Adjust type hierarchy:
   - Keep `HttpJsonTranscodingService` as a `GrpcService` that delegates to the engine.
   - Introduce a standalone `HttpJsonToGrpcTranscodingService` that implements `HttpServiceWithRoutes`.
3) Expose the standalone service and its builder as public API after the internal consolidation lands.
4) Expose the standalone service via DocService once the public API is stable.
