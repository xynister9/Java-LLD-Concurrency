# Elevator solution: class diagram

```mermaid
classDiagram
    direction LR

    %% ---------- Value types ----------
    class Direction {
        <<enum>>
        UP
        DOWN
        +opposite() Direction
    }
    class HallCall {
        <<record>>
        +int floor
        +Direction direction
    }
    class Arrival {
        <<record>>
        +String elevatorId
        +int floor
        +Direction direction  (served)
        +int tick
    }

    %% ---------- States (State pattern) ----------
    class ElevatorState {
        <<interface>>
        +step(Elevator e) Optional~Arrival~
    }
    class IdleState
    class MovingUpState
    class MovingDownState
    class DoorsOpenState
    class MaintenanceState
    class States {
        <<constants>>
        +IDLE$
        +MOVING_UP$
        +MOVING_DOWN$
        +DOORS_OPEN$
        +MAINTENANCE$
    }
    class StateSupport {
        <<utility>>
        +movingState(Direction)$ ElevatorState
        +openDoors(Elevator, Direction)$ Optional~Arrival~
        +travel(Elevator, Direction)$ Optional~Arrival~
    }

    %% ---------- Elevator ----------
    class ElevatorView {
        <<interface>>
        +id() String
        +floor() int
        +state() ElevatorState
        +direction() Optional~Direction~
        +upStops() Set~Integer~
        +downStops() Set~Integer~
    }
    class ElevatorSnapshot {
        <<record, immutable>>
        +String id
        +int floor
        +ElevatorState state
        +Direction currentDirection
        +Set~Integer~ upStops
        +Set~Integer~ downStops
        +direction() Optional~Direction~
    }
    class Elevator {
        -String elevatorId
        -int totalFloors
        -int currentFloor
        -ElevatorState currentState
        -Direction currentDirection
        -NavigableSet~Integer~ upStops
        -NavigableSet~Integer~ downStops
        -int tick
        ~step(int tick) Optional~Arrival~
        ~addStop(int floor, Direction dir) void
        ~clearStops() void
        ~setState(ElevatorState) void
        ~inMaintenance() boolean
        ~snapshot() ElevatorSnapshot
        ~setDirection(Direction) void
        ~moveOneFloor(Direction) void
        ~hasStops() boolean
        ~removeStop(int, Direction) void
        ~nearestStop() Optional~Integer~
        ~stopHere(Direction) Optional~Direction~
        ~nextTarget(Direction) Optional~Integer~
    }

    %% ---------- Dispatch (Strategy) ----------
    class DispatchStrategy {
        <<interface>>
        +choose(HallCall call, List elevators) Optional~String~
    }
    class NearestSuitable {
        +choose(HallCall, List) Optional~String~
        ~cost(ElevatorView, HallCall)$ int
    }
    class LeastLoaded {
        +choose(HallCall, List) Optional~String~
    }
    class ElevatorIds {
        <<utility>>
        +BY_NUMBER$ Comparator
    }

    %% ---------- Observer ----------
    class ArrivalListener {
        <<interface>>
        +listen(Arrival arrival) void
    }
    class SysOutArrivalListener

    %% ---------- Controller (Mediator) ----------
    class ElevatorSystem {
        -int floors
        -Map~String, Elevator~ elevators
        -Map~HallCall, String~ pendingHall
        -ArrivalListener arrivalListener
        -DispatchStrategy dispatchStrategy
        -int tick
        +requestHall(int floor, Direction d) void
        +requestCar(String elevatorId, int floor) void
        +step() void
        +tick() int
        +status() List~ElevatorSnapshot~
        +pendingHallCalls() Map~HallCall, String~
        +setDispatchStrategy(DispatchStrategy) void
        +setMaintenance(String elevatorId, boolean enable) void
        -dispatch(HallCall) String
        -elevator(String id) Elevator
        -validateFloor(int) void
    }

    %% ---------- Relationships ----------
    IdleState ..|> ElevatorState
    MovingUpState ..|> ElevatorState
    MovingDownState ..|> ElevatorState
    DoorsOpenState ..|> ElevatorState
    MaintenanceState ..|> ElevatorState
    States *-- "5" ElevatorState : one shared instance each
    MovingUpState ..> StateSupport : travel(UP)
    MovingDownState ..> StateSupport : travel(DOWN)
    IdleState ..> StateSupport
    DoorsOpenState ..> StateSupport

    Elevator ..|> ElevatorView
    ElevatorSnapshot ..|> ElevatorView
    Elevator --> "1" ElevatorState : currentState
    Elevator ..> ElevatorSnapshot : creates
    ElevatorState ..> Elevator : drives via helpers

    NearestSuitable ..|> DispatchStrategy
    LeastLoaded ..|> DispatchStrategy
    NearestSuitable ..> ElevatorIds : tie-break
    LeastLoaded ..> ElevatorIds : tie-break
    DispatchStrategy ..> ElevatorView : reads snapshots only

    SysOutArrivalListener ..|> ArrivalListener

    ElevatorSystem *-- "1..*" Elevator : owns
    ElevatorSystem o-- "1" DispatchStrategy : injected, swappable
    ElevatorSystem o-- "1" ArrivalListener : injected
    ElevatorSystem --> "0..*" HallCall : pendingHall keys
    ElevatorSystem ..> Arrival : forwards to listener
```

## Exception hierarchy
```mermaid
classDiagram
    direction TB
    RuntimeException <|-- ElevatorException
    ElevatorException <|-- FloorOutOfBoundException
    ElevatorException <|-- InvalidHallCallException
    ElevatorException <|-- NoElevatorAvailableException
    ElevatorException <|-- InvalidElevatorIdException
    ElevatorException <|-- ElevatorInMaintenanceException
```

## State transitions (one `step()` = one tick)
```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> IDLE : no stops
    IDLE --> DOORS_OPEN : stop on this floor (arrival)
    IDLE --> MOVING_UP : nearest stop above (moves this tick)
    IDLE --> MOVING_DOWN : nearest stop below (moves this tick)

    MOVING_UP --> MOVING_UP : stop ahead, move 1 floor
    MOVING_UP --> DOORS_OPEN : UP stop here, or turnaround (arrival)
    MOVING_UP --> MOVING_DOWN : nothing ahead, stops behind
    MOVING_UP --> IDLE : no stops

    MOVING_DOWN --> MOVING_DOWN : stop ahead, move 1 floor
    MOVING_DOWN --> DOORS_OPEN : DOWN stop here, or turnaround (arrival)
    MOVING_DOWN --> MOVING_UP : nothing ahead, stops behind
    MOVING_DOWN --> IDLE : no stops

    DOORS_OPEN --> MOVING_UP : next target up
    DOORS_OPEN --> MOVING_DOWN : next target down
    DOORS_OPEN --> IDLE : no stops

    IDLE --> MAINTENANCE : setMaintenance(true)
    MOVING_UP --> MAINTENANCE : setMaintenance(true)
    MOVING_DOWN --> MAINTENANCE : setMaintenance(true)
    DOORS_OPEN --> MAINTENANCE : setMaintenance(true)
    MAINTENANCE --> IDLE : setMaintenance(false)
```

## What happens on `requestHall` and `step`
```mermaid
sequenceDiagram
    participant C as Caller
    participant S as ElevatorSystem
    participant D as DispatchStrategy
    participant E as Elevator
    participant L as ArrivalListener

    C->>S: requestHall(7, UP)
    S->>S: validate floor and button, skip if already pending
    S->>D: choose(call, status())  (immutable snapshots)
    D-->>S: "E1"
    S->>S: central check: E1 exists and is in service
    S->>S: pendingHall.put(call, "E1")
    S->>E: addStop(7, UP)

    C->>S: step()
    S->>S: tick++
    loop each elevator
        S->>E: step(tick)  (current state does one tick)
        E-->>S: Optional Arrival(E1, 7, UP, tick)
        S->>S: pendingHall.remove(HallCall(7, UP), "E1")
        S->>L: listen(arrival)
    end
```

## Legend
| Arrow | Meaning | Here |
|---|---|---|
| `..\|>` | implements an interface | the 5 states, `Elevator` and its snapshot, both strategies, the listener |
| `<\|--` | extends a class | the exception classes |
| `*--` | composition: the owner creates the part and controls its lifetime | the system creates and owns its elevators; `States` holds one shared instance of each state |
| `o--` | aggregation: the part is passed in | the dispatch strategy (swappable) and the listener are passed to the system |
| `-->` | association: holds a reference | `Elevator` holds its current state; the system holds the pending `HallCall`s |
| `..>` | dependency: uses it but doesn't keep it | the states use `StateSupport`; strategies read `ElevatorView` snapshots |

## Patterns to name when you explain it
| Pattern | Where | Why it fits |
|---|---|---|
| **State** | `ElevatorState` with 5 implementations | What a tick does depends on the state, and there's no `if/else` on an enum. A new state is a new class. |
| **Strategy** | `DispatchStrategy`: `NearestSuitable`, `LeastLoaded` | The dispatch rule can be swapped at runtime, and a switch affects only future calls. |
| **Mediator** | `ElevatorSystem` | Buttons talk only to the controller, and elevators never talk to each other. |
| **Observer** | `ArrivalListener` | Logging, display and tests subscribe to arrivals without the system knowing who's listening. |
| **Snapshot (immutable view)** | `ElevatorSnapshot` from `status()` | Callers and strategies can read state but never change it. |

## Design points worth saying out loud
- **Hall calls and car calls both become stops inside the elevator, through one `addStop(floor, direction)`.** The **controller** keeps each hall call's identity in `pendingHall`, for removing duplicates and for reassigning calls when an elevator goes into maintenance.
- **Each arrival records the direction being served.** That's how `pendingHall.remove(call, id)` removes the right call, including at a turnaround floor.
- **`setMaintenance` checks before it changes anything.** If the elevator's calls would have nowhere to go, it refuses and changes nothing.
- **There are no threads or sleeps in the core.** Time only moves when `step()` is called, so every test is repeatable. That also sets up Part 2: one simulation thread changes the elevators, while button presses arrive from other threads.