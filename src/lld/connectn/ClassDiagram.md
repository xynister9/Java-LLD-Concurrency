# Connect-N final solution (v6): class diagram

```mermaid
classDiagram
    direction LR

    %% ---------- Players and move choice ----------
    class Player {
        <<record>>
        +String name
        +String character
    }

    class MoveStrategy {
        <<interface>>
        +chooseColumn(BoardView board, Player me) int
    }
    class HumanMoveStrategy {
        -Scanner in
        +chooseColumn(BoardView board, Player me) int
    }
    class RandomBotStrategy {
        -Random random
        +chooseColumn(BoardView board, Player me) int
    }

    %% ---------- Board ----------
    class BoardView {
        <<interface>>
        +getRows() int
        +getCols() int
        +getCell(int r, int c) String
        +isColumnFull(int col) boolean
    }
    class Board {
        -int rows
        -int cols
        -List~List~String~~ board
        +reset() void
        +playColumn(int col, String ch) int
        +checkFull() boolean
        +printBoard() void
    }

    %% ---------- Win rule ----------
    class WinningStrategy {
        <<interface>>
        +hasWon(BoardView board, int r, int c, Player p) boolean
        +isCompatible(BoardView board) boolean
    }
    class KLineWinningStrategy {
        -int k
        -checkAroundCell(...) boolean
        -checkLine(...) boolean
    }

    %% ---------- Engine and runner ----------
    class GameStatus {
        <<enum>>
        IN_PROGRESS
        WON
        DRAW
    }
    class GameEngine {
        -Board board
        -List~Player~ players
        -int currentPlayerIndex
        -WinningStrategy winningStrategy
        -GameStatus gameStatus
        +playTurn(int col) GameStatus
        +getStatus() GameStatus
        +getBoardView() BoardView
        +getCurrentPlayer() Player
        +getWinnerPlayer() Optional~Player~
        +restart() void
    }
    class GameRunner {
        -GameEngine engine
        -Map controllers  (Player → MoveStrategy)
        +run() GameStatus
    }
    class ConnectNGame {
        +main(String[] args)$
    }

    %% ---------- Relationships ----------
    HumanMoveStrategy ..|> MoveStrategy
    RandomBotStrategy ..|> MoveStrategy
    Board ..|> BoardView
    KLineWinningStrategy ..|> WinningStrategy

    GameEngine *-- "1" Board : owns
    GameEngine o-- "2..*" Player : players
    GameEngine o-- "1" WinningStrategy : injected
    GameEngine --> GameStatus

    GameRunner o-- "1" GameEngine
    GameRunner o-- "1..*" MoveStrategy : per player

    MoveStrategy ..> BoardView : reads
    WinningStrategy ..> BoardView : reads
    ConnectNGame ..> GameRunner : creates
    ConnectNGame ..> GameEngine : creates
```

### Exception hierarchy
```mermaid
classDiagram
    direction TB
    RuntimeException <|-- InvalidMoveException
    InvalidMoveException <|-- InvalidColumnException
    InvalidMoveException <|-- ColumnFullException
    InvalidMoveException <|-- GameOverException

    Board ..> InvalidColumnException : throws
    Board ..> ColumnFullException : throws
    GameEngine ..> GameOverException : throws
    GameRunner ..> InvalidMoveException : catches
```

### Legend
| Arrow | Meaning | In this design |
|---|---|---|
| `..\|>` | implements an interface | `Board` implements `BoardView`, each bot implements `MoveStrategy` |
| `<\|--` | extends a class | the exception classes |
| `*--` | composition: the owner creates the part and controls its lifetime | `GameEngine` creates `Board` in its constructor |
| `o--` | aggregation: the part is passed in from outside | `Player`s, `WinningStrategy`, and the engine passed to `GameRunner` |
| `-->` | association: holds a reference | `GameEngine` holds a `GameStatus` |
| `..>` | dependency: uses it but doesn't keep it | strategies read `BoardView`; classes throw or catch exceptions |

### Points to make when you explain this diagram in an interview
- **There are two Strategy points, each tied to something that can change.** `WinningStrategy` lets you swap the rule for winning. `MoveStrategy` lets you swap how a player chooses a move (human or bot).
- **Interface segregation.** Bots and win rules depend on `BoardView`, a read-only interface. Only `GameEngine` holds the full `Board`, so only the engine can change it.
- **Composition vs aggregation.** The engine *creates* its `Board`, so it owns the board's lifetime. Players and the win rule are *passed in*, so callers can swap them and tests can control them.
- **Open/closed.** `GameRunner` and the `MoveStrategy` implementations were added without touching `GameEngine.playTurn`.
