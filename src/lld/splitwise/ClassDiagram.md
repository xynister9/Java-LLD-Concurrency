# Splitwise solution: class diagram

```mermaid
classDiagram
    direction LR

    %% ---------- Value types ----------
    class User {
        <<record>>
        +String id
        +String name
        +String email
    }
    class Balance {
        <<record>>
        +String from
        +String to
        +long amount  (always > 0)
    }
    class Expense {
        <<record>>
        +String id
        +String paidBy
        +long amount
        +String description
        +Map~String, Long~ shares
    }

    %% ---------- Splits (Strategy) ----------
    class Split {
        <<interface>>
        +computeShares(long amount) Map~String, Long~
    }
    class EqualSplit {
        <<record>>
        +List~String~ participants
        +computeShares(long) Map~String, Long~
    }
    class ExactSplit {
        <<record>>
        +Map~String, Long~ amounts
        +computeShares(long) Map~String, Long~
    }
    class PercentSplit {
        <<record>>
        +Map~String, Integer~ percents
        +computeShares(long) Map~String, Long~
    }
    class ShareSplit {
        <<record>>
        +Map~String, Integer~ weights
        +computeShares(long) Map~String, Long~
    }
    class SplitSupport {
        <<utility>>
        +uniqueIds(List)$ List~String~
        +orderedCopy(Map)$ Map
        +proportional(long, Map)$ Map~String, Long~
    }

    %% ---------- Ledger ----------
    class BalanceSheet {
        -Map~Pair, Long~ net
        ~owes(String debtor, String creditor) long
        ~addDebt(String debtor, String creditor, long amt) void
        ~all() List~Balance~
        ~involving(String userId) List~Balance~
        ~netPositions() Map~String, Long~
        ~clear() void
    }
    class Pair {
        <<private record in BalanceSheet>>
        +String lo
        +String hi
        +of(String a, String b)$ Pair
    }

    %% ---------- Simplifier ----------
    class DebtSimplifier {
        <<utility>>
        -Comparator LARGEST_FIRST$
        +simplify(Map~String, Long~ netPositions)$ List~Balance~
    }
    class Position {
        <<private record in DebtSimplifier>>
        +String user
        +long amount
    }

    %% ---------- Facade ----------
    class SplitwiseService {
        -Map~String, User~ users
        -BalanceSheet sheet
        -List~Expense~ expenses
        -long nextExpenseId
        +registerUser(String id, String name, String email) User
        +getUser(String id) User
        +addExpense(String paidBy, long amount, Split split, String desc) Expense
        +settle(String from, String to, long amount) void
        +balancesOf(String userId) List~Balance~
        +allBalances() List~Balance~
        +netPositions() Map~String, Long~
        +simplifyDebts() List~Balance~
        +applySimplifiedDebts() List~Balance~
        +expenses() List~Expense~
        -requireUser(String id) User
    }

    %% ---------- Relationships ----------
    EqualSplit ..|> Split
    ExactSplit ..|> Split
    PercentSplit ..|> Split
    ShareSplit ..|> Split
    EqualSplit ..> SplitSupport : uniqueIds
    ExactSplit ..> SplitSupport : orderedCopy
    PercentSplit ..> SplitSupport : proportional
    ShareSplit ..> SplitSupport : proportional

    SplitwiseService *-- "0..*" User : registry
    SplitwiseService *-- "1" BalanceSheet : owns
    SplitwiseService *-- "0..*" Expense : history
    SplitwiseService ..> Split : uses (passed per call)
    SplitwiseService ..> DebtSimplifier : simplifyDebts()
    SplitwiseService ..> Balance : returns

    BalanceSheet *-- "0..*" Pair : map keys
    BalanceSheet ..> Balance : creates
    DebtSimplifier *-- Position : heap entries
    DebtSimplifier ..> Balance : creates

    Expense ..> User : paidBy / shares by id
```

## Exception hierarchy
```mermaid
classDiagram
    direction TB
    RuntimeException <|-- SplitwiseException
    SplitwiseException <|-- InvalidSplitException
    SplitwiseException <|-- UserNotFoundException
    SplitwiseException <|-- DuplicateUserException
    SplitwiseException <|-- UserDoesNotOweException
    SplitwiseException <|-- OverpaymentException
```

## What happens in `addExpense` and `settle`
```mermaid
sequenceDiagram
    participant C as Caller
    participant S as SplitwiseService
    participant SP as Split
    participant BS as BalanceSheet

    C->>S: addExpense(paidBy, amount, split, desc)
    S->>S: 1. validate: payer exists, amount > 0, split not null
    S->>SP: 2. computeShares(amount)  (pure, changes nothing)
    SP-->>S: userId -> share
    S->>S: 3. central check: users exist, shares >= 0, sum == amount
    loop 4. commit (only after every check passed)
        S->>BS: addDebt(participant, paidBy, share)
    end
    S-->>C: Expense

    C->>S: settle(from, to, amount)
    S->>BS: owes(from, to)
    BS-->>S: owed
    S->>S: reject if owed <= 0 or amount > owed
    S->>BS: addDebt(to, from, amount)  (repayment = reverse debt)
```

## Legend
| Arrow | Meaning | Here |
|---|---|---|
| `..\|>` | implements an interface | the 4 split records implement `Split` |
| `<\|--` | extends a class | the exception classes |
| `*--` | composition: the owner creates the part and controls its lifetime | the service owns its `BalanceSheet`, the users and the expense history; `BalanceSheet` owns its `Pair` keys |
| `..>` | dependency: uses it but doesn't keep it | the service uses the `Split` passed in and calls `DebtSimplifier`; splits call `SplitSupport` |

## Points to make when you explain the diagram
- **Strategy with the input built in:** each split record holds its own input (participants, amounts, percentages, weights). Adding `ShareSplit` needed **no** registry, no switch statement, and no edits.
- **The sign rule lives in one place:** only `BalanceSheet.owes()` and `addDebt()` know the "positive means lo owes hi" convention. Everything else thinks in terms of debtor and creditor, which removes the whole class of bug from your v0.
- **Two layers of validation:** each split checks *its own* rules (percentages add up to 100, exact amounts match). The service checks the rule *every* split must obey (registered users, shares add up to the amount), so a buggy future split can't corrupt the balances.
- **Pure helpers:** `DebtSimplifier` and `SplitSupport` change nothing, so they're easy to test directly. `simplifyDebts()` only suggests payments; `applySimplifiedDebts()` is the explicit, separate call that rewrites the balances.
- **Ids instead of object references:** `Expense` and `Balance` refer to users by `String` id. That avoids the record-equality trap from v0, where a user with a different email counted as a different user.

Time for a 30-second eye break: look at something at least 20 feet (about 6 m) away.

Splitwise is now done, with its class diagram. Next on the list is #8 Elevator System, a good one for practising the State pattern and scheduling. Say "Elevator" when you're back and I'll send its scoped problem statement.