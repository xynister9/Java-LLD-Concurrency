package vendingmachine;

import java.util.*;

// ============================================================================
// 1. Data entities
// ============================================================================

record Product(String name, int price) {
    Product {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank())
            throw new IllegalArgumentException("name must not be blank");
        if (price <= 0)
            throw new IllegalArgumentException("price must be > 0");
    }
}

/** Product dispensed + exact coins returned (empty map = exact payment). */
record PurchaseResult(Product product, Map<Denomination, Integer> change) {
    PurchaseResult {
        Objects.requireNonNull(product);
        change = CashBox.immutableCopy(change);
    }
}

enum Denomination {
    ONE(1), TWO(2), FIVE(5), TEN(10), TWENTY(20), FIFTY(50), HUNDRED(100);

    private final int value;

    Denomination(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

// ============================================================================
// 2. Exceptions (one base type, one subtype per failure reason)
// ============================================================================

class VendingException extends RuntimeException {
    VendingException(String message) {
        super(message);
    }
}

class InvalidSlotException extends VendingException {
    InvalidSlotException(String slotId) {
        super("No slot with id: " + slotId);
    }
}

class OutOfStockException extends VendingException {
    OutOfStockException(String slotId) {
        super("Slot " + slotId + " is empty, choose another slot");
    }
}

class InsufficientFundsException extends VendingException {
    private final int shortfall;

    InsufficientFundsException(int shortfall) {
        super("Balance not enough, add " + shortfall + " more");
        this.shortfall = shortfall;
    }

    public int getShortfall() {
        return shortfall;
    }
}

class CannotMakeChangeException extends VendingException {
    CannotMakeChangeException(int amount) {
        super("Cannot return change of " + amount + ", insert exact money or cancel the transaction");
    }
}

class InvalidStateOperationException extends VendingException {
    InvalidStateOperationException(String message) {
        super(message);
    }
}

// ============================================================================
// 3. Slot
// ============================================================================

class Slot {
    private final String slotId;
    private Product product;
    private int quantity;

    Slot(String slotId, Product product, int quantity) {
        Objects.requireNonNull(slotId, "slotId must not be null");
        Objects.requireNonNull(product, "product must not be null");
        if (quantity < 0)
            throw new IllegalArgumentException("quantity must be >= 0");
        this.slotId = slotId;
        this.product = product;
        this.quantity = quantity;
    }

    public String getSlotId() {
        return slotId;
    }

    public Product getProduct() {
        return product;
    }

    public int getRemainingQuantity() {
        return quantity;
    }

    void refill(int count) {
        if (count <= 0)
            throw new IllegalArgumentException("refill count must be > 0");
        quantity += count;
    }

    void replace(Product newProduct, int newQuantity) {
        Objects.requireNonNull(newProduct, "product must not be null");
        if (newQuantity < 0)
            throw new IllegalArgumentException("quantity must be >= 0");
        product = newProduct;
        quantity = newQuantity;
    }

    Product releaseItem() {
        if (quantity == 0)
            throw new OutOfStockException(slotId);
        quantity--;
        return product;
    }
}

// ============================================================================
// 4. CashBox: counts per denomination. Used for BOTH the machine's float and
// the customer's escrow (inserted-but-not-yet-spent money).
// ============================================================================

class CashBox {
    private final Map<Denomination, Integer> coins = new HashMap<>();

    void deposit(Denomination d) {
        coins.put(d, coins.getOrDefault(d, 0) + 1);
    }

    void deposit(Collection<Denomination> money) {
        money.forEach(this::deposit);
    }

    void deposit(Map<Denomination, Integer> money) {
        money.forEach((d, n) -> coins.put(d, coins.getOrDefault(d, 0) + n));
    }

    void withdraw(Map<Denomination, Integer> money) {
        // Validate everything first so a failed withdraw changes nothing.
        money.forEach((d, n) -> {
            if (coins.getOrDefault(d, 0) < n) {
                throw new IllegalStateException("Not enough " + d + " to withdraw " + n);
            }
        });
        money.forEach((d, n) -> {
            int left = coins.get(d) - n;
            if (left == 0)
                coins.remove(d);
            else
                coins.put(d, left);
        });
    }

    /** Empties the box and returns everything that was in it. */
    Map<Denomination, Integer> collectAll() {
        Map<Denomination, Integer> all = immutableCopy(coins);
        coins.clear();
        return all;
    }

    /** Read-only copy of the current contents (does not empty the box). */
    Map<Denomination, Integer> snapshot() {
        return immutableCopy(coins);
    }

    int total() {
        int sum = 0;
        for (Map.Entry<Denomination, Integer> e : coins.entrySet()) {
            sum += e.getKey().getValue() * e.getValue();
        }
        return sum;
    }

    /**
     * Pure calculation (mutates nothing): finds coins worth exactly {@code amount}
     * using this box's coins plus {@code extra} (e.g. the customer's escrow).
     *
     * <p>
     * Searches over denominations (largest first, largest count first), so it
     * returns the greedy answer whenever greedy works, and still finds an answer
     * when greedy fails under limited stock (e.g. 6 from {5x1, 2x3} -> 2+2+2).
     * Failed (denominationIndex, amount) pairs are memoized, bounding the work to
     * O(denominations x amount x maxCount) -> no exponential blow-up.
     */
    Optional<Map<Denomination, Integer>> makeChange(int amount, Map<Denomination, Integer> extra) {
        if (amount < 0)
            throw new IllegalArgumentException("amount must be >= 0");
        Map<Denomination, Integer> available = new HashMap<>(coins);
        extra.forEach((d, n) -> available.put(d, available.getOrDefault(d, 0) + n));

        Denomination[] ds = Denomination.values(); // ascending by value
        boolean[][] failed = new boolean[ds.length][amount + 1];
        Map<Denomination, Integer> out = new HashMap<>();
        return solve(ds, ds.length - 1, amount, available, failed, out)
                ? Optional.of(immutableCopy(out))
                : Optional.empty();
    }

    private static boolean solve(Denomination[] ds, int i, int amount,
            Map<Denomination, Integer> available,
            boolean[][] failed, Map<Denomination, Integer> out) {
        if (amount == 0)
            return true;
        if (i < 0 || failed[i][amount])
            return false;

        Denomination d = ds[i];
        int max = Math.min(amount / d.getValue(), available.getOrDefault(d, 0));
        for (int take = max; take >= 0; take--) {
            if (solve(ds, i - 1, amount - take * d.getValue(), available, failed, out)) {
                if (take > 0)
                    out.put(d, take);
                return true;
            }
        }
        failed[i][amount] = true;
        return false;
    }

    /**
     * Immutable, denomination-ordered copy (TreeMap sorts enum keys in declaration
     * order).
     */
    static Map<Denomination, Integer> immutableCopy(Map<Denomination, Integer> src) {
        return Collections.unmodifiableMap(new TreeMap<>(src));
    }
}

// ============================================================================
// 5. States (stateless -> one shared instance each)
// ============================================================================

interface VmState {
    void insertMoney(VendingMachine vm, List<Denomination> money);

    PurchaseResult selectProduct(VendingMachine vm, String slotId);

    Map<Denomination, Integer> cancelTransaction(VendingMachine vm);

    void restock(VendingMachine vm, String slotId, int quantity);

    void changeProduct(VendingMachine vm, String slotId, Product product, int quantity);

    void addChange(VendingMachine vm, List<Denomination> money);

    Map<Denomination, Integer> collectCash(VendingMachine vm);
}

final class VmStates {
    static final VmState IDLE = new IdleState();
    static final VmState IN_TRANSACTION = new TransactionState();

    private VmStates() {
    }
}

class IdleState implements VmState {
    @Override
    public void insertMoney(VendingMachine vm, List<Denomination> money) {
        vm.escrow().deposit(money);
        vm.setState(VmStates.IN_TRANSACTION);
    }

    @Override
    public PurchaseResult selectProduct(VendingMachine vm, String slotId) {
        throw new InvalidStateOperationException("Insert money first");
    }

    @Override
    public Map<Denomination, Integer> cancelTransaction(VendingMachine vm) {
        throw new InvalidStateOperationException("No transaction to cancel");
    }

    @Override
    public void restock(VendingMachine vm, String slotId, int quantity) {
        vm.slot(slotId).refill(quantity);
    }

    @Override
    public void changeProduct(VendingMachine vm, String slotId, Product product, int quantity) {
        vm.slot(slotId).replace(product, quantity);
    }

    @Override
    public void addChange(VendingMachine vm, List<Denomination> money) {
        vm.cashBox().deposit(money);
    }

    @Override
    public Map<Denomination, Integer> collectCash(VendingMachine vm) {
        return vm.cashBox().collectAll();
    }
}

class TransactionState implements VmState {
    @Override
    public void insertMoney(VendingMachine vm, List<Denomination> money) {
        vm.escrow().deposit(money);
    }

    @Override
    public PurchaseResult selectProduct(VendingMachine vm, String slotId) {
        // 1-4: validate everything; nothing is mutated until all checks pass.
        Slot slot = vm.slot(slotId); // InvalidSlotException
        if (slot.getRemainingQuantity() == 0)
            throw new OutOfStockException(slotId);

        int price = slot.getProduct().price();
        int paid = vm.escrow().total();
        if (paid < price)
            throw new InsufficientFundsException(price - paid);

        int changeDue = paid - price;
        Map<Denomination, Integer> change = vm.cashBox()
                .makeChange(changeDue, vm.escrow().snapshot()) // stock + customer's own coins
                .orElseThrow(() -> new CannotMakeChangeException(changeDue));

        // 5: commit all at once.
        vm.cashBox().deposit(vm.escrow().collectAll()); // inserted coins join stock first
        vm.cashBox().withdraw(change); // then change leaves
        Product product = slot.releaseItem();
        vm.setState(VmStates.IDLE);
        return new PurchaseResult(product, change);
    }

    @Override
    public Map<Denomination, Integer> cancelTransaction(VendingMachine vm) {
        Map<Denomination, Integer> refund = vm.escrow().collectAll(); // exact coins back, can't fail
        vm.setState(VmStates.IDLE);
        return refund;
    }

    @Override
    public void restock(VendingMachine vm, String slotId, int quantity) {
        throw new InvalidStateOperationException("Restock not allowed during a transaction");
    }

    @Override
    public void changeProduct(VendingMachine vm, String slotId, Product product, int quantity) {
        throw new InvalidStateOperationException("Change product not allowed during a transaction");
    }

    @Override
    public void addChange(VendingMachine vm, List<Denomination> money) {
        throw new InvalidStateOperationException("Add change not allowed during a transaction");
    }

    @Override
    public Map<Denomination, Integer> collectCash(VendingMachine vm) {
        throw new InvalidStateOperationException("Collect cash not allowed during a transaction");
    }
}

// ============================================================================
// 6. VendingMachine: the State-pattern context.
// public = customer + operator API (always goes through the state)
// package-private = helpers for the states only
// ============================================================================

class VendingMachine {
    private final Map<String, Slot> slots = new HashMap<>();
    private final CashBox cashBox; // machine's money
    private final CashBox escrow; // current customer's inserted money
    private VmState state;

    VendingMachine(List<Slot> slotList) {
        Objects.requireNonNull(slotList, "slots must not be null");
        if (slotList.isEmpty())
            throw new IllegalArgumentException("machine needs at least one slot");
        for (Slot s : slotList) {
            if (slots.putIfAbsent(s.getSlotId(), s) != null) {
                throw new IllegalArgumentException("duplicate slot id: " + s.getSlotId());
            }
        }
        this.cashBox = new CashBox();
        this.escrow = new CashBox();
        this.state = VmStates.IDLE;
    }

    // ---- Customer API ----
    public void insertMoney(List<Denomination> money) {
        state.insertMoney(this, validMoney(money));
    }

    public PurchaseResult selectProduct(String slotId) {
        return state.selectProduct(this, slotId);
    }

    public Map<Denomination, Integer> cancelTransaction() {
        return state.cancelTransaction(this);
    }

    public int balanceAmount() {
        return escrow.total();
    }

    // ---- Operator API ----
    public void restock(String slotId, int quantity) {
        state.restock(this, slotId, quantity);
    }

    public void changeProduct(String slotId, Product product, int quantity) {
        state.changeProduct(this, slotId, product, quantity);
    }

    public void addChange(List<Denomination> money) {
        state.addChange(this, validMoney(money));
    }

    public Map<Denomination, Integer> collectCash() {
        return state.collectCash(this);
    }

    // ---- Helpers for states (package-private) ----
    void setState(VmState newState) {
        state = newState;
    }

    CashBox cashBox() {
        return cashBox;
    }

    CashBox escrow() {
        return escrow;
    }

    Slot slot(String slotId) {
        Slot s = slots.get(slotId);
        if (s == null)
            throw new InvalidSlotException(slotId);
        return s;
    }

    private static List<Denomination> validMoney(List<Denomination> money) {
        Objects.requireNonNull(money, "money must not be null");
        List<Denomination> copy = List.copyOf(money); // also throws NPE if any element is null
        if (copy.isEmpty())
            throw new IllegalArgumentException("insert at least one coin/note");
        return copy;
    }
}

// ============================================================================
// 7. Test driver: every test ASSERTS the result, not just prints it.
// ============================================================================

public class VMachine {
    private static int passed = 0;

    public static void main(String[] args) {
        System.out.println("\n---- Vending Machine Test Suite ----\n");

        VendingMachine vm = new VendingMachine(List.of(
                new Slot("A1", new Product("Coke", 40), 3),
                new Slot("A2", new Product("Pepsi", 45), 1)));
        vm.addChange(List.of(Denomination.TEN, Denomination.TEN, Denomination.TEN,
                Denomination.TEN, Denomination.TEN, Denomination.TWENTY));

        test("1. Exact payment -> no change", () -> {
            vm.insertMoney(List.of(Denomination.TWENTY, Denomination.TWENTY));
            PurchaseResult r = vm.selectProduct("A1");
            check(r.product().name().equals("Coke"), "expected Coke, got " + r.product());
            check(r.change().isEmpty(), "expected no change, got " + r.change());
        });

        test("2. Pay 50 for 40 -> change {TEN=1}", () -> {
            vm.insertMoney(List.of(Denomination.FIFTY));
            PurchaseResult r = vm.selectProduct("A1");
            check(r.change().equals(Map.of(Denomination.TEN, 1)), "expected {TEN=1}, got " + r.change());
        });

        test("3. Insufficient balance -> shortfall 20, balance kept, refund on cancel", () -> {
            vm.insertMoney(List.of(Denomination.TWENTY));
            InsufficientFundsException e = expectThrows(InsufficientFundsException.class,
                    () -> vm.selectProduct("A1"));
            check(e.getShortfall() == 20, "expected shortfall 20, got " + e.getShortfall());
            check(vm.balanceAmount() == 20, "balance must be kept, got " + vm.balanceAmount());
            Map<Denomination, Integer> refund = vm.cancelTransaction();
            check(refund.equals(Map.of(Denomination.TWENTY, 1)), "expected refund {TWENTY=1}, got " + refund);
        });

        test("4. Buy last item, then OutOfStock, cancel, restock", () -> {
            vm.insertMoney(List.of(Denomination.FIFTY));
            check(vm.selectProduct("A1").change().equals(Map.of(Denomination.TEN, 1)), "expected {TEN=1}");
            vm.insertMoney(List.of(Denomination.FIFTY));
            expectThrows(OutOfStockException.class, () -> vm.selectProduct("A1"));
            check(vm.cancelTransaction().equals(Map.of(Denomination.FIFTY, 1)), "expected refund {FIFTY=1}");
            vm.restock("A1", 5);
        });

        test("5. Operator action during transaction is rejected", () -> {
            vm.insertMoney(List.of(Denomination.TEN));
            expectThrows(InvalidStateOperationException.class, () -> vm.restock("A2", 10));
            expectThrows(InvalidStateOperationException.class, vm::collectCash);
            vm.cancelTransaction();
        });

        test("6. Customer's own coins usable as change (empty float)", () -> {
            VendingMachine fresh = new VendingMachine(List.of(new Slot("B1", new Product("Chips", 30), 1)));
            fresh.insertMoney(List.of(Denomination.TWENTY, Denomination.TEN, Denomination.TEN));
            PurchaseResult r = fresh.selectProduct("B1");
            check(r.change().equals(Map.of(Denomination.TEN, 1)), "expected {TEN=1}, got " + r.change());
        });

        test("7. Limited stock where greedy fails: 6 from {FIVE x1, TWO x3} -> {TWO=3}", () -> {
            VendingMachine m = new VendingMachine(List.of(new Slot("C1", new Product("Gum", 4), 1)));
            m.addChange(List.of(Denomination.FIVE, Denomination.TWO, Denomination.TWO, Denomination.TWO));
            m.insertMoney(List.of(Denomination.TEN));
            PurchaseResult r = m.selectProduct("C1");
            check(r.change().equals(Map.of(Denomination.TWO, 3)), "expected {TWO=3}, got " + r.change());
        });

        test("8. Impossible change fails fast (50 x TWO, change 99) and changes nothing", () -> {
            VendingMachine m = new VendingMachine(List.of(new Slot("D1", new Product("Mint", 1), 1)));
            m.addChange(Collections.nCopies(50, Denomination.TWO));
            m.insertMoney(List.of(Denomination.HUNDRED));
            long start = System.nanoTime();
            expectThrows(CannotMakeChangeException.class, () -> m.selectProduct("D1"));
            long ms = (System.nanoTime() - start) / 1_000_000;
            check(ms < 1000, "change search took " + ms + " ms");
            check(m.balanceAmount() == 100, "balance must be untouched");
            check(m.cancelTransaction().equals(Map.of(Denomination.HUNDRED, 1)), "expected refund {HUNDRED=1}");
        });

        test("9. Invalid inputs / wrong-state actions are rejected", () -> {
            expectThrows(IllegalArgumentException.class, () -> vm.insertMoney(List.of()));
            expectThrows(InvalidStateOperationException.class, () -> vm.selectProduct("A1"));
            expectThrows(InvalidStateOperationException.class, vm::cancelTransaction);
            vm.insertMoney(List.of(Denomination.TEN));
            expectThrows(InvalidSlotException.class, () -> vm.selectProduct("Z9"));
            vm.cancelTransaction();
            expectThrows(IllegalArgumentException.class, () -> new Product("Bad", 0));
        });

        System.out.println("\nAll " + passed + " tests passed.");
    }

    // ---- tiny test helpers ----
    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS  " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t))
                return type.cast(t);
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
