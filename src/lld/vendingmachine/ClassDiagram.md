# Vending Machine final solution (v3): class diagram

```mermaid
classDiagram
    direction LR

    %% ---------- Value types ----------
    class Denomination {
        <<enum>>
        ONE(1)
        TWO(2)
        FIVE(5)
        TEN(10)
        TWENTY(20)
        FIFTY(50)
        HUNDRED(100)
        -int value
        +getValue() int
    }
    class Product {
        <<record>>
        +String name
        +int price
    }
    class PurchaseResult {
        <<record>>
        +Product product
        +Map change  (Denomination → count)
    }

    %% ---------- Inventory and money ----------
    class Slot {
        -String slotId
        -Product product
        -int quantity
        +getSlotId() String
        +getProduct() Product
        +getRemainingQuantity() int
        ~refill(int count) void
        ~replace(Product p, int qty) void
        ~releaseItem() Product
    }
    class CashBox {
        -Map coins  (Denomination → count, HashMap)
        ~deposit(Denomination d) void
        ~deposit(Collection money) void
        ~deposit(Map money) void
        ~withdraw(Map money) void
        ~collectAll() Map
        ~snapshot() Map
        ~total() int
        ~makeChange(int amount, Map extra) Optional~Map~
        -solve(...)$ boolean
        ~immutableCopy(Map src)$ Map
    }

    %% ---------- State pattern ----------
    class VmState {
        <<interface>>
        +insertMoney(VendingMachine vm, List money) void
        +selectProduct(VendingMachine vm, String slotId) PurchaseResult
        +cancelTransaction(VendingMachine vm) Map
        +restock(VendingMachine vm, String slotId, int qty) void
        +changeProduct(VendingMachine vm, String slotId, Product p, int qty) void
        +addChange(VendingMachine vm, List money) void
        +collectCash(VendingMachine vm) Map
    }
    class IdleState
    class TransactionState
    class VmStates {
        <<utility>>
        +IDLE$ VmState
        +IN_TRANSACTION$ VmState
    }

    %% ---------- Context ----------
    class VendingMachine {
        -Map slots  (slotId → Slot)
        -CashBox cashBox
        -CashBox escrow
        -VmState state
        +insertMoney(List money) void
        +selectProduct(String slotId) PurchaseResult
        +cancelTransaction() Map
        +balanceAmount() int
        +restock(String slotId, int qty) void
        +changeProduct(String slotId, Product p, int qty) void
        +addChange(List money) void
        +collectCash() Map
        ~setState(VmState s) void
        ~cashBox() CashBox
        ~escrow() CashBox
        ~slot(String slotId) Slot
        -validMoney(List money)$ List
    }
    class VMachine {
        +main(String[] args)$
    }

    %% ---------- Relationships ----------
    IdleState ..|> VmState
    TransactionState ..|> VmState
    VmStates --> IdleState : one shared instance
    VmStates --> TransactionState : one shared instance

    VendingMachine --> "1" VmState : current state
    VendingMachine *-- "2" CashBox : cashBox + escrow
    VendingMachine o-- "1..*" Slot : passed in
    Slot --> "1" Product
    PurchaseResult --> "1" Product

    VmState ..> VendingMachine : receives as parameter
    TransactionState ..> PurchaseResult : creates
    CashBox ..> Denomination : counts
    VMachine ..> VendingMachine : creates and tests
```

### State transitions
```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> InTransaction : insertMoney
    InTransaction --> InTransaction : insertMoney
    InTransaction --> InTransaction : select fails (balance kept)
    InTransaction --> Idle : select succeeds (product + change)
    InTransaction --> Idle : cancel (refund the inserted coins)
    Idle --> Idle : operator actions (restock, changeProduct, addChange, collectCash)
```
Actions not shown on an arrow are invalid in that state and throw `InvalidStateOperationException`. That covers select or cancel while Idle, and any operator action during a transaction.

### Exception hierarchy
```mermaid
classDiagram
    direction TB
    RuntimeException <|-- VendingException
    VendingException <|-- InvalidSlotException
    VendingException <|-- OutOfStockException
    VendingException <|-- InsufficientFundsException
    VendingException <|-- CannotMakeChangeException
    VendingException <|-- InvalidStateOperationException

    class InsufficientFundsException {
        -int shortfall
        +getShortfall() int
    }

    VendingMachine ..> InvalidSlotException : slot()
    Slot ..> OutOfStockException : releaseItem()
    TransactionState ..> InsufficientFundsException : throws
    TransactionState ..> CannotMakeChangeException : throws
    IdleState ..> InvalidStateOperationException : throws
    TransactionState ..> InvalidStateOperationException : throws
```

### Legend
| Mark | Meaning |
|---|---|
| `+` / `~` / `-` | public / package-private / private |
| `$` | static |
| `..\|>` | implements an interface |
| `*--` | composition: the owner creates the part and controls its lifetime (the machine creates both `CashBox`es) |
| `o--` | aggregation: the part is passed in from outside (slots are given to the constructor) |
| `-->` | association: holds a reference |
| `..>` | dependency: uses it without keeping it |

### Points to make when you explain this diagram in an interview
- **State pattern.** `VendingMachine` holds the current state and forwards every public call to it. The states hold no data and get the machine as a parameter, so `VmStates` creates each one once and reuses it.
- **One `CashBox` class, used twice.** One instance is the machine's own money, the other holds the coins the current customer has inserted. Cancelling just empties the customer's box, so a refund can never fail. Change is worked out from both boxes, so the customer's own coins can be given back as change.
- **Access levels.** The public API is the only way in, and it always goes through the current state. The `~` helpers are only for the states.
- **Check everything first, then apply.** `makeChange` only calculates; it doesn't change anything. `selectProduct` runs every check before touching any money or stock, so a failed purchase leaves the machine exactly as it was.

Ready for #2 Parking Lot whenever you are.