package parkinglot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

// 1. Enums, value types

/**
 * Declaration order = allocation preference (smallest first). Fit rules do NOT
 * depend on this order; they live in {@link VehicleType#canParkIn(SlotSize)}.
 */
enum SlotSize {
    SMALL, MEDIUM, LARGE
}

enum VehicleType {
    BIKE(SlotSize.SMALL, SlotSize.MEDIUM, SlotSize.LARGE),
    CAR(SlotSize.MEDIUM, SlotSize.LARGE),
    TRUCK(SlotSize.LARGE);

    private final Set<SlotSize> allowedSizes;   // explicit, so reordering SlotSize can't break fit rules

    VehicleType(SlotSize... allowedSizes) {
        this.allowedSizes = Set.of(allowedSizes);
    }

    public Set<SlotSize> allowedSizes() {
        return allowedSizes;
    }

    public boolean canParkIn(SlotSize size) {
        return allowedSizes.contains(size);
    }
}

enum TicketStatus {
    ACTIVE, CLOSED
}

record Vehicle(String licensePlateNumber, VehicleType vehicleType) {
    Vehicle {
        Objects.requireNonNull(licensePlateNumber);
        Objects.requireNonNull(vehicleType);
        if (licensePlateNumber.isBlank()) {
            throw new IllegalArgumentException("LicensePlateNumber can't be Blank");
        }
    }
}

/**
 * Issued and owned by the lot. Callers only ever hand back the ticketId, so a
 * forged Ticket object can't be used to unpark. Status moves ACTIVE -> CLOSED once.
 */
final class Ticket {
    private final String ticketId;
    private final String licensePlateNumber;
    private final VehicleType vehicleType;
    private final String spotId;
    private final String gateId;
    private final Instant entryTime;
    private TicketStatus status = TicketStatus.ACTIVE;

    Ticket(String ticketId, String licensePlateNumber, VehicleType vehicleType, String spotId, String gateId,
            Instant entryTime) {
        this.ticketId = Checks.nonBlank(ticketId, "ticketId");
        this.licensePlateNumber = Checks.nonBlank(licensePlateNumber, "licensePlateNumber");
        this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        this.spotId = Checks.nonBlank(spotId, "spotId");
        this.gateId = Checks.nonBlank(gateId, "gateId");
        this.entryTime = Objects.requireNonNull(entryTime, "entryTime must not be null");
    }

    public String ticketId() { return ticketId; }

    public String licensePlateNumber() { return licensePlateNumber; }

    public VehicleType vehicleType() { return vehicleType; }

    public String spotId() { return spotId; }

    public String gateId() { return gateId; }

    public Instant entryTime() { return entryTime; }

    public TicketStatus status() { return status; }

    void close() {
        if (status == TicketStatus.CLOSED) throw new IllegalStateException("Ticket " + ticketId + " already closed");
        status = TicketStatus.CLOSED;
    }

    @Override
    public String toString() {
        return "Ticket[" + ticketId + ", " + licensePlateNumber + ", " + spotId + ", " + status + "]";
    }
}

/** Fee may be 0 (free-pricing rules). Stores the real parked Duration; hours are a pricing detail. */
record Reciept(Ticket ticket, Instant exitTime, Duration parkedDuration, int fee) {
    Reciept {
        Objects.requireNonNull(ticket);
        Objects.requireNonNull(exitTime);
        Objects.requireNonNull(parkedDuration);
        if (parkedDuration.isNegative()) {
            throw new IllegalArgumentException("ParkedDuration should be >= 0");
        }
        if (fee < 0) {
            throw new IllegalArgumentException("Fee should be >= 0");
        }
    }
}

final class Checks {
    private Checks() {}

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " can't be Blank");
        return value;
    }
}

// 2. Exceptions: one base class

class ParkingException extends RuntimeException {
    public ParkingException(String message) {
        super(message);
    }
}

class ParkingSlotException extends ParkingException {
    public ParkingSlotException(String message) {
        super(message);
    }
}

class ParkingSlotOccupiedException extends ParkingSlotException {
    public ParkingSlotOccupiedException(String slotId) {
        super("Parking slot: " + slotId + " is already occupied. Can't park vehicle on this slot");
    }
}

class NoVehicleParkedException extends ParkingSlotException {
    public NoVehicleParkedException(String slotId) {
        super("No Vehicle parked on Parking slot: " + slotId + ". Can't release vehicle from this slot");
    }
}

class CanNotParkVehicleException extends ParkingSlotException {
    public CanNotParkVehicleException(String slotId, SlotSize slotSize) {
        super("Provided vehicle can't be accomodated in the parking slot: " + slotId + " of size: " + slotSize);
    }
}

class ParkingFullException extends ParkingException {
    public ParkingFullException() {
        super("Parking full! No parking slot applicable to vehicle is vacant.");
    }
}

class VehicleAlreadyParkedException extends ParkingException {
    public VehicleAlreadyParkedException(String licensePlateNumber) {
        super("Vehicle: " + licensePlateNumber + " is already parked in the parking lot.");
    }
}

class TicketAlreadyUsedException extends ParkingException {
    public TicketAlreadyUsedException(String ticketId) {
        super("Ticket: " + ticketId + " is already used.");
    }
}

class TicketNotFoundException extends ParkingException {
    public TicketNotFoundException(String ticketId) {
        super("Ticket: " + ticketId + " not found.");
    }
}

// 3. Parking Classes

class ParkingSlot {
    private final String slotId;
    private final int floorNumber;
    private final int slotNumber;
    private final SlotSize slotSize;
    private Optional<Vehicle> parkedVehicle;

    public ParkingSlot(String slotId, int floorNumber, int slotNumber, SlotSize slotSize) {
        Checks.nonBlank(slotId, "slotId");
        Objects.requireNonNull(slotSize, "slotSize must not be null");
        if (slotNumber < 1) {
            throw new IllegalArgumentException("SlotNumber should be >= 1");
        }

        this.slotId = slotId;
        this.floorNumber = floorNumber;
        this.slotNumber = slotNumber;
        this.slotSize = slotSize;
        parkedVehicle = Optional.empty();
    }

    public String getSlotId() { return slotId; }

    public int getFloorNumber() { return floorNumber; }

    public int getSlotNumber() { return slotNumber; }

    public SlotSize getSlotSize() { return slotSize; }

    public boolean isOccupied() {
        return parkedVehicle.isPresent();
    }

    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.vehicleType().canParkIn(slotSize);
    }

    public void parkVehicle(Vehicle vehicle) {
        if (parkedVehicle.isPresent()) throw new ParkingSlotOccupiedException(slotId);
        if (!canFitVehicle(vehicle)) throw new CanNotParkVehicleException(slotId, slotSize);
        parkedVehicle = Optional.of(vehicle);
    }

    Optional<Vehicle> releaseParkedVehicle() {
        if (parkedVehicle.isEmpty()) throw new NoVehicleParkedException(slotId);
        Optional<Vehicle> p = parkedVehicle;
        parkedVehicle = Optional.empty();
        return p;
    }
}

class ParkingFloor {
    private final int floorNumber;
    private final Map<SlotSize, List<ParkingSlot>> slotsBySize;   // each list sorted by slot number
    private final List<ParkingSlot> allSlots;

    /** Groups slots by their own size, so a slot can never be filed under the wrong size. */
    public ParkingFloor(int floorNumber, List<ParkingSlot> slots) {
        Objects.requireNonNull(slots, "slots must not be null");
        if (slots.isEmpty()) throw new IllegalArgumentException("Floor " + floorNumber + " must have at least one slot");

        Set<String> seenIds = new HashSet<>();
        Map<SlotSize, List<ParkingSlot>> grouped = new HashMap<>();
        for (ParkingSlot slot : slots) {
            Objects.requireNonNull(slot, "slot must not be null");
            if (slot.getFloorNumber() != floorNumber) {
                throw new IllegalArgumentException("Slot " + slot.getSlotId() + " belongs to floor "
                        + slot.getFloorNumber() + ", not floor " + floorNumber);
            }
            if (!seenIds.add(slot.getSlotId())) {
                throw new IllegalArgumentException("Duplicate slot id: " + slot.getSlotId());
            }
            List<ParkingSlot> list = grouped.getOrDefault(slot.getSlotSize(), new ArrayList<>());
            list.add(slot);
            grouped.put(slot.getSlotSize(), list);
        }

        // Sort once at construction; the order never changes afterwards.
        Map<SlotSize, List<ParkingSlot>> sorted = new HashMap<>();
        for (Map.Entry<SlotSize, List<ParkingSlot>> e : grouped.entrySet()) {
            List<ParkingSlot> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt(ParkingSlot::getSlotNumber));
            sorted.put(e.getKey(), List.copyOf(list));
        }

        this.floorNumber = floorNumber;
        this.slotsBySize = Map.copyOf(sorted);
        this.allSlots = List.copyOf(slots);
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    /** Slots of this size, ordered by slot number (lowest first). */
    public List<ParkingSlot> getSlots(SlotSize slotSize) {
        return slotsBySize.getOrDefault(slotSize, List.of());
    }

    public List<ParkingSlot> getAllSlots() {
        return allSlots;
    }
}

// 4. Swappable Strategies

/** Only what every pricing rule needs. How a rule counts time (hours, minutes, slabs) is its own business. */
interface PricingStrategy {
    int fee(VehicleType type, Duration parked);
}

class HourlyPricingStrategy implements PricingStrategy {
    private final Map<VehicleType, Integer> ratePerHour;

    public HourlyPricingStrategy(Map<VehicleType, Integer> ratePerHour) {
        Objects.requireNonNull(ratePerHour);
        for (VehicleType vehicleType : VehicleType.values()) {
            Integer rate = ratePerHour.get(vehicleType);
            if (rate == null || rate < 0) {
                throw new IllegalArgumentException("Rate card needs a non-negative hourly rate for " + vehicleType);
            }
        }
        this.ratePerHour = Map.copyOf(ratePerHour);
    }

    @Override
    public int fee(VehicleType type, Duration parked) {
        return ratePerHour.get(type) * hoursCharged(parked);
    }

    /** Internal detail of this rule: round up to whole hours, minimum 1. */
    private static int hoursCharged(Duration parked) {
        long seconds = parked.toSeconds();
        return (int) Math.max(1, (seconds + 3599) / 3600);
    }
}

/** Chooses a slot. Deciding which error to raise when there is none is the lot's job. */
interface SlotAllocationStrategy {
    Optional<ParkingSlot> allocateSlot(List<ParkingFloor> floors, Vehicle vehicle);
}

class DefaultSlotAllocationStrategy implements SlotAllocationStrategy {

    // Smallest allowed size first (SlotSize declaration order), then lowest floor, then lowest slot number.
    @Override
    public Optional<ParkingSlot> allocateSlot(List<ParkingFloor> floors, Vehicle vehicle) {
        List<ParkingFloor> sortedFloors = new ArrayList<>(floors);
        sortedFloors.sort(Comparator.comparingInt(ParkingFloor::getFloorNumber));

        for (SlotSize slotSize : SlotSize.values()) {
            if (!vehicle.vehicleType().canParkIn(slotSize)) continue;
            for (ParkingFloor floor : sortedFloors) {
                for (ParkingSlot slot : floor.getSlots(slotSize)) {
                    if (!slot.isOccupied()) return Optional.of(slot);
                }
            }
        }
        return Optional.empty();
    }
}

// 5. ParkingLot

class ParkingLot {
    private final Clock clock;
    private final List<ParkingFloor> floors;
    private final PricingStrategy pricingStrategy;
    private final SlotAllocationStrategy slotAllocationStrategy;

    private final Map<String, ParkingSlot> parkedVehicles = new HashMap<>();   // plate -> slot
    // The lot's own tickets are the only source of truth. Closed tickets stay here for this exercise;
    // in production they'd be archived (moved to storage) so this map doesn't grow forever.
    private final Map<String, Ticket> tickets = new HashMap<>();              // ticketId -> ticket

    public ParkingLot(Clock clock, List<ParkingFloor> floors, PricingStrategy pricingStrategy,
            SlotAllocationStrategy slotAllocationStrategy) {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(floors);
        Objects.requireNonNull(pricingStrategy);
        Objects.requireNonNull(slotAllocationStrategy);
        if (floors.isEmpty()) {
            throw new IllegalArgumentException("Parking Lot must have atleast one Floor");
        }
        Set<Integer> floorNumbers = new HashSet<>();
        Set<String> slotIds = new HashSet<>();
        for (ParkingFloor floor : floors) {
            if (!floorNumbers.add(floor.getFloorNumber())) {
                throw new IllegalArgumentException("Duplicate floor number: " + floor.getFloorNumber());
            }
            for (ParkingSlot slot : floor.getAllSlots()) {
                if (!slotIds.add(slot.getSlotId())) {
                    throw new IllegalArgumentException("Duplicate slot id across floors: " + slot.getSlotId());
                }
            }
        }

        this.clock = clock;
        this.floors = List.copyOf(floors);
        this.pricingStrategy = pricingStrategy;
        this.slotAllocationStrategy = slotAllocationStrategy;
    }

    public Ticket parkVehicle(Vehicle vehicle, String gateId) {
        // 1. Validate
        Objects.requireNonNull(vehicle, "vehicle must not be null");
        Checks.nonBlank(gateId, "gateId");
        if (parkedVehicles.containsKey(vehicle.licensePlateNumber())) {
            throw new VehicleAlreadyParkedException(vehicle.licensePlateNumber());
        }

        // 2. Compute
        ParkingSlot slot = slotAllocationStrategy.allocateSlot(floors, vehicle)
                .orElseThrow(ParkingFullException::new);
        Ticket ticket = new Ticket(UUID.randomUUID().toString(), vehicle.licensePlateNumber(),
                vehicle.vehicleType(), slot.getSlotId(), gateId, clock.instant());

        // 3. Commit
        slot.parkVehicle(vehicle);
        parkedVehicles.put(vehicle.licensePlateNumber(), slot);
        tickets.put(ticket.ticketId(), ticket);
        return ticket;
    }

    public Reciept unpark(String ticketId, String gateId) {
        // 1. Validate (the lot's own map decides what is a real ticket)
        Checks.nonBlank(ticketId, "ticketId");
        Checks.nonBlank(gateId, "gateId");
        Ticket ticket = tickets.get(ticketId);
        if (ticket == null) {
            throw new TicketNotFoundException(ticketId);
        }
        if (ticket.status() == TicketStatus.CLOSED) {
            throw new TicketAlreadyUsedException(ticketId);
        }

        // 2. Compute
        ParkingSlot slot = parkedVehicles.get(ticket.licensePlateNumber());
        Instant exitTime = clock.instant();
        Duration parked = Duration.between(ticket.entryTime(), exitTime);
        int fee = pricingStrategy.fee(ticket.vehicleType(), parked);
        Reciept reciept = new Reciept(ticket, exitTime, parked, fee);

        // 3. Commit
        slot.releaseParkedVehicle();
        parkedVehicles.remove(ticket.licensePlateNumber());
        ticket.close();
        return reciept;
    }

    /** floor -> (size -> free count), both levels in stable sorted order. */
    public Map<Integer, Map<SlotSize, Integer>> getAvailability() {
        Map<Integer, Map<SlotSize, Integer>> floorMap = new TreeMap<>();
        for (ParkingFloor floor : floors) {
            Map<SlotSize, Integer> slotMap = new TreeMap<>();
            for (SlotSize size : SlotSize.values()) {
                int count = (int) floor.getSlots(size).stream().filter(slot -> !slot.isOccupied()).count();
                slotMap.put(size, count);
            }
            floorMap.put(floor.getFloorNumber(), slotMap);
        }
        return floorMap;
    }
}

// 6. Test support

/** A clock tests can move forward, so fee boundaries are deterministic. */
class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) { now = start; }

    void advance(Duration d) { now = now.plus(d); }

    @Override public Instant instant() { return now; }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return this; }
}

public class PLot {
    private static int passed = 0;

    public static void main(String[] args) {
        System.out.println("\n---- Parking Lot Test Suite ----\n");

        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        PricingStrategy pricing = new HourlyPricingStrategy(Map.of(
                VehicleType.BIKE, 10,
                VehicleType.CAR, 20,
                VehicleType.TRUCK, 50));
        SlotAllocationStrategy allocation = new DefaultSlotAllocationStrategy();

        // Floor 1: 1 Small slot, 1 Medium slot, no Large slots
        ParkingLot parkingLot = new ParkingLot(clock, List.of(new ParkingFloor(1, List.of(
                new ParkingSlot("F1-S1", 1, 1, SlotSize.SMALL),
                new ParkingSlot("F1-M1", 1, 2, SlotSize.MEDIUM)))), pricing, allocation);

        test("Bike falls back to MEDIUM when SMALL is full", () -> {
            Ticket t1 = parkingLot.parkVehicle(new Vehicle("BIKE-01", VehicleType.BIKE), "Gate-1");
            check(t1.spotId().equals("F1-S1"), "First bike should take SMALL spot, got " + t1.spotId());
            Ticket t2 = parkingLot.parkVehicle(new Vehicle("BIKE-02", VehicleType.BIKE), "Gate-1");
            check(t2.spotId().equals("F1-M1"), "Second bike should fall back to MEDIUM, got " + t2.spotId());
            parkingLot.unpark(t1.ticketId(), "Gate-1");
            parkingLot.unpark(t2.ticketId(), "Gate-1");
        });

        test("Truck with no LARGE spots: lot throws ParkingFullException", () ->
                expectThrows(ParkingFullException.class,
                        () -> parkingLot.parkVehicle(new Vehicle("TRUCK-01", VehicleType.TRUCK), "Gate-1")));

        test("Fee boundaries and receipt stores the real Duration", () -> {
            int[][] cases = { {0, 20}, {59, 20}, {60, 20}, {61, 40} };   // {minutes parked, expected CAR fee}
            for (int[] c : cases) {
                Ticket t = parkingLot.parkVehicle(new Vehicle("CAR-" + c[0], VehicleType.CAR), "Gate-1");
                clock.advance(Duration.ofMinutes(c[0]));
                Reciept r = parkingLot.unpark(t.ticketId(), "Gate-1");
                check(r.fee() == c[1], c[0] + " min: expected fee " + c[1] + ", got " + r.fee());
                check(r.parkedDuration().equals(Duration.ofMinutes(c[0])),
                        c[0] + " min: expected duration " + c[0] + "m, got " + r.parkedDuration());
            }
        });

        test("Unparking the same ticket twice is rejected; ticket becomes CLOSED", () -> {
            Ticket t = parkingLot.parkVehicle(new Vehicle("CAR-X", VehicleType.CAR), "Gate-1");
            check(t.status() == TicketStatus.ACTIVE, "new ticket should be ACTIVE");
            parkingLot.unpark(t.ticketId(), "Gate-1");
            check(t.status() == TicketStatus.CLOSED, "ticket should be CLOSED after unpark");
            expectThrows(TicketAlreadyUsedException.class, () -> parkingLot.unpark(t.ticketId(), "Gate-1"));
        });

        test("Unknown / forged ticket id is rejected", () ->
                expectThrows(TicketNotFoundException.class, () -> parkingLot.unpark("made-up-id", "Gate-1")));

        test("Parking the same license plate twice is rejected", () -> {
            Ticket t = parkingLot.parkVehicle(new Vehicle("CAR-02", VehicleType.CAR), "Gate-1");
            expectThrows(VehicleAlreadyParkedException.class,
                    () -> parkingLot.parkVehicle(new Vehicle("CAR-02", VehicleType.CAR), "Gate-1"));
            parkingLot.unpark(t.ticketId(), "Gate-1");
        });

        test("Availability updates and has stable order (floors asc; SMALL, MEDIUM, LARGE)", () -> {
            check(smallFree(parkingLot) == 1, "Small slot should be free");
            Ticket t = parkingLot.parkVehicle(new Vehicle("BIKE-03", VehicleType.BIKE), "Gate-1");
            check(smallFree(parkingLot) == 0, "Small slot count should drop to 0");
            parkingLot.unpark(t.ticketId(), "Gate-1");
            check(smallFree(parkingLot) == 1, "Small slot count should return to 1 after unpark");
            List<SlotSize> order = new ArrayList<>(parkingLot.getAvailability().get(1).keySet());
            check(order.equals(List.of(SlotSize.SMALL, SlotSize.MEDIUM, SlotSize.LARGE)), "order was " + order);
        });

        test("Null/blank gateId fails BEFORE any state changes", () -> {
            Vehicle car = new Vehicle("CAR-GATE", VehicleType.CAR);
            expectThrows(NullPointerException.class, () -> parkingLot.parkVehicle(car, null));
            expectThrows(IllegalArgumentException.class, () -> parkingLot.parkVehicle(car, "  "));
            check(parkingLot.getAvailability().get(1).get(SlotSize.MEDIUM) == 1, "MEDIUM slot must still be free");
            Ticket t = parkingLot.parkVehicle(car, "Gate-1");
            parkingLot.unpark(t.ticketId(), "Gate-1");
        });

        test("If pricing throws, unpark changes nothing and can be retried", () -> {
            PricingStrategy broken = (type, parked) -> { throw new IllegalStateException("pricing down"); };
            ParkingLot lot = new ParkingLot(clock, List.of(new ParkingFloor(1, List.of(
                    new ParkingSlot("F1-M1", 1, 1, SlotSize.MEDIUM)))), broken, allocation);
            Ticket t = lot.parkVehicle(new Vehicle("CAR-PR", VehicleType.CAR), "Gate-1");
            expectThrows(IllegalStateException.class, () -> lot.unpark(t.ticketId(), "Gate-1"));
            check(lot.getAvailability().get(1).get(SlotSize.MEDIUM) == 0, "slot must still be occupied");
            check(t.status() == TicketStatus.ACTIVE, "ticket must still be ACTIVE");
            expectThrows(IllegalStateException.class, () -> lot.unpark(t.ticketId(), "Gate-1"));
        });

        test("Slots are chosen by number (S2 before S10), not alphabetically", () -> {
            ParkingLot lot = new ParkingLot(clock, List.of(new ParkingFloor(1, List.of(
                    new ParkingSlot("F1-S10", 1, 10, SlotSize.SMALL),
                    new ParkingSlot("F1-S2", 1, 2, SlotSize.SMALL)))), pricing, allocation);
            Ticket t = lot.parkVehicle(new Vehicle("BIKE-N", VehicleType.BIKE), "Gate-1");
            check(t.spotId().equals("F1-S2"), "expected F1-S2, got " + t.spotId());
        });

        test("A free-pricing rule (fee 0) plugs in without editing existing classes", () -> {
            PricingStrategy firstHalfHourFree = (type, parked) ->
                    parked.toMinutes() <= 30 ? 0 : pricing.fee(type, parked);
            ParkingLot lot = new ParkingLot(clock, List.of(new ParkingFloor(1, List.of(
                    new ParkingSlot("F1-M1", 1, 1, SlotSize.MEDIUM)))), firstHalfHourFree, allocation);
            Ticket t = lot.parkVehicle(new Vehicle("CAR-FREE", VehicleType.CAR), "Gate-1");
            clock.advance(Duration.ofMinutes(20));
            check(lot.unpark(t.ticketId(), "Gate-1").fee() == 0, "expected fee 0");
        });

        test("Strategy returning Optional.empty() -> lot raises ParkingFullException", () -> {
            SlotAllocationStrategy never = (floors, vehicle) -> Optional.empty();
            ParkingLot lot = new ParkingLot(clock, List.of(new ParkingFloor(1, List.of(
                    new ParkingSlot("F1-M1", 1, 1, SlotSize.MEDIUM)))), pricing, never);
            expectThrows(ParkingFullException.class,
                    () -> lot.parkVehicle(new Vehicle("CAR-E", VehicleType.CAR), "Gate-1"));
        });

        test("Floor/lot construction rejects bad layouts", () -> {
            expectThrows(IllegalArgumentException.class, () -> new ParkingFloor(1, List.of(
                    new ParkingSlot("F2-S1", 2, 1, SlotSize.SMALL))));                       // wrong floor
            expectThrows(IllegalArgumentException.class, () -> new ParkingFloor(1, List.of(
                    new ParkingSlot("F1-S1", 1, 1, SlotSize.SMALL),
                    new ParkingSlot("F1-S1", 1, 2, SlotSize.SMALL))));                       // duplicate id
            ParkingFloor a = new ParkingFloor(1, List.of(new ParkingSlot("X", 1, 1, SlotSize.SMALL)));
            ParkingFloor b = new ParkingFloor(2, List.of(new ParkingSlot("X", 2, 1, SlotSize.SMALL)));
            expectThrows(IllegalArgumentException.class,
                    () -> new ParkingLot(clock, List.of(a, b), pricing, allocation));          // duplicate id across floors
        });

        test("All errors share the ParkingException base class", () -> {
            check(new ParkingSlotOccupiedException("s") instanceof ParkingException, "slot exceptions");
            check(new TicketNotFoundException("t") instanceof ParkingException, "lot exceptions");
        });

        System.out.println("\nAll " + passed + " Parking Lot tests passed.");
    }

    // ---- tiny test helpers (always run; no -ea flag needed) ----
    private static int smallFree(ParkingLot lot) {
        return lot.getAvailability().get(1).get(SlotSize.SMALL);
    }

    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS  " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }
}