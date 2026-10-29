package mcbot.dev.test;

import java.util.ArrayList;
import java.util.List;

/**
 * A very small test harness.
 *
 * <p>No JUnit: the bot ships as a single jar with no test dependencies, and the
 * point of these tests is that anyone can run them on a machine with nothing but
 * a JDK. Each test prints one line, a failure prints what was expected and what
 * happened, and the process exits non-zero when anything failed.
 *
 * <p>The suites are deliberately behavioural rather than "does it compile":
 * break times against vanilla numbers, loot tables against the server's own
 * data, the planner against a real inventory, and physics against an integrated
 * fall.
 */
public final class TestRunner {
    public interface Body {
        void run() throws Exception;
    }

    private static final List<String> FAILURES = new ArrayList<>();
    private static String suite = "?";
    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    private TestRunner() {}

    public static void suite(String name) {
        suite = name;
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    public static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionError e) {
            failed++;
            String message = e.getMessage() == null ? "(no detail)" : e.getMessage();
            FAILURES.add(suite + " / " + name + ": " + message);
            System.out.println("  FAIL  " + name + "  --  " + message);
        } catch (Throwable t) {
            failed++;
            String message = t.getClass().getSimpleName() + ": " + t.getMessage();
            FAILURES.add(suite + " / " + name + ": " + message);
            System.out.println("  ERROR " + name + "  --  " + message);
        }
    }

    public static void skip(String name, String why) {
        skipped++;
        System.out.println("  SKIP  " + name + "  --  " + why);
    }

    // ------------------------------------------------------------- assertions --

    public static void check(String what, boolean condition) {
        if (!condition) throw new AssertionError(what);
    }

    public static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    public static void near(String what, double expected, double actual, double epsilon) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(what + ": expected " + expected + " +/- " + epsilon
                    + ", got " + actual);
        }
    }

    public static void notNull(String what, Object value) {
        if (value == null) throw new AssertionError(what + ": was null");
    }

    public static void contains(String what, String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(what + ": \"" + haystack + "\" does not contain \"" + needle + "\"");
        }
    }

    public static void atLeast(String what, int minimum, int actual) {
        if (actual < minimum) {
            throw new AssertionError(what + ": expected at least " + minimum + ", got " + actual);
        }
    }

    public static void fail(String what) {
        throw new AssertionError(what);
    }

    // ---------------------------------------------------------------- summary --

    /** Prints the totals and returns a process exit code. */
    public static int summary() {
        System.out.println();
        System.out.println("--------------------------------------------------");
        System.out.println("tests: " + (passed + failed) + "   passed: " + passed
                + "   failed: " + failed + "   skipped: " + skipped);
        if (!FAILURES.isEmpty()) {
            System.out.println("failures:");
            for (String f : FAILURES) System.out.println("  - " + f);
        }
        System.out.println("--------------------------------------------------");
        return failed == 0 ? 0 : 1;
    }

    public static int passed() { return passed; }
    public static int failed() { return failed; }
    public static int skipped() { return skipped; }
    public static List<String> failures() { return FAILURES; }
}
