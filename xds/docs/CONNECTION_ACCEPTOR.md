# ConnectionAcceptor

**Status**: Design

## Motivation

When an xDS snapshot updates (e.g., new RBAC policy, certificate rotation, filter chain
change), the system must decide **when** connections see the new policy. There are two
models:

### Request-time binding (no acceptor)

The decorator or TLS provider reads the current snapshot on every request or TLS
negotiation. This means:

- A snapshot update instantly affects **all** connections, including long-lived ones.
- An HTTP/2 connection carrying multiple streams could have its RBAC policy change
  mid-stream — some requests see the old policy, later requests see the new one.
- There is no clear "point in time" where a connection's policy is decided.

This makes behavior non-deterministic from the connection's perspective. The policy a
request sees depends on the race between the request and the snapshot update.

### Connection-time binding (with acceptor)

A `ConnectionAcceptor` evaluates the current snapshot **once** when the connection is
established, and stores the resolved policy on the channel. All subsequent requests on
that connection use the stored policy. This means:

- A snapshot update only affects **new** connections.
- Existing connections continue with the policy they were accepted with, for their
  entire lifetime.
- There is a clear "point in time" — connection establishment — where the policy is
  decided and locked.

This is deterministic: every request on a connection sees the same policy, regardless
of when snapshot updates happen.

### Why connection-time binding is the right model

1. **Matches Envoy's behavior.** In Envoy, filter chains are matched at connection time.
   The matched chain's TLS config and filters apply for the connection's lifetime. Envoy
   does not re-evaluate filter chain matching on existing connections when the config
   changes.

2. **TLS is inherently connection-scoped.** The TLS handshake happens once per connection.
   Changing the TLS policy mid-connection is not possible without terminating and
   re-establishing the connection. The acceptor makes TLS and decorator policy consistent
   — both are bound at the same point.

3. **Predictable rollout.** When rolling out a new RBAC policy via xDS, operators expect
   existing connections to drain naturally under the old policy while new connections pick
   up the new policy. This avoids breaking in-flight requests.

4. **Simpler reasoning.** Debugging is easier when every request on a connection is
   guaranteed to see the same policy. There are no races between snapshot updates and
   in-flight requests.

## Design

### Interface

```java
@FunctionalInterface
public interface ConnectionAcceptor {
    /**
     * Evaluates whether to accept the connection based on current policy.
     *
     * <p>This method is called once per connection, before TLS negotiation.
     * Implementations should evaluate the current policy snapshot and store
     * the resolved policy on the connection context (via channel attributes)
     * for use by downstream handlers.
     *
     * @param ctx the connection context with SNI hostname, ALPN protocols,
     *            and channel attribute access
     * @return {@code true} to accept the connection, {@code false} to reject
     *         (close immediately)
     */
    boolean accept(ConnectionContext ctx);
}
```

### Pipeline flow

```
bytes arrive
  → ConnectionAcceptHandler parses ClientHello
  → creates ConnectionContext (isTls, sniHostname, alpnProtocols, channel)
  → ConnectionAcceptor.accept(ctx)
      → matches filter chain against current snapshot
      → stores resolved policy (TLS spec + decorator) on channel attrs
      → returns true/false
  → if rejected: close connection
  → ServerTlsProvider.serverTlsSpec(ctx)
      → reads stored TLS spec from channel attr
      → returns it
  → SslHandler negotiation
  → ...
  → HttpServerHandler
      → reads stored decorator from channel attr
      → wraps service with decorator
```

For HTTP (non-TLS) connections, the same flow applies with `isTls=false` and
empty SNI/ALPN. The acceptor can still accept/reject and store a decorator.

### Separation of concerns

| Component            | Responsibility                              | When called          |
|----------------------|---------------------------------------------|----------------------|
| `ConnectionAcceptor` | Accept/reject + bind policy to connection   | Once, at connect     |
| `ServerTlsProvider`  | Resolve TLS config from bound policy        | Once, at connect     |
| Decorator (channel)  | Apply bound policy to requests              | Per request          |

The acceptor is the **only** component that consults the snapshot. The TLS provider and
decorator are pure readers of what the acceptor stored. This ensures the snapshot is
read exactly once per connection, at a well-defined point.

### xDS usage

```java
class XdsConnectionAcceptor implements ConnectionAcceptor {
    private final XdsConnectionConfig connectionConfig;

    @Override
    public boolean accept(ConnectionContext ctx) {
        // Matches filter chain against current xDS snapshot.
        // Stores ResolvedFilterChain (TLS spec + decorator) on channel attr.
        ResolvedFilterChain matched = connectionConfig.matchAndStore(ctx);
        return matched != null;
    }
}

class XdsTlsProvider implements ServerTlsProvider {
    @Override
    public ServerTlsSpec serverTlsSpec(ConnectionContext ctx) {
        // Reads what the acceptor stored — does not consult the snapshot.
        ResolvedFilterChain matched = ctx.channel().attr(MATCHED_CHAIN).get();
        return matched != null ? matched.serverTlsSpec : null;
    }
}
```

### Registration

```java
Server.builder()
    .connectionAcceptor(xdsAcceptor)     // called before TLS
    .tlsProvider(xdsTlsProvider)         // reads from acceptor's stored policy
    .service("/api", myService)
    .build();
```

### Interaction with SniHandler optimization

When a `ConnectionAcceptor` is registered, `ConnectionAcceptHandler` is always used
(full ClientHello parsing) because the acceptor needs the `ConnectionContext`. The
`SniHandler` optimization (skip ClientHello parsing for simple hostname-based TLS)
only applies when no acceptor is registered.

| Acceptor | ServerTlsProvider type            | Pipeline handler           |
|----------|-----------------------------------|----------------------------|
| No       | `StaticTlsProvider`               | `SniHandler` (fast path)   |
| No       | `TlsProviderAdapter`              | `SniHandler` (fast path)   |
| No       | `CompositeServerTlsProvider`      | `ConnectionAcceptHandler`  |
| Yes      | Any                               | `ConnectionAcceptHandler`  |
