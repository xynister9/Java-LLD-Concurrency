package ratelimiter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

// 1. Strategy: limit state for ONE client. Each impl guards its own fields -> one lock per client.
interface RateLimitAlgorithm {
    boolean tryAcquire(long nowNanos);
}

// 2. Config = factory. New algorithm = new record + new class; nothing existing is edited.
//    Deliberately NOT sealed: a `permits` list would have to be edited -> breaks the OCP requirement.
interface LimiterConfig {
    RateLimitAlgorithm create();
}

record TokenBucketConfig(long capacity, double refillPerSecond) implements LimiterConfig {
    TokenBucketConfig {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (!(refillPerSecond > 0) || Double.isInfinite(refillPerSecond))   // !(x > 0) also rejects NaN
            throw new IllegalArgumentException("refillPerSecond must be a positive finite number");
    }
    @Override public RateLimitAlgorithm create() { return new TokenBucket(capacity, refillPerSecond); }
}

record FixedWindowConfig(long limit, Duration windowSize) implements LimiterConfig {
    FixedWindowConfig {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
        Objects.requireNonNull(windowSize, "windowSize");
        if (windowSize.isNegative() || windowSize.isZero())
            throw new IllegalArgumentException("windowSize must be > 0");
    }
    @Override public RateLimitAlgorithm create() { return new FixedWindow(limit, windowSize.toNanos()); }
}

// 3. Algorithms
final class TokenBucket implements RateLimitAlgorithm {
    private final long capacity;
    private final double refillPerNano;
    // guarded by `this`
    private double tokens;
    private long lastRefillNanos;
    private boolean started;

    TokenBucket(long capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.tokens = capacity;                       // starts full
    }

    @Override
    public synchronized boolean tryAcquire(long nowNanos) {
        if (!started) {
            started = true;
            lastRefillNanos = nowNanos;
        } else if (nowNanos > lastRefillNanos) {      // threads may arrive out of time order
            tokens = Math.min(capacity, tokens + (nowNanos - lastRefillNanos) * refillPerNano);
            lastRefillNanos = nowNanos;
        }
        if (tokens >= 1) { tokens -= 1; return true; }   // read-decide-write, atomic under lock
        return false;
    }
}

final class FixedWindow implements RateLimitAlgorithm {
    private final long limit;
    private final long windowNanos;
    // guarded by `this`
    private long currentWindowStart = Long.MIN_VALUE;
    private long count;

    FixedWindow(long limit, long windowNanos) { this.limit = limit; this.windowNanos = windowNanos; }

    @Override
    public synchronized boolean tryAcquire(long nowNanos) {
        long windowStart = nowNanos - Math.floorMod(nowNanos, windowNanos);
        if (windowStart > currentWindowStart) {   // only move FORWARD; a late thread with an older
            currentWindowStart = windowStart;     // timestamp must not reset the count
            count = 0;
        }
        if (count < limit) { count++; return true; }
        return false;
    }
}

// 4. Entry point
final class RateLimiterEngine {
    private final LimiterConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<String, RateLimitAlgorithm> perClient = new ConcurrentHashMap<>();

    public RateLimiterEngine(LimiterConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean tryAcquire(String clientId) {
        if (clientId == null || clientId.isBlank())
            throw new IllegalArgumentException("clientId must be non-blank");
        // Atomic: two simultaneous first requests get the SAME bucket.
        // containsKey + put would be check-then-act -> two buckets -> double the limit.
        RateLimitAlgorithm state = perClient.computeIfAbsent(clientId, id -> config.create());
        return state.tryAcquire(nowNanos());
    }

    private long nowNanos() {
        Instant t = clock.instant();
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000_000L), t.getNano());
    }
}

public class RateLimiter {
    public static void main(String[] args) {
        System.out.println("----Rate Limiter----");
    }
}
