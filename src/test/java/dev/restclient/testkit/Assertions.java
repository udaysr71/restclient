package dev.restclient.testkit;

import java.util.Objects;

/** The handful of assertions the tests use. Failures throw {@link AssertionError}. */
public final class Assertions {
    /** Code expected to throw. */
    @FunctionalInterface
    public interface Executable {
        void execute() throws Throwable;
    }

    private Assertions() {
    }

    private static AssertionError fail(String message, String detail) {
        return new AssertionError((message == null ? "" : message + " ==> ") + detail);
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw fail(message, "expected: <" + expected + "> but was: <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) throw fail(message, "expected: <" + expected + "> but was: <" + actual + ">");
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, null);
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) throw fail(message, "expected: <true> but was: <false>");
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, null);
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) throw fail(message, "expected: <false> but was: <true>");
    }

    public static void assertNull(Object value) {
        if (value != null) throw fail(null, "expected: <null> but was: <" + value + ">");
    }

    public static void assertNotNull(Object value) {
        assertNotNull(value, null);
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) throw fail(message, "expected: not <null>");
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, Executable code) {
        try {
            code.execute();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            AssertionError e = fail(null, "expected " + type.getName() + " but got " + t);
            e.initCause(t);
            throw e;
        }
        throw fail(null, "expected " + type.getName() + " to be thrown, but nothing was thrown");
    }
}
