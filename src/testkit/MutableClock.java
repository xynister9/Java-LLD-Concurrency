package testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A Clock that tests move forward by hand. Thread-safe, so it also works in concurrency tests. */
public final class MutableClock extends Clock {
    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public synchronized void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public Instant instant() { return now; }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return this; }
}