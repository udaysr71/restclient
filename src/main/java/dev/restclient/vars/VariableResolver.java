package dev.restclient.vars;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.Environment;
import dev.restclient.model.Variable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{name}}} placeholders. Lookup order, highest first:
 * <ol>
 *   <li>the request's own local variables, when there are any ({@link #withLocals})</li>
 *   <li>the active environment</li>
 *   <li>the collection the request belongs to</li>
 *   <li>globals</li>
 * </ol>
 * Disabled variables are ignored. Values may themselves contain variables (resolved up to a fixed depth).
 *
 * <p>Names that start with {@code $} are the built-in {@link DynamicVars}: they can't be defined or overridden,
 * and every occurrence gets a fresh value each time it's resolved. {@link #recording()} and {@link #replaying()}
 * let the same generated values be used twice, for the request that is sent and the copy kept for display.
 */
public final class VariableResolver {
    public enum Scope {
        ENVIRONMENT("environment"), COLLECTION("collection"), GLOBAL("global"),
        /** A request's own local variables: they win over every other scope, for that request only. */
        REQUEST("request");

        public final String label;

        Scope(String label) {
            this.label = label;
        }
    }

    /** Where a variable's value comes from. */
    public record Resolution(String name, String value, Scope scope, String sourceName, boolean secret) {
    }

    /** A {@code {{name}}} occurrence in a string; {@code end} is exclusive. */
    public record Token(int start, int end, String name) {
    }

    /**
     * What a variable name may be: letters and digits (of any alphabet), {@code _ . - $}, and spaces between
     * words ("base url", "API Key"). Spaces around the name inside the braces aren't part of it.
     */
    public static final String NAME = "[\\p{L}\\p{N}_.\\-$]+(?: +[\\p{L}\\p{N}_.\\-$]+)*";
    public static final Pattern VAR = Pattern.compile("\\{\\{\\s*(" + NAME + ")\\s*}}");
    private static final Pattern USER_NAME = Pattern.compile(NAME);

    /** True when {@code name} can be a variable of the user's own: a valid name that isn't a {@code $built-in}. */
    public static boolean isUserName(String name) {
        return name != null && name.length() <= 128 && !name.contains("$") && USER_NAME.matcher(name).matches();
    }
    public static final String MASK = "••••••••";
    private static final int MAX_DEPTH = 5;

    private record Layer(Scope scope, String sourceName, List<Variable> vars) {
    }

    private final List<Layer> layers = new ArrayList<>();
    private final String environmentName;
    /** Generated dynamic values, in order: appended to while recording, read back while replaying. */
    private List<String> tape;
    private int tapePosition = -1; // -1 = not replaying

    public VariableResolver(Environment environment, ApiCollection collection, Environment globals) {
        if (environment != null) layers.add(new Layer(Scope.ENVIRONMENT, environment.name, environment.variables));
        if (collection != null) layers.add(new Layer(Scope.COLLECTION, collection.name, collection.variables));
        if (globals != null) layers.add(new Layer(Scope.GLOBAL, "Globals", globals.variables));
        this.environmentName = environment == null ? null : environment.name;
    }

    private VariableResolver(VariableResolver from) {
        layers.addAll(from.layers);
        environmentName = from.environmentName;
    }

    /**
     * A copy in which a request's local variables come first, ahead of the environment. Rows that are switched
     * off, or whose name can't be a variable name, are left out.
     */
    public VariableResolver withLocals(List<dev.restclient.model.KeyValue> locals) {
        List<Variable> vars = new ArrayList<>();
        if (locals != null) {
            for (dev.restclient.model.KeyValue kv : locals) {
                if (!kv.enabled || kv.key == null) continue;
                String name = kv.key.trim();
                if (isUserName(name)) vars.add(new Variable(name, kv.value == null ? "" : kv.value));
            }
        }
        if (vars.isEmpty()) return this;
        VariableResolver r = new VariableResolver(this);
        r.layers.add(0, new Layer(Scope.REQUEST, "This request", vars));
        return r;
    }

    /** A copy that remembers every dynamic value it hands out. */
    public VariableResolver recording() {
        VariableResolver r = new VariableResolver(this);
        r.tape = new ArrayList<>();
        return r;
    }

    /**
     * A copy that hands out the values this (recording) resolver generated, in the same order, so resolving the
     * same texts in the same order gives the same result. Once they run out, values are fresh again.
     */
    public VariableResolver replaying() {
        VariableResolver r = new VariableResolver(this);
        r.tape = tape == null ? new ArrayList<>() : new ArrayList<>(tape);
        r.tapePosition = 0;
        return r;
    }

    private String dynamic(String name) {
        if (tape != null && tapePosition >= 0) {
            // names aren't checked: the caller resolves the same texts in the same order
            if (tapePosition < tape.size()) return tape.get(tapePosition++);
            return DynamicVars.generate(name);
        }
        String v = DynamicVars.generate(name);
        if (tape != null) tape.add(v);
        return v;
    }

    public static VariableResolver empty() {
        return new VariableResolver(null, null, null);
    }

    /** Name of the active environment, or null when none is selected. */
    public String environmentName() {
        return environmentName;
    }

    /** The winning definition for a name. */
    public Optional<Resolution> lookup(String name) {
        List<Resolution> all = lookupAll(name);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Every scope that defines the name, in precedence order (the first one wins). */
    public List<Resolution> lookupAll(String name) {
        List<Resolution> out = new ArrayList<>();
        if (name == null || DynamicVars.isDynamic(name)) return out;
        for (Layer l : layers) {
            for (Variable v : l.vars) {
                if (v.enabled && v.key != null && name.equals(v.key.trim())) { // a stray space typed after a name doesn't hide it
                    out.add(new Resolution(name, v.value == null ? "" : v.value, l.scope, l.sourceName, v.secret));
                    break;
                }
            }
        }
        return out;
    }

    /** True for user-defined variables and for the built-in dynamic ones. */
    public boolean isDefined(String name) {
        return DynamicVars.isDynamic(name) || lookup(name).isPresent();
    }

    /** Replaces every defined variable; undefined ones are left as {@code {{name}}}. */
    public String resolve(String text) {
        return resolve(text, false);
    }

    /** Like {@link #resolve(String)} but secret values become {@link #MASK} (for history and display). */
    public String resolveMasked(String text) {
        return resolve(text, true);
    }

    private String resolve(String text, boolean mask) {
        if (text == null || text.indexOf("{{") < 0) return text;
        String current = text;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String next = resolveOnce(current, mask);
            if (next.equals(current)) break;
            current = next;
        }
        return current;
    }

    private String resolveOnce(String text, boolean mask) {
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (DynamicVars.isDynamic(m.group(1))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(dynamic(m.group(1))));
                continue;
            }
            Optional<Resolution> r = lookup(m.group(1));
            if (r.isEmpty()) {
                replacement = m.group(0);
            } else if (mask && r.get().secret()) {
                replacement = MASK;
            } else {
                replacement = r.get().value();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Names used in the text that no scope defines (after resolving nested values). */
    public Set<String> unresolved(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (Token t : tokens(new VariableResolver(this).resolve(text))) out.add(t.name());
        return out;
    }

    /** All variable names available for autocomplete. */
    public Set<String> names() {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Layer l : layers) {
            for (Variable v : l.vars) if (v.enabled && v.key != null && !v.key.isBlank()) out.add(v.key.trim());
        }
        return out;
    }

    public static List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = VAR.matcher(text);
        while (m.find()) out.add(new Token(m.start(), m.end(), m.group(1)));
        return out;
    }

    public static boolean references(String text, String name) {
        for (Token t : tokens(text)) if (t.name().equals(name)) return true;
        return false;
    }
}
