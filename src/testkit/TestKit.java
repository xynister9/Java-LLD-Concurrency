package testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/** Tiny test toolkit. Works without JUnit or the -ea flag. Use: import static testkit.TestKit.*; */
public final class TestKit {
    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private TestKit() {}

    /** Like Runnable, but allowed to throw checked exceptions (InterruptedException etc.). */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Like a for-loop body that receives the round number and may throw. */
    @FunctionalInterface
    public interface ThrowingIntConsumer {
        void accept(int round) throws Exception;
    }

    // ---------------------------------------------------------------- running tests

    /** Runs one named test. A failure is recorded and printed, and the remaining tests still run. */
    public static void test(String name, ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("FAIL  " + name);
            System.out.println("      " + t);
        }
    }

    /** Prints totals. Exits with code 1 if anything failed, so scripts and CI notice. */
    public static void summary() {
        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed.");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- assertions

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void checkEquals(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /** Asserts that the action throws the given type (or a subclass) and returns the exception for more checks. */
    public static <T extends Throwable> T expectThrows(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    /** Runs the body N times. A failure message says which round failed. */
    public static void repeat(int rounds, ThrowingIntConsumer body) throws Exception {
        for (int round = 0; round < rounds; round++) {
            try {
                body.accept(round);
            } catch (AssertionError e) {
                throw new AssertionError("round " + round + ": " + e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------- concurrency

    /**
     * Runs all tasks on a pool of {@code threads} threads. A CountDownLatch "starting gun" makes them
     * start together, so races are as likely as possible. Returns results in task order.
     * Any exception from a task fails the test. A task stuck for 30s fails as a likely deadlock or infinite loop.
     */
    public static <T> List<T> runConcurrently(int threads, List<? extends Callable<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();                                          // fire!

            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } catch (ExecutionException e) {
            throw new AssertionError("a concurrent task threw: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("a concurrent task did not finish in 30s (deadlock or infinite loop?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for tasks", e);
        } finally {
            pool.shutdownNow();
        }
    }
}