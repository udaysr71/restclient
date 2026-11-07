package dev.restclient.testkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds every {@code *Test} class under a compiled test directory and runs its {@link Test} methods.
 * Usage: {@code java -cp classes:test-classes dev.restclient.testkit.TestRunner test-classes [filter]}.
 * Exits with status 1 if anything failed.
 */
public final class TestRunner {
    private TestRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/test-classes");
        String filter = args.length > 1 ? args[1] : "";
        List<String> classes = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith("Test.class"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(p -> {
                        String rel = root.relativize(p).toString();
                        classes.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.').replace('\\', '.'));
                    });
        }
        int passed = 0;
        List<String> failures = new ArrayList<>();
        long start = System.nanoTime();
        for (String name : classes) {
            if (!name.contains(filter)) continue;
            Class<?> c = Class.forName(name);
            List<Method> before = annotated(c, BeforeEach.class);
            List<Method> after = annotated(c, AfterEach.class);
            for (Method m : annotated(c, Test.class)) {
                String id = c.getSimpleName() + "." + m.getName();
                Object instance = null;
                try {
                    Constructor<?> k = c.getDeclaredConstructor();
                    k.setAccessible(true);
                    instance = k.newInstance();
                    for (Method b : before) b.invoke(instance);
                    m.invoke(instance);
                    passed++;
                    System.out.println("  ok    " + id);
                } catch (InvocationTargetException e) {
                    failures.add(id);
                    System.out.println("  FAIL  " + id + ": " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                } finally {
                    if (instance != null) {
                        for (Method a : after) {
                            try {
                                a.invoke(instance);
                            } catch (Exception ignored) {
                                // cleanup problems don't fail the test
                            }
                        }
                    }
                }
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed (" + ms + " ms)");
        for (String f : failures) System.out.println("  failed: " + f);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static List<Method> annotated(Class<?> c, Class<? extends java.lang.annotation.Annotation> a) {
        List<Method> out = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.isAnnotationPresent(a)) {
                m.setAccessible(true);
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(Method::getName));
        return out;
    }
}
