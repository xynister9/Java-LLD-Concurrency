package lrucache;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import testkit.*;

record CacheStats(long hits, long misses, long evictions) {
};

// 1. Eviction Policy
interface EvictionPolicy<K> {
    void keyAdded(K key);

    void keyAccessed(K key); // a get hit, or an update of an existing key

    void keyRemoved(K key);

    Optional<K> evict(); // picks the victim AND forgets it
}

final class LruPolicy<K> implements EvictionPolicy<K> {
    private static final class Node<K> {
        final K key;
        Node<K> prev, next;

        Node(K key) {
            this.key = key;
        }
    }

    private final Map<K, Node<K>> nodes = new HashMap<>();
    private final Node<K> head = new Node<>(null), tail = new Node<>(null); // Sentinal Nodes

    LruPolicy() {
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public void keyAdded(K key) {
        Node<K> n = new Node<>(key);
        nodes.put(key, n);
        addFront(n);
    }

    @Override
    public void keyAccessed(K key) {
        Node<K> n = nodes.get(key);
        unlink(n);
        addFront(n);
    }

    @Override
    public void keyRemoved(K key) {
        Node<K> n = nodes.remove(key);
        if (n != null)
            unlink(n);
    }

    @Override
    public Optional<K> evict() {
        if (tail.prev == head)
            return Optional.empty(); // empty
        Node<K> lru = tail.prev;
        unlink(lru);
        nodes.remove(lru.key);
        return Optional.of(lru.key);
    }

    private void addFront(Node<K> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = node.next = null;
    }
}

// 2. Cache and Bounded Cache
interface Cache<K, V> {
    Optional<V> get(K key);

    void put(K key, V value);

    boolean remove(K key);

    int size();

    int capacity();

    CacheStats stats();
}

class BoundedCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private long hits, misses, evictions;

    private final Map<K,V> store ; 
    private final EvictionPolicy<K> evictionPolicy;

    BoundedCache(int capacity, Supplier<EvictionPolicy<K>> evictionPolicy) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be atleast 1");
        }
        Objects.requireNonNull(evictionPolicy, "evictionPolicy");
        
        store = new HashMap<>();
        this.capacity = capacity;
        this.evictionPolicy = evictionPolicy.get();
    }

    @Override
    public synchronized Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        if (store.containsKey(key)) {
            hits++;
            evictionPolicy.keyAccessed(key);
            return Optional.of(store.get(key));
        }
        else{
            misses++;
            return Optional.empty();
        } 
    }

    @Override
    public synchronized void put(K key, V value) {
        Objects.requireNonNull(key ,"key");
        Objects.requireNonNull(value, "value");

        if (store.containsKey(key)) {
            evictionPolicy.keyAccessed(key);
            store.put(key,value);
        } else {
            if (store.size() == capacity) {
                Optional<K> evictedKey = evictionPolicy.evict();
                if(evictedKey.isPresent()){
                    store.remove(evictedKey.get());
                    evictions++;
                }
                else{
                    throw new IllegalStateException("Policy out of sync");
                }
            }
            store.put(key, value);
            evictionPolicy.keyAdded(key);
        }
    }

    @Override
    public synchronized boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        if (!store.containsKey(key)) {
            return false;
        }
        evictionPolicy.keyRemoved(key);
        store.remove(key);
        return true;
    }

    @Override
    public synchronized int size() {
        return store.size();
    }

    @Override
    public synchronized CacheStats stats() {
        return new CacheStats(hits, misses, evictions);
    }

    @Override
    public int capacity() {
        return capacity;
    }
}

public class LeastRecentlyUsedCache {
    public static void main(String[] args) {
        System.out.println("\n----LRU Cache----\n");

        Supplier<EvictionPolicy<String>> policySuplier = ()->new LruPolicy<String>();
        BoundedCache<String, Integer> lruCache = new BoundedCache<String, Integer>(3, policySuplier);

        lruCache.put("L", 1);
        lruCache.put("R", 2);
        lruCache.put("U", 3);

        System.out.println("Value for L is " + lruCache.get("L"));

        lruCache.put("F", 4);

        System.out.println("Value for R is " + lruCache.get("R"));

        System.out.println("Value for F is " + lruCache.get("F"));
        
        concurrencyTest();

    }

    private static void concurrencyTest(){
        for (int round = 0; round < 50; round++) {
            Supplier<EvictionPolicy<Integer>> policySuplier = ()->new LruPolicy<Integer>();
            BoundedCache<Integer, Integer> cache = new BoundedCache<>(100, policySuplier);
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                long seed = round * 31L + t;
                tasks.add(() -> {
                    Random r = new Random(seed); long gets = 0;
                    for (int i = 0; i < 10_000; i++) {
                        int key = r.nextInt(500), op = r.nextInt(100);
                        if (op < 60) { cache.get(key); gets++; }
                        else if (op < 95) cache.put(key, i);
                        else cache.remove(key);
                    }
                    return gets;
                });
            }
            long totalGets = TestKit.runConcurrently(8, tasks).stream().mapToLong(Long::longValue).sum();

            CacheStats s = cache.stats();
            TestKit.check(cache.size() <= 100, "round " + round + ": size " + cache.size());
            TestKit.check(s.hits() + s.misses() == totalGets, "round " + round + ": lost stat updates");
        }
    }
}
