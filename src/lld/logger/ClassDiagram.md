# Logging Framework v0: class diagram

```mermaid
classDiagram
    direction LR

    %% ---------- Value types ----------
    class LogLevel {
        <<enum>>
        DEBUG
        INFO
        WARN
        ERROR
        FATAL
        -int level
        +smallerThan(LogLevel other) boolean
    }

    class LogRecord {
        <<record>>
        +Instant timestamp
        +LogLevel logLevel
        +String loggerName
        +String threadName
        +String message
        +Optional~Throwable~ throwable
    }

    %% ---------- Formatting (Strategy) ----------
    class Formatter {
        <<interface>>
        +format(LogRecord record) String
    }
    class PlainTextFormatter {
        +format(LogRecord record) String
    }
    class JsonFormatter {
        +format(LogRecord record) String
        -esc(String s)$ String
    }

    %% ---------- Destinations (Template Method) ----------
    class Appender {
        <<interface>>
        +append(LogRecord record) void
    }
    class BaseAppender {
        <<abstract>>
        -LogLevel minimumLevel
        -Formatter formatter
        +append(LogRecord record) void  [final, synchronized]
        #write(String line)* void
    }
    class ConsoleAppender {
        #write(String line) void
    }
    class InMemoryAppender {
        -List~String~ lines
        #write(String line) void
        +lines() List~String~  [synchronized]
    }

    %% ---------- Configuration (Builder) ----------
    class LoggerConfig {
        <<immutable>>
        ~LogLevel level
        ~List~Appender~ appenders
        ~Clock clock
        +builder()$ Builder
    }
    class Builder {
        <<static nested in LoggerConfig>>
        -LogLevel level = INFO
        -List~Appender~ appenders
        -Clock clock = systemUTC
        +setLevel(LogLevel) Builder
        +setAppender(Appender) Builder
        +setClock(Clock) Builder
        +build() LoggerConfig
    }

    %% ---------- Loggers ----------
    class Logger {
        -String name
        -LoggerConfig loggerConfig
        +debug(String msg) void
        +debug(Supplier~String~ msg) void
        +info(String msg) void
        +warn(String msg) void
        +error(String msg) void
        +error(String msg, Throwable t) void
        +fatal(String msg) void
        +log(LogLevel level, String msg, Throwable t) void
    }
    class LogManager {
        -LoggerConfig config
        -ConcurrentHashMap~String, Logger~ loggers
        +getLogger(String name) Logger
    }

    %% ---------- Relationships ----------
    PlainTextFormatter ..|> Formatter
    JsonFormatter ..|> Formatter
    BaseAppender ..|> Appender
    ConsoleAppender --|> BaseAppender
    InMemoryAppender --|> BaseAppender

    BaseAppender o-- "1" Formatter : injected
    BaseAppender --> LogLevel : minimumLevel

    Builder ..> LoggerConfig : builds
    LoggerConfig o-- "1..*" Appender
    LoggerConfig --> LogLevel : level

    LogManager o-- "1" LoggerConfig : shared
    LogManager *-- "0..*" Logger : creates + caches per name
    Logger o-- "1" LoggerConfig : shared, read-only
    Logger ..> LogRecord : creates

    Formatter ..> LogRecord : reads
    Appender ..> LogRecord : receives
    LogRecord --> LogLevel
```

## What happens on one `log.warn(...)` call
```mermaid
sequenceDiagram
    participant App as Application thread
    participant L as Logger
    participant A1 as InMemoryAppender (INFO, plain)
    participant A2 as InMemoryAppender (ERROR, json)

    App->>L: warn("low stock")
    L->>L: WARN smaller than config.level? → no, continue
    L->>L: new LogRecord(clock.instant(), WARN, name, thread, msg)
    L->>A1: append(record)  [inside try/catch]
    A1->>A1: lock A1 → WARN ≥ INFO → format → write(line) → unlock
    L->>A2: append(record)  [inside try/catch]
    A2->>A2: lock A2 → WARN < ERROR → skip → unlock
    L-->>App: returns (never throws)
```

## Legend
| Arrow | Meaning | Here |
|---|---|---|
| `..\|>` | implements an interface | each formatter implements `Formatter`; `BaseAppender` implements `Appender` |
| `--\|>` | extends a class | `ConsoleAppender` and `InMemoryAppender` extend `BaseAppender` |
| `*--` | composition: the owner creates the part and controls its lifetime | `LogManager` creates and caches each `Logger` |
| `o--` | aggregation: the part is passed in or shared | the formatter is given to each appender; one `LoggerConfig` is shared by the manager and every logger |
| `-->` | association: holds a reference | `LogRecord` and `BaseAppender` each hold a `LogLevel` |
| `..>` | dependency: uses it but doesn't keep it | `Logger` creates records; formatters and appenders read them |

`Clock` (injected through the Builder) and the test classes (`TestKit`, `MutableClock`) are left out to keep the diagram readable.

## Patterns to name in the interview
| Pattern | Where | Why it fits |
|---|---|---|
| **Strategy** | `Formatter` | Output formats can be swapped or added without edits |
| **Template Method** | `BaseAppender.append` is `final` and calls the abstract `write` | The level check and formatting happen exactly once and can't be skipped; subclasses only decide where the line goes |
| **Builder** | `LoggerConfig.Builder` | Defaults (level INFO, system clock), checks done once in `build()`, and an immutable result |
| **Factory + cache** | `LogManager.getLogger` with `computeIfAbsent` | One logger per name, safe when many threads ask at once |
| **Decorator** *(Part 2)* | `AsyncAppender` wraps any `Appender` | Adds the queue and background thread without changing the appender it wraps |

## Concurrency points shown in the diagram
- **`Logger` has no lock.** It only reads `final` fields of an immutable `LoggerConfig`.
- **Each appender has its own lock** (`synchronized append`), so a slow console doesn't block the in-memory appender.
- **`LogManager` uses `ConcurrentHashMap.computeIfAbsent`**, so the same name never ends up with two loggers.