# Executor Design Notes for Armeria's gRPC Server Call Chain

## Context

gRPC request deserialization currently runs on the **event loop thread** even when `useBlockingTaskExecutor = true`. The deserialization happens in `AbstractServerCall.onRequestMessage()` *before* the task is dispatched to the blocking executor. Heavy protobuf serde blocks the event loop.

Related issues:
- [#6241](https://github.com/line/armeria/issues/6241) — gRPC serde on event loop
- [#4911](https://github.com/line/armeria/issues/4911) — Trustin's `ScheduledExecutor` proposal
- [#6931](https://github.com/line/armeria/pull/6931) — Hun425's PR introducing `CallExecutor`

## 1. Analysis of Existing Solutions

### Armeria's Current Model

Armeria uses Netty's `EventLoop` (single-threaded) for reactive stream signal delivery and relies on `inEventLoop()` throughout for inline execution optimization:

```java
if (eventLoop.inEventLoop()) {
    doWork();           // fast path: inline
} else {
    eventLoop.execute(() -> doWork());  // slow path: dispatch
}
```

This pattern depends on **thread affinity** — once on the event loop thread, `inEventLoop()` remains true for the entire call stack. The blocking executor is a separate `@Nullable Executor` dispatched to via `if (blockingExecutor != null)`.

### grpc-java's SerializingExecutor

grpc-java uses `SerializingExecutor` for `ServerCall.Listener` dispatch — a sequential executor without thread affinity. Key difference from Armeria: it has no `inEventLoop()` equivalent. It always dispatches, which is simpler but gives up the inline execution optimization.

### Reactor's Scheduler/Worker Model

Reactor uses **atomic WIP/queue-drain** for signal serialization, not thread-identity checks. Operators never call an `inEventLoop()` equivalent — correctness comes from atomic state transitions.

| Scheduler | Thread model | Sequential guarantee |
|---|---|---|
| `parallel()` | Fixed pool of `ScheduledThreadPoolExecutor(1)` | Single-threadedness |
| `boundedElastic()` | Dedicated platform thread per worker | Single-threadedness |
| `boundedElastic()` with vthreads | New virtual thread per task | WIP/queue-drain pattern |

The virtual thread implementation (`BoundedElasticThreadPerTaskScheduler` in `src/main/java21/`) uses `SequentialThreadPerTaskExecutor`:
- Each task creates a **new virtual thread** via `factory.newThread(this)`
- Sequential guarantee comes from WIP/drain handoff: `drain()` starts one task and returns; the task calls `drain()` on completion to hand off to the next
- No thread reuse — each task gets a fresh virtual thread
- This is the only known precedent of non-thread-affine sequential execution driving reactive streams

Since Reactor never relied on thread identity for correctness, adopting virtual threads required no fundamental architectural change.

### Armeria vs Reactor

| | Thread affinity required? | Serialization mechanism |
|---|---|---|
| **Reactor** | No (convenient, not required) | Atomic WIP/queue-drain |
| **Armeria** | Yes (currently) | `inEventLoop()` check |

## 2. Potential Problems

### inEventLoop() Misuse

If a non-thread-affine executor is exposed as an `EventExecutor`, users and internal code may call `inEventLoop()` expecting thread-affinity guarantees. We cannot prevent this at the type level since `inEventLoop()` is part of the `EventExecutor` interface.

| Executor | `inEventLoop()` meaning | Stability |
|---|---|---|
| `EventLoop` | "Am I on my dedicated thread?" | Stable — true for entire call stack |
| Non-thread-affine sequential executor | "Am I inside one of my tasks right now?" | Transient — only true during task execution |

Specific risks:
- `assert executor.inEventLoop()` checks scattered throughout the codebase would pass spuriously or fail unexpectedly
- Code that assumes `inEventLoop() == true` means "I'm on the right thread for the lifetime of this method" would be wrong
- Interceptors or decorators written by users could make incorrect assumptions based on the familiar `inEventLoop()` method

### ThreadLocal Contamination

With a non-thread-affine executor, tasks run on arbitrary threads from the backing pool. If a ThreadLocal is set during one task and not cleaned up, it persists on that thread and leaks into the next unrelated task that happens to run on the same thread.

The `try (SafeCloseable ignored = ctx.push())` pattern is safe — it pushes at entry and pops at exit. However, moving to non-thread-affine executors may expose latent problems where ThreadLocals are set without proper cleanup (e.g., setting a ThreadLocal directly without a corresponding remove), issues that are invisible today because event loop tasks always run on the same dedicated thread.

### Loss of inEventLoop() Optimization

`inEventLoop()` enables zero-cost inline execution when already on the correct thread. If we move away from thread-affine executors, every call becomes a dispatch (queue + context switch overhead). Other frameworks deal with this differently:
- grpc-java: always dispatch, never inline. Not necessarily less performant since `SerializingExecutor` greedily drains the queue inline when already executing.
- Reactor: avoids dispatch cost via WIP/queue-drain — a fundamentally different serialization model.

## 3. Proposal

### Trustin's ScheduledExecutor Proposal (#4911)

A unified executor abstraction replacing both event loop and blocking executor. Motivated by virtual threads — if blocking is cheap, the two-executor split becomes unnecessary.

### Why Sequential Execution?

Existing Armeria code heavily relies on single-threadedness for dealing with concurrency. Introducing a sequential executor helps migrate to non-event-loop executors more easily — code that currently assumes "I'm on the event loop, so no concurrent access" continues to work correctly under a sequential executor without requiring synchronization changes.

Additionally, a sequential executor provides a uniform dispatch target — internal code can simply call `executor.execute(task)` without worrying about whether the executor is an event loop, a blocking pool, or a virtual thread pool. The ordering guarantee is handled by the wrapper, and framework internals (e.g., gRPC's `ServerCall.Listener`) that require sequential callbacks get this for free.

### Design

#### SequentialExecutor Interface

A new `SequentialExecutor` interface with methods similar to Netty's `EventExecutor`:

- `maybeInEventLoop()` instead of `inEventLoop()` — returns false by default for non-event-loop-based executors, true on the dedicated thread for event loops. Provides backwards compatibility as an optimization hint without the misuse risk of a strong `inEventLoop()` guarantee.
- `execute()`, `schedule()`, and other standard executor methods
- For non-event-loop executors, `schedule(task, delay, unit)` can delegate timing to the channel event loop: `ctx.eventLoop().schedule(() -> executor.execute(task), delay, unit)`. No need for a separate shared scheduler since Armeria always has an event loop per request.

#### Type Hierarchy

```
SequentialExecutor                 (new interface, maybeInEventLoop())
├── EventLoopSequentialExecutor    (wraps EventLoop, maybeInEventLoop() delegates to inEventLoop())
└── SequentialExecutorImpl   (wraps arbitrary Executor with sequential guarantee, maybeInEventLoop() returns false)
```

Both `EventLoop`-based and plain `Executor`-based implementations extend the same interface, so internal code can work with `SequentialExecutor` uniformly.

#### Access via RequestContext

Exposed as `ctx.sequentialExecutor()` on `RequestContext`. This returns the appropriate `SequentialExecutor` for the current request:
- If `useBlockingTaskExecutor = true`: a `SequentialExecutorImpl` wrapping `ctx.blockingTaskExecutor()`
- Otherwise: an `EventLoopSequentialExecutor` wrapping `ctx.eventLoop()`

#### Activation Scope

Start with activation at:
- `HttpService#serve` — for general HTTP services
- Internal services (e.g., gRPC's `FramedGrpcService`) — for protocol-specific dispatch

This keeps the change incremental — existing decorators and middleware continue to use `ctx.eventLoop()` until migrated.

Users are free to use `ctx.blockingTaskExecutor()` directly or `ctx.sequentialExecutor()` depending on their needs — the sequential executor is the recommended default for framework-managed dispatch, but direct access to the blocking executor remains available for cases where concurrent execution is desired.

### Tradeoffs

Pros:
- Uniform executor abstraction — eliminates `if (blockingExecutor != null)` branching throughout internal code
- Ordering guarantees are encapsulated in the executor, not scattered across call sites
- Simplifies reasoning about concurrency for framework internals

Cons:
- Sequential wrapping a blocking executor serializes tasks that could otherwise run concurrently — potential throughput loss for independent requests

### Sequential Executor Overhead

Wrapping a blocking executor with a sequential executor (e.g., Guava's `SequentialExecutor`) adds ordering overhead compared to dispatching directly to the blocking executor. With a pure blocking executor, tasks can run concurrently on multiple threads in the pool. With a sequential wrapper, tasks are serialized — only one runs at a time, and the rest queue up waiting.

This may negate the throughput benefits of a thread pool for workloads where concurrent execution is safe (e.g., independent requests). The sequential wrapper is necessary for correctness when tasks share mutable state, but it introduces a bottleneck when they don't.

## 4. Misc

### Adopting WIP/Queue-Drain for StreamMessage

To fully support non-thread-affine executors for reactive streams, Armeria would need to move from `inEventLoop()` checks to atomic WIP/queue-drain serialization (like Reactor). This is a deep architectural change that would affect `StreamMessage` and all operators.

Pros:
- Simplifies internal code — users and internals no longer need to worry about synchronization between the channel event loop and blocking executor
- Enables decoupling stream signal delivery from a specific thread, opening the door for virtual thread support

Cons:
- There is little practical need today for arbitrary executors driving reactive streams
- The virtual thread landscape is still evolving and may change significantly
- Deep architectural change touching `StreamMessage` and all operators — high effort, high risk

### IO Layer

As a general direction, networking can continue to use channel event loops. The new executor abstraction would be for user-facing code (service handlers, gRPC serde, interceptors, etc.).

### Virtual Thread Carrier Thread Segregation

When using virtual threads, the carrier thread pool must be separate from the Netty event loop threads ([netty/netty#13204](https://github.com/netty/netty/issues/13204)). If an event loop thread doubles as a carrier, a virtual thread can acquire a lock (e.g., Netty's buffer allocator), park, and yield the carrier. If the event loop then tries to acquire the same lock, it blocks — and if the parked virtual thread needs IO on that event loop to resume, you get a deadlock.

With segregated pools this is avoided: the virtual thread can resume on a different carrier and release the lock. The carrier thread pool backing virtual threads should be separate from Netty's event loop threads.
