# Rate Limiter v0: class diagram

```mermaid
classDiagram
    direction LR

    %% ---------- Configuration = factory ----------
    class LimiterConfig {
        <<interface, deliberately not sealed>>
        +create() RateLimitAlgorithm
    }
    class TokenBucketConfig {
        <<record>>
        +long capacity
        +double refillPerSecond
        +create() RateLimitAlgorithm
    }
    class FixedWindowConfig {
        <<record>>
        +long limit
        +Duration windowSize
        +create() RateLimitAlgorithm
    }

    %% ---------- Per-client algorithm (Strategy) ----------
    class RateLimitAlgorithm {
        <<interface>>
        +tryAcquire(long nowNanos) boolean
    }
    class TokenBucket {
        <<final>>
        -long capacity
        -double refillPerNano
        -double tokens  (guarded by this)
        -long lastRefillNanos  (guarded by this)
        -boolean started  (guarded by this)
        +tryAcquire(long nowNanos) boolean  [synchronized]
    }
    class FixedWindow {
        <<final>>
        -long limit
        -long windowNanos
        -long currentWindowStart  (guarded by this)
        -long count  (guarded by this)
        +tryAcquire(long nowNanos) boolean  [synchronized]
    }

    %% ---------- Entry point ----------
    class RateLimiterEngine {
        <<final>>
        -LimiterConfig config
        -Clock clock
        -ConcurrentHashMap~String, RateLimitAlgorithm~ perClient
        +RateLimiterEngine(LimiterConfig config, Clock clock)
        ~tryAcquire(String clientId) boolean
        -nowNanos() long
    }
    class Clock {
        <<java.time, injected>>
        +instant() Instant
    }

    %% ---------- Relationships ----------
    TokenBucketConfig ..|> LimiterConfig
    FixedWindowConfig ..|> LimiterConfig
    TokenBucket ..|> RateLimitAlgorithm
    FixedWindow ..|> RateLimitAlgorithm

    TokenBucketConfig ..> TokenBucket : creates
    FixedWindowConfig ..> FixedWindow : creates

    RateLimiterEngine o-- "1" LimiterConfig : injected
    RateLimiterEngine o-- "1" Clock : injected
    RateLimiterEngine *-- "0..*" RateLimitAlgorithm : one per clientId
```

After Fix A, `TokenBucket`'s fields become `long capacityScaled`, `long ratePerSecond`, `long fullRefillNanos` and `long scaledTokens`. The diagram's shape doesn't change.

## What happens on one `tryAcquire`
```mermaid
sequenceDiagram
    participant T as Request thread
    participant E as RateLimiterEngine
    participant M as perClient (ConcurrentHashMap)
    participant A as Client's algorithm (e.g. TokenBucket)

    T->>E: tryAcquire("client-42")
    E->>E: validate clientId
    E->>M: computeIfAbsent("client-42", id -> config.create())
    Note over M: atomic: two simultaneous first<br/>requests get the SAME object
    M-->>E: the client's algorithm object
    E->>E: nowNanos() from the injected Clock
    E->>A: tryAcquire(now)  [lock on this client's object only]
    A->>A: refill / switch window (time only moves forward)
    A->>A: if a token or room is left: use it, return true
    A-->>E: true / false
    E-->>T: allow (true) or reject with 429 (false)
```

## Where the locks are
```mermaid
flowchart LR
    T1["Thread 1: client A"] --> A["TokenBucket A (lock A)"]
    T2["Thread 2: client A"] --> A
    T3["Thread 3: client B"] --> B["TokenBucket B (lock B)"]
    T4["Thread 4: client C"] --> C["TokenBucket C (lock C)"]
```
Threads 1 and 2 take turns on lock A. Threads 3 and 4 run **in parallel** with them, because each client has its own lock and there's no global one.

## Legend
| Arrow | Meaning | Here |
|---|---|---|
| `..\|>` | implements an interface | each config implements `LimiterConfig`; each algorithm implements `RateLimitAlgorithm` |
| `*--` | composition: the owner creates the part and controls its lifetime | the engine creates one algorithm object per client in `perClient` |
| `o--` | aggregation: the part is passed in | the config and the `Clock` are given to the engine |
| `..>` | dependency: uses it but doesn't keep it | each config creates its matching algorithm |

## Points to make when you explain it
| Point | Why it matters |
|---|---|
| **Strategy:** `RateLimitAlgorithm` | Token bucket and fixed window are interchangeable. A sliding-window version would just be one more class. |
| **Each config is also the factory:** `LimiterConfig.create()` | A new algorithm is one new record plus one new class, with no switch to edit. It's **deliberately not `sealed`**, because a `permits` list would have to be edited each time. |
| **One lock per client** | `synchronized` on each client's own object. Clients never block each other. A global lock would make every request in the service wait in one queue. |
| **`computeIfAbsent`** | Creates each client's object atomically. `containsKey` followed by `put` would let two simultaneous first requests create two buckets, doubling that client's limit. |
| **Time only moves forward** | A thread that read the clock earlier but got the lock later can't double-count a refill or reset a window. |
| **Time comes from an injected `Clock`** | Tests can control time exactly. In production, use a source that never goes backwards, such as `System.nanoTime()`. |