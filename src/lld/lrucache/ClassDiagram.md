## Class diagram
```mermaid
classDiagram
    direction LR

    class Cache {
        <<interface>>
        +get(K key) Optional~V~
        +put(K key, V value) void
        +remove(K key) boolean
        +size() int
        +capacity() int
        +stats() CacheStats
    }

    class BoundedCache {
        -int capacity
        -long hits
        -long misses
        -long evictions
        -Map~K, V~ store
        -EvictionPolicy~K~ evictionPolicy
        +BoundedCache(int capacity, Supplier policySupplier)
        +get(K key) Optional~V~  [synchronized]
        +put(K key, V value) void  [synchronized]
        +remove(K key) boolean  [synchronized]
        +size() int  [synchronized]
        +stats() CacheStats  [synchronized]
        +capacity() int
    }

    class EvictionPolicy {
        <<interface>>
        +keyAdded(K key) void
        +keyAccessed(K key) void
        +keyRemoved(K key) void
        +evict() Optional~K~
    }

    class LruPolicy {
        -Map~K, Node~ nodes
        -Node head  (sentinel)
        -Node tail  (sentinel)
        +keyAdded(K key) void
        +keyAccessed(K key) void
        +keyRemoved(K key) void
        +evict() Optional~K~
        -addFront(Node node) void
        -unlink(Node node) void
    }

    class Node {
        <<private static nested>>
        ~K key
        ~Node prev
        ~Node next
    }

    class CacheStats {
        <<record>>
        +long hits
        +long misses
        +long evictions
    }

    class LeastRecentlyUsedCache {
        +main(String[] args)$
        -concurrencyTest()$
    }

    class TestKit {
        <<utility>>
        +check(boolean, String)$
        +runConcurrently(int, List~Callable~)$
    }

    BoundedCache ..|> Cache
    LruPolicy ..|> EvictionPolicy

    BoundedCache *-- "1" EvictionPolicy : owns (created via Supplier)
    BoundedCache ..> CacheStats : creates snapshot
    LruPolicy *-- "0..*" Node : nodes + 2 sentinels
    Node --> Node : prev / next

    LeastRecentlyUsedCache ..> BoundedCache : creates
    LeastRecentlyUsedCache ..> LruPolicy : via Supplier
    LeastRecentlyUsedCache ..> TestKit : uses
```

### Legend
| Arrow | Meaning | Here |
|---|---|---|
| `..\|>` | implements an interface | `BoundedCache` implements `Cache`; `LruPolicy` implements `EvictionPolicy` |
| `*--` | composition: the owner creates the part and controls its lifetime | the cache creates its own policy from the `Supplier`; `LruPolicy` creates its nodes |
| `-->` | association: holds a reference | `Node.prev` and `Node.next` |
| `..>` | dependency: uses it but doesn't keep it | the cache creates a `CacheStats` snapshot; `main` uses `TestKit` |

The diagram doesn't show the type parameters. `Cache` and `BoundedCache` are generic in `<K, V>`. `EvictionPolicy`, `LruPolicy` and `Node` only need `<K>`, because eviction never looks at values.

### Points to make when you explain it
- **Two responsibilities, two classes:** `BoundedCache` stores values and enforces capacity; the policy decides **who gets evicted**. LFU or FIFO means one new class implementing `EvictionPolicy`.
- **The `Supplier` makes the policy owned by the cache.** No policy can be shared between caches by accident.
- **One lock lives in the cache and guards two structures:** the cache's map and the policy's map plus list. That's why the policy needs no thread safety of its own.

---

**Next:**
1. Apply the same `synchronized` change plus its 3 tests to **Parking Lot** (~10 min) and close it.
2. Then **#7 Splitwise**, the start of the intermediate problems.

Want the Splitwise problem statement?