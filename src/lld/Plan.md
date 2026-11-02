Each problem adds one new skill on top of the previous one, in this order: OOP → design patterns → state machines → data structures → concurrency → combining everything → performance.

### 🟢 Easy: OOP and core patterns

| # | Problem (≈ also covers) | What you practise | Senior follow-up (build it) |
|---|---|---|---|
| 1 | **Tic-Tac-Toe** (≈ Snake & Ladder, Connect-4) | Splitting into classes (Game/Board/Player/Move), single responsibility, encapsulation; O(1) win check using row/column/diagonal counters | N×N board, undo, and a Bot player (Strategy) added without editing `Game` |
| 2 | **Parking Lot** (≈ Amazon Locker, library management) | Modelling entities (Lot→Floor→Spot, Vehicle, Ticket), composition vs inheritance, Strategy (spot allocation, pricing), Factory | Two entry gates at the same time must never get the same spot; add EV spots or new pricing by *adding* classes only |
| 3 | **Vending Machine** (≈ ATM, coffee machine, traffic signal) | State pattern, rejecting invalid transitions, inventory | Cancel/refund/maintenance states with no if-else chains; giving change from limited coins (greedy can fail → DP) |
| 4 | **LRU Cache** (≈ LFU, in-memory key-value store) | HashMap + doubly linked list (O(1) get/put), generics, pluggable `EvictionPolicy` | Thread safety: `ReadWriteLock` doesn't help because `get` changes the recency order → one big lock vs lock striping; TTL expiry |
| 5 | **Logging Framework** (≈ basic notification service) | Chain of Responsibility (log levels), multiple outputs, Formatter Strategy, Singleton vs dependency injection, Builder for config | Async logging: bounded `BlockingQueue` + writer thread; what to do when the queue is full (block or drop); flush on shutdown |

### 🟡 Intermediate: systems with many entities, algorithms inside the design, first real concurrency

| # | Problem (≈ also covers) | What you practise | Senior follow-up (build it) |
|---|---|---|---|
| 6 | **Chess** (≈ rule-heavy games, text-editor undo/redo) | Polymorphism (each Piece owns its move rules), move validation, check/checkmate | Undo/redo with Command + Memento; castling, en passant and promotion without breaking the Piece hierarchy |
| 7 | **Splitwise** (≈ expense tracker, wallet ledger) | Split Strategy (Equal/Exact/Percent/Share) + validation, balance ledger, service layer, parsing CLI commands | Simplify debts: net balances + greedy with two heaps (at most n−1 transfers; the true minimum is NP-hard, so say that out loud); store money as `long` minor units (paise/cents) and handle rounding remainders |
| 8 | **Elevator System** (≈ event-driven simulations) | State (Idle/Up/Down/Maintenance), SCAN/LOOK scheduling, dispatcher Strategy, controller as Mediator | N elevators, each a thread working through its own request queue; switch dispatch policy (nearest suitable ↔ least loaded) at runtime |
| 9 | **Rate Limiter** (≈ API quotas, throttling, circuit breaker) | Token/Leaky Bucket and Fixed/Sliding Window behind one interface (Strategy + Factory), per-client config | Thread-safe without one global lock (`ConcurrentHashMap.compute`, per-key locks, atomics); inject a `Clock` so tests are deterministic |
| 10 | **BookMyShow** (≈ hotel, airline, meeting-room, car-rental booking) | Deep entity graph (City→Theatre→Screen→Show→ShowSeat), booking state machine, Payment interface | Two users grab the same seat: seat hold with a TTL, pessimistic vs optimistic (version-number) locking, automatic release when payment times out |

### 🔴 Pro: infrastructure-level concurrency and full systems

| # | Problem (≈ also covers) | What you practise | Senior follow-up (build it) |
|---|---|---|---|
| 11 | **Pub-Sub / Kafka-lite** (≈ event bus, live score updates) | Topics split into partitions, consumer groups with their own offsets, producer–consumer on thread pools | At-least-once delivery (commit only after processing), retries + dead-letter queue, per-key ordering via `hash(key) % partitions`, back-pressure |
| 12 | **Job Scheduler (cron-lite)** (≈ `ScheduledExecutorService`, Airflow-lite) | One-off, delayed and recurring jobs; min-heap ordered by next run time; worker pool; Command for jobs | Dispatcher wakes up early when a sooner job arrives (`Condition.awaitNanos`/`signal`), cancel, retry with exponential backoff, graceful shutdown; job dependencies via topological sort |
| 13 | **Ride-Sharing (Uber/Ola)** (≈ delivery-partner assignment, bike-share) | Trip state machine, matching Strategy (nearest/ETA/rating), pricing Strategy + surge as a Decorator, Observer for trip events | No driver assigned twice under concurrent requests (compare-and-set on driver status), frequent location updates into a grid/geohash index behind an interface, cancel → re-match |
| 14 | **Food Delivery / E-commerce** (≈ Amazon checkout, coupon engine, inventory management) | Menu as Composite, cart, order state machine, payment gateways via Adapter, coupons via Decorator/Chain of Responsibility, checkout Facade | No overselling (stock reservation with expiry), idempotent checkout/payment (idempotency keys), undo on failure (refund + release stock) |
| 15 | **Stock Exchange Matching Engine** (≈ crypto exchange, auctions) | Order book = `TreeMap` of price levels with a FIFO queue at each level; price-then-time priority; limit and market orders; partial fills | O(1) cancel using an `orderId → node` index, one writer thread per symbol instead of locks (LMAX-style), event log so the book can be replayed exactly |

**What you'll have covered by #15:**
- **Patterns:** Strategy, State, Observer, Factory, Builder, Singleton/dependency injection, Decorator, Command, Memento, Chain of Responsibility, Adapter, Composite, Facade, Mediator.
- **Concurrency and correctness:** locks, compare-and-set, `BlockingQueue`, thread pools, condition variables, optimistic locking, idempotency.

### 7-day plan (~8–9 focused hours a day)

| Day | Work |
|---|---|
| 1 | Morning: SOLID + core patterns refresher. Afternoon: #1, #2, #3 |
| 2 | Morning: concurrency basics (locks, `ReadWriteLock`, `ConcurrentHashMap` atomic operations, compare-and-set, `BlockingQueue`, `ExecutorService`, `Condition`, optimistic vs pessimistic locking, avoiding deadlocks). Afternoon: #4, #5 |
| 3 | #6, #7, #8 |
| 4 | #9, #10, then redo #2 in 90 minutes, including its thread-safety follow-up |
| 5 | #11, #12 |
| 6 | #13, #14 |
| 7 | Morning: #15. Afternoon: 2 mock rounds (90 minutes each, no notes) on random picks from #7–#14, then fix the gaps they expose |

If you fall behind, drop in this order: #6 → #15 → #5 → #1. The other 11 are the ones interviewers ask most.

### How to work each problem
1. **Requirements (10 min):** what it must do, quality needs (speed, concurrency), and what's explicitly out of scope.
2. **Entities (10 min):** nouns become classes, verbs become methods; decide is-a vs has-a.
3. **Interfaces first (10 min):** one interface for each part likely to change (pricing, matching, eviction…).
4. **Code (60 min, hard stop):** models → interfaces → services → in-memory repositories → a `main` that runs. *Steps 1–4 together are one 90-minute machine-coding round.*
5. **Harden (15 min):** input validation, typed exceptions, protect shared data that threads modify.
6. **Senior follow-up (20–40 min):** a new feature should mean new classes, not edits to existing ones (Open/Closed check).
7. **Compare (30 min):** only now read a reference solution; write down 3 differences you'll reuse.

### Senior bar: grade every solution against this
- Every pattern is tied to a specific part that changes. Don't add patterns for show.
- You can name the exact race condition you prevented and explain why your lock covers no more than it needs to.
- Repositories, `Clock`, payment gateway and ID generator are injected, so the code is unit-testable. Singletons are either justified or absent.
- Money is integer minor units or `BigDecimal`; errors are typed exceptions, not prints or booleans.
- You state trade-offs out loud: optimistic vs pessimistic locking, greedy vs optimal, consistency vs throughput.

**Language:** use one language the whole week. Java is the usual choice for LLD because it has the best concurrency tools and the most reference solutions.

**References (after your attempt, never before):** [awesome-low-level-design](https://github.com/ashishps1/awesome-low-level-design), [refactoring.guru](https://refactoring.guru/design-patterns).

When you finish each problem, share the solution (paste it or give the file path) and I'll review it against the senior bar.
