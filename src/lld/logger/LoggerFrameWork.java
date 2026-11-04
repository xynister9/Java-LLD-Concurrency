package logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import testkit.TestKit.*;
import testkit.*;

// 1. Enums and Records
enum LogLevel {
    DEBUG(0), INFO(1), WARN(2), ERROR(3), FATAL(4);

    private final int level;

    private LogLevel(int level) {
        this.level = level;
    }

    public boolean smallerThan(LogLevel other) {
        return level < other.level;
    }
}

record LogRecord(Instant timestamp, LogLevel logLevel, String loggerName,
        String threadName, String message, Optional<Throwable> throwable) {
    LogRecord {
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(logLevel);
        Objects.requireNonNull(loggerName);
        Objects.requireNonNull(threadName);
        Objects.requireNonNull(message);
        Objects.requireNonNull(throwable);
        if (loggerName.isBlank()) {
            throw new IllegalArgumentException("loggerName can't be blank");
        }
        if (threadName.isBlank()) {
            throw new IllegalArgumentException("threadName can't be blank");
        }
    }
};

// 2. Associated Intefaces and Implementations.

interface Formatter {
    String format(LogRecord record);
}

class PlainTextFormatter implements Formatter {
    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(record.timestamp())
                .append(" [").append(record.logLevel()).append("]")
                .append(" [").append(record.threadName()).append("]")
                .append(" ").append(record.loggerName())
                .append(" - ").append(record.message());

        record.throwable()
                .ifPresent(t -> sb.append(" ").append(t.getClass().getName()).append(": ").append(t.getMessage()));
        return sb.toString();
    }
}

class JsonFormatter implements Formatter {
    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ts\":\"").append(record.timestamp())
                .append("\",\"level\":\"").append(record.logLevel())
                .append("\",\"thread\":\"").append(esc(record.threadName()))
                .append("\",\"logger\":\"").append(esc(record.loggerName()))
                .append("\",\"msg\":\"").append(esc(record.message())).append("\"");
        record.throwable().ifPresent(t -> sb.append(",\"error\":\"")
                .append(esc(t.getClass().getName() + ": " + t.getMessage())).append("\""));
        sb.append("}");
        return sb.toString();
    }

    /** Escapes characters that would break a JSON string. */
    private static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));   // other control characters
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}

interface Appender {
    void append(LogRecord logRecord);
}

abstract class BaseAppender implements Appender {
    private final LogLevel minimumLevel;
    private final Formatter formatter;

    protected BaseAppender(LogLevel minimumLevel, Formatter formatter) {
        Objects.requireNonNull(minimumLevel);
        Objects.requireNonNull(formatter);
        this.minimumLevel = minimumLevel;
        this.formatter = formatter;
    }

    @Override
    public synchronized final void append(LogRecord record) {
        if (!record.logLevel().smallerThan(minimumLevel)) {
            write(formatter.format(record));
        }
    }

    protected abstract void write(String line); // subclasses only decide WHERE the line goes
}

class ConsoleAppender extends BaseAppender {
    public ConsoleAppender(LogLevel minimumLevel, Formatter formatter) {
        super(minimumLevel, formatter);
    }

    @Override
    protected void write(String line) {
        System.out.println(line);
    }
}

class InMemoryAppender extends BaseAppender {
    private final List<String> lines = new ArrayList<>();

    public InMemoryAppender(LogLevel minimumLevel, Formatter formatter) {
        super(minimumLevel, formatter);
    }

    @Override
    protected void write(String line) {
        lines.add(line);
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }
}

// 3. Logger Classes

final class LoggerConfig {
    final LogLevel level;
    final List<Appender> appenders;
    final Clock clock;

    private LoggerConfig(Builder b) {
        this.level = b.level;
        this.clock = b.clock;
        this.appenders = List.copyOf(b.appenders);
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private LogLevel level = LogLevel.INFO;
        private final List<Appender> appenders = new ArrayList<>();
        private Clock clock = Clock.systemUTC();

        public Builder level(LogLevel level) {
            this.level = level;
            return this;
        }

        public Builder addappender(Appender appender) {
            this.appenders.add(appender);
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public LoggerConfig build() {
            Objects.requireNonNull(level);
            Objects.requireNonNull(clock);
            if (appenders.isEmpty()) {
                throw new IllegalArgumentException("Required atleast one Appender.");
            }
            return new LoggerConfig(this);
        }
    }
}

class Logger {
    private final String name;
    private final LoggerConfig loggerConfig;

    Logger(String name, LoggerConfig loggerConfig) {
        this.name = name;
        this.loggerConfig = loggerConfig;
    }

    public void debug(String msg) {
        log(LogLevel.DEBUG, msg, null);
    }

    public void info(String msg) {
        log(LogLevel.INFO, msg, null);
    }

    public void warn(String msg) {
        log(LogLevel.WARN, msg, null);
    }

    public void error(String msg) {
        log(LogLevel.ERROR, msg, null);
    }

    public void error(String msg, Throwable throwable) {
        log(LogLevel.ERROR, msg, throwable);
    }

    public void fatal(String msg) {
        log(LogLevel.FATAL, msg, null);
    }

    public void debug(Supplier<String> msg) {
        if (LogLevel.DEBUG.smallerThan(loggerConfig.level)) {
            return;
        }
        debug(msg.get());
    }

    public void log(LogLevel level, String msg, Throwable t) {
        if (level.smallerThan(loggerConfig.level)) {
            return;
        }
        LogRecord record = new LogRecord(loggerConfig.clock.instant(), level, name,
                            Thread.currentThread().getName(), String.valueOf(msg), Optional.ofNullable(t));
        for (Appender appender : loggerConfig.appenders) {
            try { 
                appender.append(record); 
            }
            catch (RuntimeException e) { 
                System.err.println("Appender failed: " + e); 
            }
        }
    }
}

class LogManager {
    private final LoggerConfig config;
    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();

    public LogManager(LoggerConfig config) {
        Objects.requireNonNull(config);
        this.config = config;
    }

    public Logger getLogger(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("logger name can't be blank");
        }
        return loggers.computeIfAbsent(name, n -> new Logger(n, config));
    }
}

// public class LoggerFrameWork {
//     public static void main(String[] args) {
//         System.out.println("\n----Logger----\n");
//     }
// }



public class LoggerFrameWork {
    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);   // never advanced, so exact-format tests stay stable
    private static final Formatter PLAIN = new PlainTextFormatter();
    private static final Formatter JSON = new JsonFormatter();

    public static void main(String[] args) {
        System.out.println("\n---- Logger Tests ----\n");

        // ------------------------------------------------------------ levels & filtering
        TestKit.test("Levels are ordered DEBUG < INFO < WARN < ERROR < FATAL", () -> {
            LogLevel[] all = LogLevel.values();
            for (int i = 0; i + 1 < all.length; i++) {
                TestKit.check(all[i].smallerThan(all[i + 1]), all[i] + " should be smaller than " + all[i + 1]);
            }
            TestKit.check(!LogLevel.INFO.smallerThan(LogLevel.INFO), "a level is not smaller than itself");
        });

        TestKit.test("Logger at INFO drops debug before any appender sees it", () -> {
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            Logger log = logger(LogLevel.INFO, mem);
            log.debug("hidden");
            log.info("shown");
            TestKit.checkEquals(1, mem.lines().size(), "line count");
            TestKit.check(mem.lines().get(0).endsWith("- shown"), "got " + mem.lines());
        });

        TestKit.test("Each appender filters by its own level", () -> {
            InMemoryAppender all = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            InMemoryAppender alerts = new InMemoryAppender(LogLevel.ERROR, PLAIN);
            Logger log = logger(LogLevel.DEBUG, all, alerts);
            log.warn("disk 80%");
            log.error("disk full");
            TestKit.checkEquals(2, all.lines().size(), "'all' appender lines");
            TestKit.checkEquals(1, alerts.lines().size(), "'alerts' appender lines");
            TestKit.check(alerts.lines().get(0).contains("[ERROR]"), "alerts got " + alerts.lines());
        });

        TestKit.test("Lazy debug: supplier runs 0 times when DEBUG is off, once when on", () -> {
            AtomicInteger calls = new AtomicInteger();
            Logger off = logger(LogLevel.INFO, new InMemoryAppender(LogLevel.DEBUG, PLAIN));
            off.debug(() -> { calls.incrementAndGet(); return "expensive"; });
            TestKit.checkEquals(0, calls.get(), "supplier calls with DEBUG disabled");

            Logger on = logger(LogLevel.DEBUG, new InMemoryAppender(LogLevel.DEBUG, PLAIN));
            on.debug(() -> { calls.incrementAndGet(); return "expensive"; });
            TestKit.checkEquals(1, calls.get(), "supplier calls with DEBUG enabled");
        });

        // ------------------------------------------------------------ formatting
        TestKit.test("Plain text format is exact", () -> {
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            logger(LogLevel.DEBUG, mem).info("Order placed: 42");
            String thread = Thread.currentThread().getName();
            TestKit.checkEquals("2026-11-04T10:00:00Z [INFO] [" + thread + "] OrderService - Order placed: 42",
                    mem.lines().get(0), "plain line");
        });

        TestKit.test("Plain text appends the throwable's class and message", () -> {
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            logger(LogLevel.DEBUG, mem).error("Payment failed", new IllegalStateException("gateway down"));
            TestKit.check(mem.lines().get(0).endsWith("- Payment failed java.lang.IllegalStateException: gateway down"),
                    "got " + mem.lines().get(0));
        });

        TestKit.test("JSON format is exact", () -> {
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, JSON);
            logger(LogLevel.DEBUG, mem).warn("low stock");
            String thread = Thread.currentThread().getName();
            TestKit.checkEquals("{\"ts\":\"2026-11-04T10:00:00Z\",\"level\":\"WARN\",\"thread\":\"" + thread
                    + "\",\"logger\":\"OrderService\",\"msg\":\"low stock\"}", mem.lines().get(0), "json line");
        });

        TestKit.test("Timestamp comes from the injected clock", () -> {
            MutableClock clock = new MutableClock(T0);
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            Logger log = new LogManager(LoggerConfig.builder()
                    .level(LogLevel.DEBUG).clock(clock).addappender(mem).build()).getLogger("Clocked");
            log.info("first");
            clock.advance(Duration.ofMinutes(5));
            log.info("second");
            TestKit.check(mem.lines().get(0).startsWith("2026-11-04T10:00:00Z"), "first: " + mem.lines().get(0));
            TestKit.check(mem.lines().get(1).startsWith("2026-11-04T10:05:00Z"), "second: " + mem.lines().get(1));
        });

        // ------------------------------------------------------------ robustness
        TestKit.test("A failing appender never reaches the caller; other appenders still get the record", () -> {
            Appender broken = r -> { throw new IllegalStateException("disk full"); };
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            Logger log = logger(LogLevel.DEBUG, broken, mem);      // broken FIRST on purpose
            log.error("still delivered");                          // must not throw (expect 1 line on System.err)
            TestKit.checkEquals(1, mem.lines().size(), "healthy appender lines");
        });

        TestKit.test("Logging a null message never crashes the caller", () -> {
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            Logger log = logger(LogLevel.DEBUG, mem);
            log.info(null);                                        // spec: logging must never crash the app
            TestKit.checkEquals(1, mem.lines().size(), "null message should still be logged, e.g. as \"null\"");
        });

        // ------------------------------------------------------------ config & manager
        TestKit.test("Builder: default level is INFO; zero appenders is rejected", () -> {
            TestKit.expectThrows(IllegalArgumentException.class, () -> LoggerConfig.builder().build());
            LoggerConfig cfg = LoggerConfig.builder()
                    .addappender(new InMemoryAppender(LogLevel.DEBUG, PLAIN)).build();
            TestKit.checkEquals(LogLevel.INFO, cfg.level, "default level");
        });

        TestKit.test("LogManager returns one instance per name", () -> {
            LogManager mgr = new LogManager(config(LogLevel.INFO, new InMemoryAppender(LogLevel.DEBUG, PLAIN)));
            TestKit.check(mgr.getLogger("A") == mgr.getLogger("A"), "same name -> same instance");
            TestKit.check(mgr.getLogger("A") != mgr.getLogger("B"), "different names -> different instances");
        });

        TestKit.test("LogManager: 8 threads asking for the same name at once get ONE instance", () -> TestKit.repeat(50, round -> {
            LogManager mgr = new LogManager(config(LogLevel.INFO, new InMemoryAppender(LogLevel.DEBUG, PLAIN)));
            Callable<Logger> ask = () -> mgr.getLogger("Shared");
            Set<Logger> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            distinct.addAll(TestKit.runConcurrently(8, Collections.nCopies(8, ask)));
            TestKit.checkEquals(1, distinct.size(), "distinct Logger instances");
        }));

        // ------------------------------------------------------------ concurrency
        TestKit.test("8 threads x 1000 messages: none lost, every line intact, per-thread order kept", () -> TestKit.repeat(20, round -> {
            InMemoryAppender mem = new InMemoryAppender(LogLevel.DEBUG, PLAIN);
            Logger log = logger(LogLevel.DEBUG, mem);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                tasks.add(() -> {
                    for (int i = 0; i < 1000; i++) log.info("T" + id + "-" + i);
                    return null;
                });
            }
            TestKit.runConcurrently(8, tasks);

            List<String> lines = mem.lines();
            TestKit.checkEquals(8000, lines.size(), "total lines");
            int[] lastSeq = new int[8];
            Arrays.fill(lastSeq, -1);
            for (String line : lines) {
                int at = line.lastIndexOf(" - T");
                TestKit.check(at >= 0 && line.startsWith("2026-11-04T10:00:00Z [INFO] ["), "mangled line: " + line);
                String[] parts = line.substring(at + 4).split("-");          // "3-17" -> ["3", "17"]
                int id = Integer.parseInt(parts[0]), seq = Integer.parseInt(parts[1]);
                TestKit.check(seq == lastSeq[id] + 1, "thread " + id + " out of order: " + lastSeq[id] + " then " + seq);
                lastSeq[id] = seq;
            }
        }));

        TestKit.summary();
    }

    // ---------------------------------------------------------------- helpers
    private static LoggerConfig config(LogLevel level, Appender... appenders) {
        LoggerConfig.Builder b = LoggerConfig.builder().level(level).clock(CLOCK);
        for (Appender a : appenders) b.addappender(a);
        return b.build();
    }

    private static Logger logger(LogLevel level, Appender... appenders) {
        return new LogManager(config(level, appenders)).getLogger("OrderService");
    }
}
