import static testkit.TestKit.*;

import java.util.*;

// ============================================================================
// 1. Value types (immutable records; ids are the identity everywhere)
// ============================================================================

record User(String id, String name, String email) {
    User {
        Checks.nonBlank(id, "id");
        Checks.nonBlank(name, "name");
        Checks.nonBlank(email, "email");
        if (!email.contains("@")) throw new IllegalArgumentException("invalid email: " + email);
    }
}

/** "from owes to amount". Always positive; zero balances are never produced. */
record Balance(String from, String to, long amount) {
    Balance {
        Checks.nonBlank(from, "from");
        Checks.nonBlank(to, "to");
        if (from.equals(to)) throw new IllegalArgumentException("a user can't owe themselves");
        if (amount <= 0) throw new IllegalArgumentException("balance amount must be > 0");
    }
}

/** Audit record of an expense. The shares are exactly what was recorded in the balances. */
record Expense(String id, String paidBy, long amount, String description, Map<String, Long> shares) {
    Expense {
        shares = Collections.unmodifiableMap(new LinkedHashMap<>(shares));
    }
}

final class Checks {
    private Checks() {}

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

// ============================================================================
// 2. Exceptions: one base class, one subclass per failure reason
// ============================================================================

class SplitwiseException extends RuntimeException {
    SplitwiseException(String message) { super(message); }
}

class InvalidSplitException extends SplitwiseException {
    InvalidSplitException(String message) { super(message); }
}

class UserNotFoundException extends SplitwiseException {
    UserNotFoundException(String id) { super("No user with id: " + id); }
}

class DuplicateUserException extends SplitwiseException {
    DuplicateUserException(String id) { super("User already exists: " + id); }
}

class UserDoesNotOweException extends SplitwiseException {
    UserDoesNotOweException(String from, String to) { super(from + " does not owe anything to " + to); }
}

class OverpaymentException extends SplitwiseException {
    OverpaymentException(long paid, long owed) { super("Settling " + paid + " but only " + owed + " is owed"); }
}

// ============================================================================
// 3. Splits (Strategy). Each split carries its own input, so a new type is one
//    new class: no registry, no switch, no edits to existing code.
// ============================================================================

interface Split {
    /** Pure: changes nothing. Returns userId -> share; the engine re-validates the result. */
    Map<String, Long> computeShares(long amount);
}

/** Split amount equally. Leftover paise go one each to the first participants, in order. */
record EqualSplit(List<String> participants) implements Split {
    EqualSplit {
        participants = SplitSupport.uniqueIds(participants);
    }

    @Override
    public Map<String, Long> computeShares(long amount) {
        int n = participants.size();
        long base = amount / n, extra = amount % n;
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) out.put(participants.get(i), base + (i < extra ? 1 : 0));
        return out;
    }
}

/** Caller states each share exactly. They must sum to the expense amount. */
record ExactSplit(Map<String, Long> amounts) implements Split {
    ExactSplit {
        amounts = SplitSupport.orderedCopy(amounts);
        for (var e : amounts.entrySet()) {
            if (e.getValue() < 0) throw new InvalidSplitException("negative share for " + e.getKey());
        }
    }

    @Override
    public Map<String, Long> computeShares(long amount) {
        long sum = 0;
        for (long s : amounts.values()) sum = Math.addExact(sum, s);
        if (sum != amount) throw new InvalidSplitException("exact shares sum to " + sum + " but amount is " + amount);
        return amounts;
    }
}

/** Whole-number percentages summing to 100. Leftover paise by largest remainder (ties: user id). */
record PercentSplit(Map<String, Integer> percents) implements Split {
    PercentSplit {
        percents = SplitSupport.orderedCopy(percents);
        long total = 0;
        for (var e : percents.entrySet()) {
            if (e.getValue() < 0 || e.getValue() > 100) {
                throw new InvalidSplitException("percent for " + e.getKey() + " must be 0..100");
            }
            total += e.getValue();
        }
        if (total != 100) throw new InvalidSplitException("percents sum to " + total + ", expected 100");
    }

    @Override
    public Map<String, Long> computeShares(long amount) {
        return SplitSupport.proportional(amount, percents);
    }
}

/** Ratios, e.g. 2:1:1. Added last, with no edits anywhere else: the open/closed proof. */
record ShareSplit(Map<String, Integer> weights) implements Split {
    ShareSplit {
        weights = SplitSupport.orderedCopy(weights);
        long total = 0;
        for (var e : weights.entrySet()) {
            if (e.getValue() < 0) throw new InvalidSplitException("negative weight for " + e.getKey());
            total += e.getValue();
        }
        if (total == 0) throw new InvalidSplitException("weights must not all be zero");
    }

    @Override
    public Map<String, Long> computeShares(long amount) {
        return SplitSupport.proportional(amount, weights);
    }
}

/** Shared helpers for split implementations. */
final class SplitSupport {
    private SplitSupport() {}

    static List<String> uniqueIds(List<String> ids) {
        Objects.requireNonNull(ids, "participants");
        List<String> copy = List.copyOf(ids);                    // also rejects null elements
        if (copy.isEmpty()) throw new InvalidSplitException("at least one participant is required");
        if (new HashSet<>(copy).size() != copy.size()) throw new InvalidSplitException("duplicate participants");
        copy.forEach(id -> Checks.nonBlank(id, "participant id"));
        return copy;
    }

    /** Immutable copy that keeps the caller's iteration order (Map.copyOf would lose it). */
    static <V> Map<String, V> orderedCopy(Map<String, V> src) {
        Objects.requireNonNull(src, "split input");
        if (src.isEmpty()) throw new InvalidSplitException("at least one participant is required");
        Map<String, V> copy = new LinkedHashMap<>();
        src.forEach((k, v) -> copy.put(Checks.nonBlank(k, "participant id"), Objects.requireNonNull(v, "value")));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * amount * weight / totalWeight for each user, rounded down, then the leftover paise go one
     * each to the largest remainders (ties by user id). Shares always sum exactly to amount.
     */
    static Map<String, Long> proportional(long amount, Map<String, Integer> weights) {
        long total = 0;
        for (int w : weights.values()) total += w;

        record Part(String user, long base, long rem) {}
        List<Part> parts = new ArrayList<>();
        long assigned = 0;
        for (var e : weights.entrySet()) {
            long product = Math.multiplyExact(amount, (long) e.getValue());
            parts.add(new Part(e.getKey(), product / total, product % total));
            assigned += product / total;
        }
        long left = amount - assigned;                            // always < parts.size()
        parts.sort(Comparator.comparingLong(Part::rem).reversed().thenComparing(Part::user));

        Map<String, Long> extra = new HashMap<>();
        for (Part p : parts) extra.put(p.user(), left-- > 0 ? 1L : 0L);

        Map<String, Long> out = new LinkedHashMap<>();            // report in the caller's order
        for (var e : weights.entrySet()) {
            Part p = parts.stream().filter(x -> x.user().equals(e.getKey())).findFirst().orElseThrow();
            out.put(e.getKey(), p.base() + extra.get(e.getKey()));
        }
        return out;
    }
}

// ============================================================================
// 4. BalanceSheet: who owes whom. ONE netted figure per pair of users.
//    The sign convention lives in exactly two methods: owes() and addDebt().
// ============================================================================

final class BalanceSheet {
    /** Unordered pair of user ids, stored with the smaller id first. */
    private record Pair(String lo, String hi) {
        static Pair of(String a, String b) {
            return a.compareTo(b) < 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }

    private final Map<Pair, Long> net = new HashMap<>();          // value > 0: lo owes hi; < 0: hi owes lo

    /** How much debtor owes creditor. Negative means creditor owes debtor. */
    long owes(String debtor, String creditor) {
        Pair p = Pair.of(debtor, creditor);
        long v = net.getOrDefault(p, 0L);
        return debtor.equals(p.lo()) ? v : -v;
    }

    /** debtor now owes creditor amt more. Netting and direction flips happen here and only here. */
    void addDebt(String debtor, String creditor, long amt) {
        if (amt <= 0 || debtor.equals(creditor)) throw new IllegalArgumentException("bad debt entry");
        Pair p = Pair.of(debtor, creditor);
        long delta = debtor.equals(p.lo()) ? amt : -amt;
        long v = net.merge(p, delta, Math::addExact);
        if (v == 0) net.remove(p);                                // zero balances are never stored
    }

    List<Balance> all() {
        List<Balance> out = new ArrayList<>();
        net.forEach((p, v) -> out.add(v > 0 ? new Balance(p.lo(), p.hi(), v) : new Balance(p.hi(), p.lo(), -v)));
        out.sort(Comparator.comparing(Balance::from).thenComparing(Balance::to));   // stable output
        return out;
    }

    List<Balance> involving(String userId) {
        return all().stream().filter(b -> b.from().equals(userId) || b.to().equals(userId)).toList();
    }

    /** userId -> (owed to them) - (they owe). Always sums to 0. */
    Map<String, Long> netPositions() {
        Map<String, Long> pos = new TreeMap<>();
        for (Balance b : all()) {
            pos.merge(b.to(), b.amount(), Math::addExact);
            pos.merge(b.from(), -b.amount(), Math::addExact);
        }
        return pos;
    }

    void clear() {
        net.clear();
    }
}

// ============================================================================
// 5. DebtSimplifier: greedy on net positions with two max-heaps.
//    At most (non-zero users - 1) payments. The true minimum is NP-hard.
// ============================================================================

final class DebtSimplifier {
    private DebtSimplifier() {}

    private record Position(String user, long amount) {}

    private static final Comparator<Position> LARGEST_FIRST =
            Comparator.comparingLong(Position::amount).reversed().thenComparing(Position::user);

    static List<Balance> simplify(Map<String, Long> netPositions) {
        PriorityQueue<Position> creditors = new PriorityQueue<>(LARGEST_FIRST);
        PriorityQueue<Position> debtors = new PriorityQueue<>(LARGEST_FIRST);
        long sum = 0;
        for (var e : netPositions.entrySet()) {
            long v = e.getValue();
            if (v > 0) creditors.add(new Position(e.getKey(), v));
            else if (v < 0) debtors.add(new Position(e.getKey(), -v));
            sum = Math.addExact(sum, v);
        }
        if (sum != 0) throw new IllegalStateException("net positions must sum to 0, got " + sum);

        List<Balance> payments = new ArrayList<>();
        while (!creditors.isEmpty()) {                            // sum == 0, so debtors empties at the same time
            Position c = creditors.poll(), d = debtors.poll();
            long pay = Math.min(c.amount(), d.amount());
            payments.add(new Balance(d.user(), c.user(), pay));
            if (c.amount() > pay) creditors.add(new Position(c.user(), c.amount() - pay));
            if (d.amount() > pay) debtors.add(new Position(d.user(), d.amount() - pay));
        }
        return payments;
    }
}

// ============================================================================
// 6. SplitwiseService: the Facade. Validate -> compute -> commit. Returns data only.
// ============================================================================

final class SplitwiseService {
    private final Map<String, User> users = new LinkedHashMap<>();
    private final BalanceSheet sheet = new BalanceSheet();
    private final List<Expense> expenses = new ArrayList<>();
    private long nextExpenseId = 1;

    public User registerUser(String id, String name, String email) {
        User user = new User(id, name, email);                    // validates fields first
        if (users.putIfAbsent(id, user) != null) throw new DuplicateUserException(id);
        return user;
    }

    public User getUser(String id) {
        return requireUser(id);
    }

    public Expense addExpense(String paidBy, long amount, Split split, String description) {
        // 1. Validate inputs
        requireUser(paidBy);
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(description, "description");

        // 2. Compute (pure), then check the rules every split must obey
        Map<String, Long> shares = split.computeShares(amount);
        Objects.requireNonNull(shares, "split returned null");
        long sum = 0;
        for (var e : shares.entrySet()) {
            requireUser(e.getKey());
            if (e.getValue() == null || e.getValue() < 0) throw new InvalidSplitException("invalid share for " + e.getKey());
            sum = Math.addExact(sum, e.getValue());
        }
        if (sum != amount) throw new InvalidSplitException("shares sum to " + sum + ", expected " + amount);

        // 3. Commit: nothing above changed any state
        shares.forEach((userId, share) -> {
            if (!userId.equals(paidBy) && share > 0) sheet.addDebt(userId, paidBy, share);
        });
        Expense expense = new Expense("E" + nextExpenseId++, paidBy, amount, description, shares);
        expenses.add(expense);
        return expense;
    }

    public void settle(String fromId, String toId, long amount) {
        requireUser(fromId);
        requireUser(toId);
        if (fromId.equals(toId)) throw new IllegalArgumentException("can't settle with yourself");
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");

        long owed = sheet.owes(fromId, toId);
        if (owed <= 0) throw new UserDoesNotOweException(fromId, toId);
        if (amount > owed) throw new OverpaymentException(amount, owed);
        sheet.addDebt(toId, fromId, amount);                      // a repayment is just the reverse debt
    }

    public List<Balance> balancesOf(String userId) {
        requireUser(userId);
        return sheet.involving(userId);
    }

    public List<Balance> allBalances() {
        return sheet.all();
    }

    public Map<String, Long> netPositions() {
        return sheet.netPositions();
    }

    /** Suggests the payments that would settle everyone. Changes nothing. */
    public List<Balance> simplifyDebts() {
        return DebtSimplifier.simplify(sheet.netPositions());
    }

    /** Replaces the stored balances with the simplified ones. Every user's net position is unchanged. */
    public List<Balance> applySimplifiedDebts() {
        List<Balance> plan = simplifyDebts();
        sheet.clear();
        plan.forEach(b -> sheet.addDebt(b.from(), b.to(), b.amount()));
        return plan;
    }

    public List<Expense> expenses() {
        return List.copyOf(expenses);
    }

    private User requireUser(String id) {
        Objects.requireNonNull(id, "user id");
        User u = users.get(id);
        if (u == null) throw new UserNotFoundException(id);
        return u;
    }
}

// ============================================================================
// 7. Tests
// ============================================================================

public class Splitwise {
    public static void main(String[] args) {
        System.out.println("\n---- Splitwise Tests ----\n");

        // ------------------------------------------------------------ splits
        test("EQUAL 10000 / 3 -> [3334, 3333, 3333] in participant order", () -> {
            Map<String, Long> s = new EqualSplit(List.of("a", "b", "c")).computeShares(10_000);
            checkEquals(List.of(3334L, 3333L, 3333L), new ArrayList<>(s.values()), "shares");
        });

        test("PERCENT 50/30/20 of 47474: largest remainder gets the leftover paisa", () -> {
            Map<String, Long> s = new PercentSplit(ordered("a", 50, "b", 30, "c", 20)).computeShares(47_474);
            // exact: 23737.0, 14242.2, 9494.8 -> floors sum to 47473, leftover 1 goes to c (.8)
            checkEquals(Map.of("a", 23_737L, "b", 14_242L, "c", 9_495L), s, "shares");
        });

        test("SHARE 2:1:1 on 1000 -> 500, 250, 250 (new class, no edits elsewhere)", () -> {
            Map<String, Long> s = new ShareSplit(ordered("a", 2, "b", 1, "c", 1)).computeShares(1000);
            checkEquals(Map.of("a", 500L, "b", 250L, "c", 250L), s, "shares");
        });

        test("Splits reject bad input at construction", () -> {
            expectThrows(InvalidSplitException.class, () -> new EqualSplit(List.of("a", "a")));
            expectThrows(InvalidSplitException.class, () -> new EqualSplit(List.of()));
            expectThrows(InvalidSplitException.class, () -> new PercentSplit(ordered("a", 50, "b", 49)));
            expectThrows(InvalidSplitException.class, () -> new ExactSplit(Map.of("a", -1L)));
            expectThrows(InvalidSplitException.class, () -> new ShareSplit(ordered("a", 0, "b", 0)));
        });

        // ------------------------------------------------------------ expenses and netting
        test("Payer inside the split: A pays 900 for A, B, C -> B and C each owe A 300", () -> {
            SplitwiseService s = service("a", "b", "c");
            s.addExpense("a", 900, new EqualSplit(List.of("a", "b", "c")), "dinner");
            checkEquals(List.of(new Balance("b", "a", 300), new Balance("c", "a", 300)), s.allBalances(), "balances");
            checkEquals(2, s.balancesOf("a").size(), "A's balances");
        });

        test("Payer outside the split: A pays 600 for B and C -> each owes A 300", () -> {
            SplitwiseService s = service("a", "b", "c");
            s.addExpense("a", 600, new EqualSplit(List.of("b", "c")), "tickets");
            checkEquals(List.of(new Balance("b", "a", 300), new Balance("c", "a", 300)), s.allBalances(), "balances");
        });

        test("Netting: B owes A 1000, then B pays 400 for A -> one balance, B owes A 600", () -> {
            SplitwiseService s = service("a", "b");
            s.addExpense("a", 1000, new EqualSplit(List.of("b")), "x");
            s.addExpense("b", 400, new EqualSplit(List.of("a")), "y");
            checkEquals(List.of(new Balance("b", "a", 600)), s.allBalances(), "balances");
        });

        test("Direction flip: B owes A 100, then B pays 300 for A -> A owes B 200", () -> {
            SplitwiseService s = service("a", "b");
            s.addExpense("a", 100, new EqualSplit(List.of("b")), "x");
            s.addExpense("b", 300, new EqualSplit(List.of("a")), "y");
            checkEquals(List.of(new Balance("a", "b", 200)), s.allBalances(), "balances");
        });

        test("Exactly cancelling debts leave no zero entry", () -> {
            SplitwiseService s = service("a", "b");
            s.addExpense("a", 500, new EqualSplit(List.of("b")), "x");
            s.addExpense("b", 500, new EqualSplit(List.of("a")), "y");
            check(s.allBalances().isEmpty(), "expected no balances, got " + s.allBalances());
        });

        test("Invalid expenses change nothing", () -> {
            SplitwiseService s = service("a", "b", "c");
            s.addExpense("a", 900, new EqualSplit(List.of("a", "b", "c")), "seed");
            List<Balance> before = s.allBalances();
            expectThrows(InvalidSplitException.class,
                    () -> s.addExpense("a", 1000, new ExactSplit(ordered("b", 500L, "c", 400L)), "sum 900"));
            expectThrows(UserNotFoundException.class,
                    () -> s.addExpense("a", 300, new EqualSplit(List.of("b", "ghost")), "unknown user"));
            expectThrows(UserNotFoundException.class,
                    () -> s.addExpense("ghost", 300, new EqualSplit(List.of("b")), "unknown payer"));
            expectThrows(IllegalArgumentException.class,
                    () -> s.addExpense("a", 0, new EqualSplit(List.of("b")), "zero"));
            checkEquals(before, s.allBalances(), "balances after rejected expenses");
            checkEquals(1, s.expenses().size(), "recorded expenses");
        });

        test("A buggy custom split is caught by the engine's central check", () -> {
            SplitwiseService s = service("a", "b");
            Split buggy = amount -> Map.of("b", amount - 1);                // loses a paisa
            expectThrows(InvalidSplitException.class, () -> s.addExpense("a", 100, buggy, "bug"));
            check(s.allBalances().isEmpty(), "nothing recorded");
        });

        // ------------------------------------------------------------ settle
        test("Settle when the debtor has the HIGHER id (v0 bug repro)", () -> {
            SplitwiseService s = service("a", "b");
            s.addExpense("a", 300, new EqualSplit(List.of("b")), "x");      // b owes a 300; b > a
            expectThrows(UserDoesNotOweException.class, () -> s.settle("a", "b", 1));   // a owes nothing
            checkEquals(List.of(new Balance("b", "a", 300)), s.allBalances(), "wrong-direction settle changed state");
            s.settle("b", "a", 100);
            checkEquals(List.of(new Balance("b", "a", 200)), s.allBalances(), "after partial settle");
            expectThrows(OverpaymentException.class, () -> s.settle("b", "a", 201));
            s.settle("b", "a", 200);
            check(s.allBalances().isEmpty(), "full settle must remove the balance");
        });

        test("Settle when the debtor has the LOWER id", () -> {
            SplitwiseService s = service("a", "b");
            s.addExpense("b", 300, new EqualSplit(List.of("a")), "x");      // a owes b 300
            expectThrows(UserDoesNotOweException.class, () -> s.settle("b", "a", 1));
            s.settle("a", "b", 300);
            check(s.allBalances().isEmpty(), "full settle must remove the balance");
        });

        test("Settle validates users and amounts", () -> {
            SplitwiseService s = service("a", "b");
            expectThrows(UserNotFoundException.class, () -> s.settle("a", "ghost", 1));
            expectThrows(IllegalArgumentException.class, () -> s.settle("a", "a", 1));
            expectThrows(IllegalArgumentException.class, () -> s.settle("a", "b", 0));
            expectThrows(UserDoesNotOweException.class, () -> s.settle("a", "b", 1));
        });

        // ------------------------------------------------------------ simplify
        test("Simplify chain: A owes B 100, B owes C 100 -> A pays C 100", () -> {
            SplitwiseService s = service("a", "b", "c");
            s.addExpense("b", 100, new EqualSplit(List.of("a")), "x");      // a owes b
            s.addExpense("c", 100, new EqualSplit(List.of("b")), "y");      // b owes c
            checkEquals(List.of(new Balance("a", "c", 100)), s.simplifyDebts(), "plan");
            checkEquals(2, s.allBalances().size(), "simplifyDebts() only suggests; it changes nothing");
        });

        test("Simplify, random: nets preserved, at most (non-zero users - 1) payments, apply works", () -> repeat(200, round -> {
            Random r = new Random(round);
            List<String> ids = List.of("u1", "u2", "u3", "u4", "u5", "u6");
            SplitwiseService s = service(ids.toArray(String[]::new));
            for (int i = 0; i < 15; i++) {
                String payer = ids.get(r.nextInt(ids.size()));
                List<String> group = new ArrayList<>(ids);
                Collections.shuffle(group, r);
                group = group.subList(0, 1 + r.nextInt(ids.size()));
                s.addExpense(payer, 1 + r.nextInt(100_000), new EqualSplit(group), "e" + i);
            }
            Map<String, Long> before = s.netPositions();
            List<Balance> plan = s.simplifyDebts();

            Map<String, Long> fromPlan = new TreeMap<>();
            for (Balance b : plan) {
                fromPlan.merge(b.to(), b.amount(), Long::sum);
                fromPlan.merge(b.from(), -b.amount(), Long::sum);
            }
            checkEquals(before, fromPlan, "net positions implied by the plan");
            long nonZero = before.values().stream().filter(v -> v != 0).count();
            check(plan.size() <= Math.max(0, nonZero - 1), plan.size() + " payments for " + nonZero + " users");

            s.applySimplifiedDebts();
            checkEquals(before, s.netPositions(), "net positions after apply");
            checkEquals(sorted(plan), s.allBalances(), "stored balances after apply");
        }));

        // ------------------------------------------------------------ users
        test("Users: duplicate id rejected; unknown id rejected; bad fields rejected", () -> {
            SplitwiseService s = service("a");
            expectThrows(DuplicateUserException.class, () -> s.registerUser("a", "A2", "a2@x.com"));
            expectThrows(UserNotFoundException.class, () -> s.getUser("zzz"));
            expectThrows(IllegalArgumentException.class, () -> s.registerUser("b", " ", "b@x.com"));
            expectThrows(IllegalArgumentException.class, () -> s.registerUser("b", "B", "not-an-email"));
        });

        summary();
    }

    // ---------------------------------------------------------------- helpers
    private static SplitwiseService service(String... ids) {
        SplitwiseService s = new SplitwiseService();
        for (String id : ids) s.registerUser(id, id.toUpperCase(), id + "@example.com");
        return s;
    }

    /** LinkedHashMap from alternating key/value pairs, so test input order is explicit. */
    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> ordered(Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (V) kv[i + 1]);
        return m;
    }

    private static List<Balance> sorted(List<Balance> in) {
        List<Balance> out = new ArrayList<>(in);
        out.sort(Comparator.comparing(Balance::from).thenComparing(Balance::to));
        return out;
    }
}