package dev.restclient.json;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps plain model classes to and from JSON. A model is a class with a public no-argument
 * constructor whose public, non-static, non-transient fields are its properties.
 * <p>
 * Supported field types: {@code String}, {@code boolean/int/long/double} and their boxes, enums
 * (by name), {@code List<T>}, {@code Map<String,T>} and other models. Null fields are left out
 * when writing; unknown properties are ignored when reading, and missing ones keep their default.
 */
public final class JsonMapper {
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private JsonMapper() {
    }

    public static String toJson(Object value) {
        return Json.write(toTree(value), true);
    }

    public static <T> T fromJson(String text, Class<T> type) throws JsonException {
        return fromTree(Json.parse(text), type);
    }

    /** Deep copy through the JSON tree. */
    public static <T> T copy(T value, Class<T> type) {
        try {
            return fromTree(toTree(value), type);
        } catch (JsonException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- object -> tree --------------------------------------------------------------------

    public static Object toTree(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Json.Num) return v;
        if (v instanceof Number n) return n;
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(toTree(o));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), toTree(e.getValue()));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields(v.getClass())) {
            try {
                Object fv = f.get(v);
                if (fv != null) out.put(f.getName(), toTree(fv));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    // ---- tree -> object --------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static <T> T fromTree(Object tree, Class<T> type) throws JsonException {
        return (T) convert(tree, type, type, "$");
    }

    private static Object convert(Object v, Class<?> raw, Type generic, String path) throws JsonException {
        if (v == null) return raw.isPrimitive() ? defaultPrimitive(raw) : null;
        if (raw == String.class) {
            if (v instanceof String s) return s;
            if (v instanceof Json.Num || v instanceof Boolean) return v.toString();
            throw mismatch(path, "a string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (v instanceof Boolean b) return b;
            throw mismatch(path, "true or false");
        }
        if (raw == int.class || raw == Integer.class) return number(v, path).intValue();
        if (raw == long.class || raw == Long.class) return number(v, path).longValue();
        if (raw == double.class || raw == Double.class) return number(v, path).doubleValue();
        if (raw.isEnum()) {
            if (!(v instanceof String s)) throw mismatch(path, "a name");
            for (Object c : raw.getEnumConstants()) if (((Enum<?>) c).name().equals(s)) return c;
            return null; // unknown value from a newer version: fall back to the field default
        }
        if (List.class.isAssignableFrom(raw)) {
            if (!(v instanceof List<?> l)) throw mismatch(path, "an array");
            Type elem = typeArg(generic, 0);
            List<Object> out = new ArrayList<>(l.size());
            for (int k = 0; k < l.size(); k++) out.add(convert(l.get(k), rawOf(elem), elem, path + "[" + k + "]"));
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (!(v instanceof Map<?, ?> m)) throw mismatch(path, "an object");
            Type val = typeArg(generic, 1);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), convert(e.getValue(), rawOf(val), val, path + "." + e.getKey()));
            }
            return out;
        }
        if (raw == Object.class) return v;
        if (!(v instanceof Map<?, ?> m)) throw mismatch(path, "an object");
        Object obj = instantiate(raw);
        for (Field f : fields(raw)) {
            if (!m.containsKey(f.getName())) continue;
            Object fv = convert(m.get(f.getName()), f.getType(), f.getGenericType(), path + "." + f.getName());
            if (fv == null && (f.getType().isPrimitive() || f.getType().isEnum())) continue; // keep default
            try {
                f.set(obj, fv);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return obj;
    }

    private static Json.Num number(Object v, String path) throws JsonException {
        if (v instanceof Json.Num n) return n;
        if (v instanceof Number n) return new Json.Num(n.toString());
        throw mismatch(path, "a number");
    }

    private static Object defaultPrimitive(Class<?> c) {
        if (c == boolean.class) return false;
        if (c == int.class) return 0;
        if (c == long.class) return 0L;
        if (c == double.class) return 0d;
        return null;
    }

    private static JsonException mismatch(String path, String expected) {
        return new JsonException("Expected " + expected + " at " + path);
    }

    private static Type typeArg(Type generic, int index) {
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        return Object.class;
    }

    private static Class<?> rawOf(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }

    private static Object instantiate(Class<?> c) throws JsonException {
        try {
            Constructor<?> k = c.getDeclaredConstructor();
            return k.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonException("Can't create " + c.getSimpleName() + ": it needs a public no-argument constructor");
        }
    }

    private static List<Field> fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> out = new ArrayList<>();
            for (Field f : k.getFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || Modifier.isFinal(mod)) continue;
                out.add(f);
            }
            return out;
        });
    }
}
