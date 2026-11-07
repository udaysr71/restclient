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
 *   <li>the active environment</li>
 *   <li>the collection the request belongs to</li>
 *   <li>globals</li>
 * </ol>
 * Disabled variables are ignored. Values may themselves contain variables (resolved up to a fixed depth).
 */
public final class VariableResolver {
    public enum Scope {
        ENVIRONMENT("environment"), COLLECTION("collection"), GLOBAL("global");

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

    public static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-$]+)\\s*}}");
    public static final String MASK = "••••••••";
    private static final int MAX_DEPTH = 5;

    private record Layer(Scope scope, String sourceName, List<Variable> vars) {
    }

    private final List<Layer> layers = new ArrayList<>();
    private final String environmentName;

    public VariableResolver(Environment environment, ApiCollection collection, Environment globals) {
        if (environment != null) layers.add(new Layer(Scope.ENVIRONMENT, environment.name, environment.variables));
        if (collection != null) layers.add(new Layer(Scope.COLLECTION, collection.name, collection.variables));
        if (globals != null) layers.add(new Layer(Scope.GLOBAL, "Globals", globals.variables));
        this.environmentName = environment == null ? null : environment.name;
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
        if (name == null) return out;
        for (Layer l : layers) {
            for (Variable v : l.vars) {
                if (v.enabled && name.equals(v.key)) {
                    out.add(new Resolution(name, v.value == null ? "" : v.value, l.scope, l.sourceName, v.secret));
                    break;
                }
            }
        }
        return out;
    }

    public boolean isDefined(String name) {
        return lookup(name).isPresent();
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
            Optional<Resolution> r = lookup(m.group(1));
            String replacement;
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
        for (Token t : tokens(resolve(text))) out.add(t.name());
        return out;
    }

    /** All variable names available for autocomplete. */
    public Set<String> names() {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Layer l : layers) {
            for (Variable v : l.vars) if (v.enabled && v.key != null && !v.key.isBlank()) out.add(v.key);
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
