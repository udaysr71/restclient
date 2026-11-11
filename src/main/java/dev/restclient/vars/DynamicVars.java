package dev.restclient.vars;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Built-in variables whose value is made up each time a request is sent: {@code {{$guid}}},
 * {@code {{$timestamp}}} and friends. The names match Postman's, so imported requests keep working.
 * Every occurrence gets its own value.
 */
public final class DynamicVars {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private record Def(String description, Supplier<String> make) {
    }

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        DEFS.put("$guid", new Def("A random UUID, new for every request", () -> UUID.randomUUID().toString()));
        DEFS.put("$randomUUID", new Def("A random UUID, new for every request", () -> UUID.randomUUID().toString()));
        DEFS.put("$timestamp", new Def("The current time in seconds since 1970",
                () -> Long.toString(System.currentTimeMillis() / 1000)));
        DEFS.put("$isoTimestamp", new Def("The current time in ISO 8601 form, in UTC",
                () -> Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()));
        DEFS.put("$randomInt", new Def("A random whole number from 0 to 1000", () -> Integer.toString(RANDOM.nextInt(1001))));
        DEFS.put("$randomBoolean", new Def("true or false, at random", () -> Boolean.toString(RANDOM.nextBoolean())));
        DEFS.put("$randomAlphaNumeric", new Def("One random letter or digit",
                () -> String.valueOf(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())))));
    }

    private DynamicVars() {
    }

    public static boolean isDynamic(String name) {
        return name != null && DEFS.containsKey(name);
    }

    /** The built-in names, in a sensible reading order. */
    public static Set<String> names() {
        return DEFS.keySet();
    }

    /** What the variable produces, for hover cards. Null for an unknown name. */
    public static String describe(String name) {
        Def d = DEFS.get(name);
        return d == null ? null : d.description;
    }

    /** A fresh value, or null for an unknown name. */
    public static String generate(String name) {
        Def d = DEFS.get(name);
        return d == null ? null : d.make.get();
    }
}
