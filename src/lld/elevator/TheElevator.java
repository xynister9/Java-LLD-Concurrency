package elevator;

import static testkit.TestKit.*;

import java.util.*;

// ============================================================================
// 0. Helpers
// ============================================================================

final class Checks {
    private Checks() {}

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

/** Elevator ids look like "E0", "E1", ... Compare by the number so "E2" comes before "E10". */
final class ElevatorIds {
    private ElevatorIds() {}

    static final Comparator<String> BY_NUMBER =
            Comparator.comparingInt((String id) -> Integer.parseInt(id.replaceAll("\\D", "")))
                      .thenComparing(Comparator.naturalOrder());
}

// ============================================================================
// 1. Enums and records
// ============================================================================

enum Direction {
    UP, DOWN;

    Direction opposite() {
        return this == UP ? DOWN : UP;
    }
}

record HallCall(int floor, Direction direction) {
    HallCall {
        if (floor < 0) throw new IllegalArgumentException("floor must be >= 0");
        Objects.requireNonNull(direction, "direction");
    }
}

/** Doors opened at {@code floor}, serving {@code direction}, on {@code tick}. */
record Arrival(String elevatorId, int floor, Direction direction, int tick) {
    Arrival {
        Checks.nonBlank(elevatorId, "elevatorId");
        Objects.requireNonNull(direction, "direction");
        if (floor < 0) throw new IllegalArgumentException("floor must be >= 0");
        if (tick < 0) throw new IllegalArgumentException("tick must be >= 0");
    }
}

// ============================================================================
// 2. Elevator states (State pattern; stateless, one shared instance each)
// ============================================================================

interface ElevatorState {
    Optional<Arrival> step(Elevator e);
}

/** No direction. Opens doors if a stop is right here, otherwise heads to the nearest stop (moving this tick). */
class IdleState implements ElevatorState {
    @Override
    public Optional<Arrival> step(Elevator e) {
        if (!e.hasStops()) {
            e.setDirection(null);
            return Optional.empty();
        }
        Optional<Direction> here = e.stopHere(Direction.UP).or(() -> e.stopHere(Direction.DOWN));
        if (here.isPresent()) {
            return StateSupport.openDoors(e, here.get());
        }
        int target = e.nearestStop().orElseThrow();
        Direction d = target > e.floor() ? Direction.UP : Direction.DOWN;
        e.setDirection(d);
        e.setState(StateSupport.movingState(d));
        return e.state().step(e);                 // start moving in this same tick
    }
}

class MovingUpState implements ElevatorState {
    @Override
    public Optional<Arrival> step(Elevator e) {
        return StateSupport.travel(e, Direction.UP);
    }
}

class MovingDownState implements ElevatorState {
    @Override
    public Optional<Arrival> step(Elevator e) {
        return StateSupport.travel(e, Direction.DOWN);
    }
}

/** Doors were opened last tick (arrival already reported). This tick they close and the next move is decided. */
class DoorsOpenState implements ElevatorState {
    @Override
    public Optional<Arrival> step(Elevator e) {
        Direction d = e.direction().orElse(Direction.UP);
        if (e.nextTarget(d).isPresent()) {
            e.setState(StateSupport.movingState(d));          // keep going the same way
        } else if (e.hasStops()) {
            Direction back = d.opposite();                     // nothing ahead, something behind
            e.setDirection(back);
            e.setState(StateSupport.movingState(back));
        } else {
            e.setDirection(null);
            e.setState(States.IDLE);
        }
        return Optional.empty();
    }
}

/** Out of service: does nothing. The system clears stops and reassigns hall calls when entering this state. */
class MaintenanceState implements ElevatorState {
    @Override
    public Optional<Arrival> step(Elevator e) {
        return Optional.empty();
    }
}

/** Logic shared by the states (MovingUp and MovingDown are mirror images). */
final class StateSupport {
    private StateSupport() {}

    static ElevatorState movingState(Direction d) {
        return d == Direction.UP ? States.MOVING_UP : States.MOVING_DOWN;
    }

    /** Serve the current floor: remove the stop, open doors, report the arrival. */
    static Optional<Arrival> openDoors(Elevator e, Direction served) {
        e.removeStop(e.floor(), served);
        e.setDirection(served);
        e.setState(States.DOORS_OPEN);
        return Optional.of(new Arrival(e.id(), e.floor(), served, e.tick()));
    }

    /** One tick of LOOK while travelling in d. */
    static Optional<Arrival> travel(Elevator e, Direction d) {
        Optional<Direction> served = e.stopHere(d);
        if (served.isPresent()) {
            return openDoors(e, served.get());                 // a stop for this direction (or the turnaround point)
        }
        if (e.nextTarget(d).filter(t -> t != e.floor()).isPresent()) {
            e.moveOneFloor(d);                                 // something ahead: move one floor
            return Optional.empty();
        }
        if (e.hasStops()) {                                    // nothing ahead, something behind: turn (uses this tick)
            Direction back = d.opposite();
            e.setDirection(back);
            e.setState(movingState(back));
        } else {
            e.setDirection(null);
            e.setState(States.IDLE);
        }
        return Optional.empty();
    }
}

final class States {
    static final ElevatorState IDLE = new IdleState();
    static final ElevatorState MOVING_UP = new MovingUpState();
    static final ElevatorState MOVING_DOWN = new MovingDownState();
    static final ElevatorState DOORS_OPEN = new DoorsOpenState();
    static final ElevatorState MAINTENANCE = new MaintenanceState();

    private States() {}
}

// ============================================================================
// 3. Dispatch strategies (Strategy, swappable at runtime)
// ============================================================================

interface DispatchStrategy {
    /** Returns the chosen elevator id, or empty if none can take the call. */
    Optional<String> choose(HallCall call, List<? extends ElevatorView> elevators);
}

/**
 * Lowest cost wins. Idle, or already heading toward the call in its direction: distance.
 * Otherwise: distance to the turnaround point, then back. Maintenance excluded. Ties: lowest id.
 */
class NearestSuitable implements DispatchStrategy {
    @Override
    public Optional<String> choose(HallCall call, List<? extends ElevatorView> elevators) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(elevators, "elevators");
        record Candidate(String id, int cost) {}                  // compute each cost once
        return elevators.stream()
                .filter(e -> e.state() != States.MAINTENANCE)
                .map(e -> new Candidate(e.id(), cost(e, call)))
                .min(Comparator.comparingInt(Candidate::cost)
                        .thenComparing(Candidate::id, ElevatorIds.BY_NUMBER))
                .map(Candidate::id);
    }

    /** Estimated floors to travel before this elevator can serve the call. */
    static int cost(ElevatorView e, HallCall call) {
        int at = e.floor(), target = call.floor();
        Optional<Direction> dir = e.direction();
        SortedSet<Integer> stops = new TreeSet<>(e.upStops());
        stops.addAll(e.downStops());

        if (dir.isEmpty() || stops.isEmpty()) return Math.abs(at - target);

        if (dir.get() == Direction.UP) {
            if (call.direction() == Direction.UP && target >= at) return target - at;
            int turn = Math.max(at, stops.last());
            return (turn - at) + Math.abs(turn - target);
        } else {
            if (call.direction() == Direction.DOWN && target <= at) return at - target;
            int turn = Math.min(at, stops.first());
            return (at - turn) + Math.abs(target - turn);
        }
    }
}

/** Fewest pending stops wins. Ties: closer elevator, then lowest id. Maintenance excluded. */
class LeastLoaded implements DispatchStrategy {
    @Override
    public Optional<String> choose(HallCall call, List<? extends ElevatorView> elevators) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(elevators, "elevators");
        return elevators.stream()
                .filter(e -> e.state() != States.MAINTENANCE)
                .min(Comparator.comparingInt((ElevatorView e) -> e.upStops().size() + e.downStops().size())
                        .thenComparingInt(e -> Math.abs(e.floor() - call.floor()))
                        .thenComparing(ElevatorView::id, ElevatorIds.BY_NUMBER))
                .map(ElevatorView::id);
    }
}

// ============================================================================
// 4. Arrival listener (Observer)
// ============================================================================

interface ArrivalListener {
    void listen(Arrival arrival);
}

class SysOutArrivalListener implements ArrivalListener {
    @Override
    public void listen(Arrival arrival) {
        System.out.println("[Arrival] Elevator id: " + arrival.elevatorId() + " Floor: " + arrival.floor()
                + " Tick: " + arrival.tick());
    }
}

// ============================================================================
// 5. Exceptions: one base class
// ============================================================================

class ElevatorException extends RuntimeException {
    ElevatorException(String msg) { super(msg); }
}

class FloorOutOfBoundException extends ElevatorException {
    FloorOutOfBoundException(int floor) { super("Floor out of bound: " + floor); }
}

class InvalidHallCallException extends ElevatorException {
    InvalidHallCallException(int floor, Direction d) { super("No " + d + " button on floor " + floor); }
}

class NoElevatorAvailableException extends ElevatorException {
    NoElevatorAvailableException() { super("No elevator available"); }
}

class InvalidElevatorIdException extends ElevatorException {
    InvalidElevatorIdException(String id) { super("No elevator matching id: " + id); }
}

class ElevatorInMaintenanceException extends ElevatorException {
    ElevatorInMaintenanceException(String id) { super("Elevator " + id + " is in maintenance"); }
}

// ============================================================================
// 6. Elevator, its read-only view, and snapshots
// ============================================================================

/** Read-only view (id, floor, state, direction, pending stops). */
interface ElevatorView {
    String id();
    int floor();
    ElevatorState state();
    Optional<Direction> direction();
    Set<Integer> upStops();
    Set<Integer> downStops();
}

/** Immutable copy handed to callers and strategies, so nobody outside can see or change live state. */
record ElevatorSnapshot(String id, int floor, ElevatorState state, Direction currentDirection,
                        Set<Integer> upStops, Set<Integer> downStops) implements ElevatorView {
    ElevatorSnapshot {
        upStops = Set.copyOf(upStops);
        downStops = Set.copyOf(downStops);
    }

    @Override
    public Optional<Direction> direction() {
        return Optional.ofNullable(currentDirection);
    }
}

class Elevator implements ElevatorView {
    private final String elevatorId;
    private final int totalFloors;
    private int currentFloor = 0;
    private ElevatorState currentState = States.IDLE;
    private Direction currentDirection = null;                // null = no direction (idle)
    private final NavigableSet<Integer> upStops = new TreeSet<>();
    private final NavigableSet<Integer> downStops = new TreeSet<>();
    private int tick = 0;                                     // tick of the step in progress (set by the system)

    Elevator(String elevatorId, int totalFloors) {
        this.elevatorId = Checks.nonBlank(elevatorId, "elevatorId");
        if (totalFloors < 2) throw new IllegalArgumentException("totalFloors must be at least 2");
        this.totalFloors = totalFloors;
    }

    Optional<Arrival> step(int tick) {
        this.tick = tick;
        return currentState.step(this);
    }

    void addStop(int floor, Direction dir) {
        (dir == Direction.UP ? upStops : downStops).add(floor);
    }

    void clearStops() {
        upStops.clear();
        downStops.clear();
    }

    void setState(ElevatorState state) {
        currentState = Objects.requireNonNull(state, "state");
    }

    boolean inMaintenance() {
        return currentState == States.MAINTENANCE;
    }

    ElevatorSnapshot snapshot() {
        return new ElevatorSnapshot(elevatorId, currentFloor, currentState, currentDirection, upStops, downStops);
    }

    // ---- ElevatorView ----
    @Override public String id() { return elevatorId; }
    @Override public int floor() { return currentFloor; }
    @Override public ElevatorState state() { return currentState; }
    @Override public Optional<Direction> direction() { return Optional.ofNullable(currentDirection); }
    @Override public Set<Integer> upStops() { return Set.copyOf(upStops); }
    @Override public Set<Integer> downStops() { return Set.copyOf(downStops); }

    // ---- helpers used only by the states (package-private) ----

    int tick() {
        return tick;
    }

    void setDirection(Direction direction) {
        currentDirection = direction;
    }

    void moveOneFloor(Direction d) {
        int next = currentFloor + (d == Direction.UP ? 1 : -1);
        if (next < 0 || next >= totalFloors) {
            throw new IllegalStateException(elevatorId + " can't move to floor " + next);
        }
        currentFloor = next;
    }

    boolean hasStops() {
        return !upStops.isEmpty() || !downStops.isEmpty();
    }

    void removeStop(int floor, Direction d) {
        (d == Direction.UP ? upStops : downStops).remove(floor);
    }

    /** Nearest stop in either set; ties prefer the stop above. O(log n). */
    Optional<Integer> nearestStop() {
        int f = currentFloor;
        Integer above = minOf(upStops.ceiling(f), downStops.ceiling(f));
        Integer below = maxOf(upStops.floor(f), downStops.floor(f));
        if (above == null) return Optional.ofNullable(below);
        if (below == null) return Optional.of(above);
        return Optional.of(above - f <= f - below ? above : below);
    }

    /**
     * Should the doors open here while travelling in {@code travelling}? Returns the direction being served:
     * a stop for this direction, or an opposite-direction stop that is the turnaround point (nothing further ahead).
     */
    Optional<Direction> stopHere(Direction travelling) {
        int f = currentFloor;
        if (travelling == Direction.UP) {
            if (upStops.contains(f)) return Optional.of(Direction.UP);
            boolean nothingAbove = upStops.higher(f) == null && downStops.higher(f) == null;
            return downStops.contains(f) && nothingAbove ? Optional.of(Direction.DOWN) : Optional.empty();
        }
        if (downStops.contains(f)) return Optional.of(Direction.DOWN);
        boolean nothingBelow = downStops.lower(f) == null && upStops.lower(f) == null;
        return upStops.contains(f) && nothingBelow ? Optional.of(Direction.UP) : Optional.empty();
    }

    /** Next floor to head for while travelling in {@code travelling} (LOOK), including the turnaround point. */
    Optional<Integer> nextTarget(Direction travelling) {
        int f = currentFloor;
        if (travelling == Direction.UP) {
            Integer ahead = upStops.ceiling(f);
            if (ahead != null) return Optional.of(ahead);
            return downStops.isEmpty() || downStops.last() < f ? Optional.empty() : Optional.of(downStops.last());
        }
        Integer ahead = downStops.floor(f);
        if (ahead != null) return Optional.of(ahead);
        return upStops.isEmpty() || upStops.first() > f ? Optional.empty() : Optional.of(upStops.first());
    }

    private static Integer minOf(Integer a, Integer b) {
        return a == null ? b : b == null ? a : Integer.valueOf(Math.min(a, b));
    }

    private static Integer maxOf(Integer a, Integer b) {
        return a == null ? b : b == null ? a : Integer.valueOf(Math.max(a, b));
    }
}

// ============================================================================
// 7. ElevatorSystem: the controller (Mediator). Validate -> decide -> commit.
// ============================================================================

class ElevatorSystem {
    private final int floors;
    private final Map<String, Elevator> elevators = new LinkedHashMap<>();   // id -> elevator, in id order
    private final Map<HallCall, String> pendingHall = new HashMap<>();       // hall call -> assigned elevator id
    private final ArrivalListener arrivalListener;
    private DispatchStrategy dispatchStrategy;
    private int tick = 0;

    ElevatorSystem(int floors, int elevatorCount, DispatchStrategy dispatchStrategy, ArrivalListener arrivalListener) {
        if (floors < 2) throw new IllegalArgumentException("floors must be at least 2");
        if (elevatorCount < 1) throw new IllegalArgumentException("elevatorCount must be at least 1");
        this.floors = floors;
        this.dispatchStrategy = Objects.requireNonNull(dispatchStrategy, "dispatchStrategy");
        this.arrivalListener = Objects.requireNonNull(arrivalListener, "arrivalListener");
        for (int i = 0; i < elevatorCount; i++) {
            String id = "E" + i;
            elevators.put(id, new Elevator(id, floors));
        }
    }

    public synchronized void requestHall(int floor, Direction direction) {
        validateFloor(floor);
        Objects.requireNonNull(direction, "direction");
        if ((floor == 0 && direction == Direction.DOWN) || (floor == floors - 1 && direction == Direction.UP)) {
            throw new InvalidHallCallException(floor, direction);
        }
        HallCall call = new HallCall(floor, direction);
        if (pendingHall.containsKey(call)) return;                       // duplicate: already assigned

        String id = dispatch(call);
        pendingHall.put(call, id);
        elevators.get(id).addStop(floor, direction);
    }

    public synchronized void requestCar(String elevatorId, int floor) {
        validateFloor(floor);
        Elevator e = elevator(elevatorId);
        if (e.inMaintenance()) throw new ElevatorInMaintenanceException(elevatorId);
        e.addStop(floor, floor >= e.floor() ? Direction.UP : Direction.DOWN);   // TreeSet ignores duplicates
    }

    public synchronized void step() {
        tick++;
        for (Elevator e : elevators.values()) {
            e.step(tick).ifPresent(arrival -> {
                // remove(key, value): only if the call is still assigned to THIS elevator
                pendingHall.remove(new HallCall(arrival.floor(), arrival.direction()), arrival.elevatorId());
                arrivalListener.listen(arrival);
            });
        }
    }

    public int tick() {
        return tick;
    }

    /** Immutable snapshots: callers can't see later changes or reach the live elevators. */
    public synchronized List<ElevatorSnapshot> status() {
        return elevators.values().stream().map(Elevator::snapshot).toList();
    }

    /** Which elevator each waiting hall call is assigned to (read-only copy). */
    public synchronized Map<HallCall, String> pendingHallCalls() {
        return Map.copyOf(pendingHall);
    }

    public synchronized void setDispatchStrategy(DispatchStrategy dispatchStrategy) {
        this.dispatchStrategy = Objects.requireNonNull(dispatchStrategy, "dispatchStrategy");
    }

    public synchronized void setMaintenance(String elevatorId, boolean enable) {
        Elevator target = elevator(elevatorId);
        if (!enable) {
            if (target.inMaintenance()) target.setState(States.IDLE);   // only leave maintenance if actually in it
            return;
        }
        if (target.inMaintenance()) return;

        // 1. Validate before changing anything: its hall calls must have somewhere to go
        List<HallCall> orphaned = pendingHall.entrySet().stream()
                .filter(en -> en.getValue().equals(elevatorId))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparingInt(HallCall::floor).thenComparing(HallCall::direction))
                .toList();
        boolean othersAvailable = elevators.values().stream().anyMatch(e -> e != target && !e.inMaintenance());
        if (!orphaned.isEmpty() && !othersAvailable) throw new NoElevatorAvailableException();

        // 2. Commit
        target.clearStops();
        target.setDirection(null);
        target.setState(States.MAINTENANCE);
        orphaned.forEach(pendingHall::remove);
        orphaned.forEach(c -> requestHall(c.floor(), c.direction()));   // strategies skip the target now
    }

    // ---- helpers ----

    private String dispatch(HallCall call) {
        String id = dispatchStrategy.choose(call, status()).orElseThrow(NoElevatorAvailableException::new);
        Elevator chosen = elevators.get(id);
        if (chosen == null || chosen.inMaintenance()) {                  // central check: don't trust a strategy blindly
            throw new IllegalStateException("dispatch strategy chose an unusable elevator: " + id);
        }
        return id;
    }

    private Elevator elevator(String id) {
        Objects.requireNonNull(id, "elevatorId");
        Elevator e = elevators.get(id);
        if (e == null) throw new InvalidElevatorIdException(id);
        return e;
    }

    private void validateFloor(int floor) {
        if (floor < 0 || floor >= floors) throw new FloorOutOfBoundException(floor);
    }
}

// ============================================================================
// 8. Tests
// ============================================================================

public class TheElevator {
    public static void main(String[] args) {
        System.out.println("\n---- Elevator Tests ----\n");

        // ------------------------------------------------------------ movement (LOOK + states)
        test("Simple trip: car call 5 from 0 -> at 5 after 5 ticks, arrival on tick 6, IDLE after tick 7", () -> {
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(10, 1, arrivals);
            sys.requestCar("E0", 5);
            for (int i = 0; i < 5; i++) sys.step();
            checkEquals(5, sys.status().get(0).floor(), "floor after 5 ticks");
            sys.step();
            checkEquals(List.of(new Arrival("E0", 5, Direction.UP, 6)), arrivals, "arrivals");
            sys.step();
            check(sys.status().get(0).state() == States.IDLE, "IDLE after tick 7");
        });

        test("LOOK order: at 3 heading up with stops 5 and 1 -> 5 then 1", () -> {
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(10, 1, arrivals);
            sys.requestCar("E0", 3);
            stepUntilArrivals(sys, arrivals, 1);                         // doors open at 3, direction UP
            sys.requestCar("E0", 5);
            sys.requestCar("E0", 1);
            stepUntilArrivals(sys, arrivals, 3);
            checkEquals(List.of(3, 5, 1), floors(arrivals), "order");
        });

        test("Pick-up on the way: heading 0 -> 8, hall UP 4 added at floor 2 -> 4 then 8", () -> {
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(10, 1, arrivals);
            sys.requestCar("E0", 8);
            sys.step(); sys.step();
            checkEquals(2, sys.status().get(0).floor(), "floor");
            sys.requestHall(4, Direction.UP);
            stepUntilArrivals(sys, arrivals, 2);
            checkEquals(List.of(4, 8), floors(arrivals), "order");
        });

        test("Wrong direction on the way: heading up to 8, hall DOWN 4 -> 8 first, then 4", () -> {
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(10, 1, arrivals);
            sys.requestCar("E0", 8);
            sys.step(); sys.step();
            sys.requestHall(4, Direction.DOWN);
            stepUntilArrivals(sys, arrivals, 2);
            checkEquals(List.of(8, 4), floors(arrivals), "order");
            checkEquals(Direction.DOWN, arrivals.get(1).direction(), "served direction at 4");
        });

        test("Turnaround: only a hall DOWN at 6 -> elevator goes up and serves it as DOWN; call cleared", () -> {
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(10, 1, arrivals);
            sys.requestHall(6, Direction.DOWN);
            stepUntilArrivals(sys, arrivals, 1);
            checkEquals(new Arrival("E0", 6, Direction.DOWN, 7), arrivals.get(0), "arrival");
            check(sys.pendingHallCalls().isEmpty(), "hall call should be cleared");
        });

        // ------------------------------------------------------------ dispatch
        test("Dispatch nearest: E0 idle at 0, E1 idle at 9, hall at 7 -> E1", () -> {
            ElevatorSystem sys = system(10, 2, new ArrayList<>());
            sys.requestCar("E1", 9);
            runUntilIdle(sys);
            sys.requestHall(7, Direction.UP);
            checkEquals("E1", sys.pendingHallCalls().get(new HallCall(7, Direction.UP)), "assigned");
        });

        test("Dispatch prefers an elevator already coming: E0 at 2 heading up to 9 beats idle E1 at 0", () -> {
            ElevatorSystem sys = system(10, 2, new ArrayList<>());
            sys.requestCar("E0", 9);
            sys.step(); sys.step();                                      // E0 at 2, moving up
            sys.requestHall(5, Direction.UP);
            checkEquals("E0", sys.pendingHallCalls().get(new HallCall(5, Direction.UP)), "assigned");
        });

        test("Dispatch tie -> lowest id", () -> {
            ElevatorSystem sys = system(10, 3, new ArrayList<>());
            sys.requestHall(3, Direction.UP);
            checkEquals("E0", sys.pendingHallCalls().get(new HallCall(3, Direction.UP)), "assigned");
        });

        test("Switching strategy affects only future assignments", () -> {
            ElevatorSystem sys = system(10, 2, new ArrayList<>());
            sys.requestCar("E1", 5);
            runUntilIdle(sys);                                           // E1 idle at 5, E0 idle at 0
            sys.requestCar("E0", 8);
            sys.requestCar("E0", 9);
            sys.requestHall(1, Direction.UP);                            // NearestSuitable: E0 (1 away) vs E1 (4 away)
            checkEquals("E0", sys.pendingHallCalls().get(new HallCall(1, Direction.UP)), "before switch");
            sys.setDispatchStrategy(new LeastLoaded());
            sys.requestHall(2, Direction.UP);                            // LeastLoaded: E0 has 3 stops, E1 has 0
            checkEquals("E1", sys.pendingHallCalls().get(new HallCall(2, Direction.UP)), "after switch");
            checkEquals("E0", sys.pendingHallCalls().get(new HallCall(1, Direction.UP)), "earlier call unchanged");
        });

        // ------------------------------------------------------------ validation & duplicates
        test("Validation: bad floors, missing buttons, unknown elevator", () -> {
            ElevatorSystem sys = system(10, 1, new ArrayList<>());
            expectThrows(InvalidHallCallException.class, () -> sys.requestHall(9, Direction.UP));
            expectThrows(InvalidHallCallException.class, () -> sys.requestHall(0, Direction.DOWN));
            expectThrows(FloorOutOfBoundException.class, () -> sys.requestHall(-1, Direction.UP));
            expectThrows(FloorOutOfBoundException.class, () -> sys.requestHall(10, Direction.DOWN));
            expectThrows(FloorOutOfBoundException.class, () -> sys.requestCar("E0", 10));
            expectThrows(InvalidElevatorIdException.class, () -> sys.requestCar("E7", 3));
            expectThrows(InvalidElevatorIdException.class, () -> sys.setMaintenance("E7", true));
        });

        test("Duplicate hall call -> one assignment, one arrival", () -> {
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(10, 2, arrivals);
            sys.requestHall(4, Direction.UP);
            sys.requestHall(4, Direction.UP);
            checkEquals(1, sys.pendingHallCalls().size(), "pending");
            runUntilIdle(sys);
            checkEquals(1, arrivals.size(), "arrivals");
        });

        // ------------------------------------------------------------ maintenance
        test("Maintenance: E0's two hall calls move to E1, and E1 serves them", () -> {
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(10, 2, arrivals);
            sys.requestHall(3, Direction.UP);
            sys.requestHall(6, Direction.DOWN);
            checkEquals(Set.of("E0"), new HashSet<>(sys.pendingHallCalls().values()), "initially E0");
            sys.setMaintenance("E0", true);
            checkEquals(Set.of("E1"), new HashSet<>(sys.pendingHallCalls().values()), "reassigned to E1");
            runUntilIdle(sys);
            check(arrivals.stream().allMatch(a -> a.elevatorId().equals("E1")), "served by E1: " + arrivals);
            checkEquals(Set.of(3, 6), new HashSet<>(floors(arrivals)), "floors served");
            expectThrows(ElevatorInMaintenanceException.class, () -> sys.requestCar("E0", 2));
        });

        test("Maintenance on the last working elevator with pending calls is refused and changes nothing", () -> {
            ElevatorSystem sys = system(10, 2, new ArrayList<>());
            sys.setMaintenance("E0", true);
            sys.requestHall(5, Direction.UP);                            // goes to E1
            expectThrows(NoElevatorAvailableException.class, () -> sys.setMaintenance("E1", true));
            check(sys.status().get(1).state() != States.MAINTENANCE, "E1 must stay in service");
            checkEquals("E1", sys.pendingHallCalls().get(new HallCall(5, Direction.UP)), "call kept");
        });

        test("Everyone in maintenance -> new hall call rejected; leaving maintenance works", () -> {
            ElevatorSystem sys = system(10, 2, new ArrayList<>());
            sys.setMaintenance("E0", true);
            sys.setMaintenance("E1", true);
            expectThrows(NoElevatorAvailableException.class, () -> sys.requestHall(5, Direction.UP));
            sys.setMaintenance("E1", false);
            sys.requestHall(5, Direction.UP);
            checkEquals("E1", sys.pendingHallCalls().get(new HallCall(5, Direction.UP)), "assigned");
        });

        test("Leaving maintenance on an elevator that isn't in it changes nothing", () -> {
            ElevatorSystem sys = system(10, 1, new ArrayList<>());
            sys.requestCar("E0", 8);
            sys.step(); sys.step();
            sys.setMaintenance("E0", false);
            check(sys.status().get(0).state() == States.MOVING_UP, "still moving up");
        });

        // ------------------------------------------------------------ snapshots
        test("status() returns immutable snapshots, not live elevators", () -> {
            ElevatorSystem sys = system(10, 1, new ArrayList<>());
            sys.requestCar("E0", 5);
            ElevatorSnapshot before = sys.status().get(0);
            sys.step(); sys.step();
            checkEquals(0, before.floor(), "old snapshot must not change");
            checkEquals(2, sys.status().get(0).floor(), "new snapshot");
            expectThrows(UnsupportedOperationException.class, () -> before.upStops().add(9));
        });

        // ------------------------------------------------------------ random end-to-end
        test("Random: every hall call is served, elevators stay in range and end IDLE", () -> repeat(200, round -> {
            Random r = new Random(round);
            List<Arrival> arrivals = new ArrayList<>();
            ElevatorSystem sys = system(15, 3, arrivals);
            for (int t = 0; t < 300; t++) {
                if (r.nextInt(3) == 0) {
                    int f = r.nextInt(15);
                    Direction d = f == 0 ? Direction.UP : f == 14 ? Direction.DOWN
                            : (r.nextBoolean() ? Direction.UP : Direction.DOWN);
                    sys.requestHall(f, d);
                }
                if (r.nextInt(4) == 0) sys.requestCar("E" + r.nextInt(3), r.nextInt(15));
                sys.step();
                for (ElevatorSnapshot s : sys.status()) {
                    check(s.floor() >= 0 && s.floor() < 15, s.id() + " left the building");
                }
            }
            runUntilIdle(sys);
            check(sys.pendingHallCalls().isEmpty(), "unserved hall calls: " + sys.pendingHallCalls());
        }));

        summary();
    }

    // ---------------------------------------------------------------- helpers
    private static ElevatorSystem system(int floors, int elevators, List<Arrival> arrivals) {
        return new ElevatorSystem(floors, elevators, new NearestSuitable(), arrivals::add);
    }

    private static void stepUntilArrivals(ElevatorSystem sys, List<Arrival> arrivals, int count) {
        for (int i = 0; i < 1000 && arrivals.size() < count; i++) sys.step();
        check(arrivals.size() >= count, "expected " + count + " arrivals, got " + arrivals);
    }

    private static void runUntilIdle(ElevatorSystem sys) {
        for (int i = 0; i < 10_000; i++) {
            boolean allIdle = sys.status().stream()
                    .allMatch(s -> (s.state() == States.IDLE || s.state() == States.MAINTENANCE)
                            && s.upStops().isEmpty() && s.downStops().isEmpty());
            if (allIdle) return;
            sys.step();
        }
        throw new AssertionError("elevators never became idle: " + sys.status());
    }

    private static List<Integer> floors(List<Arrival> arrivals) {
        return arrivals.stream().map(Arrival::floor).toList();
    }
}