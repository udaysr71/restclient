import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds REST Client with nothing but a JDK (17 or newer). No downloads, no plugins.
 *
 * <pre>
 *   java Build.java          compile, run tests, build build/restclient.jar
 *   java Build.java jar      compile and build the jar, skipping tests
 *   java Build.java test     compile and run tests
 *   java Build.java run      build (skipping tests) and start the app
 *   java Build.java clean    delete build/
 * </pre>
 */
public class Build {
    static final Path ROOT = Path.of(System.getProperty("user.dir"));
    static final Path OUT = ROOT.resolve("build");
    static final Path CLASSES = OUT.resolve("classes");
    static final Path TEST_CLASSES = OUT.resolve("test-classes");
    static final Path JAR = OUT.resolve("restclient.jar");
    static final String MAIN = "dev.restclient.App";

    public static void main(String[] args) throws Exception {
        String task = args.length > 0 ? args[0] : "all";
        switch (task) {
            case "clean" -> clean();
            case "test" -> {
                compileMain();
                test();
            }
            case "jar" -> {
                compileMain();
                jar();
            }
            case "run" -> {
                compileMain();
                jar();
                run(List.of(javaBin(), "-jar", JAR.toString()));
            }
            case "featurecheck" -> {
                compileMain();
                compile(ROOT.resolve("src/test/java"), TEST_CLASSES, CLASSES.toString());
                featureCheck(CLASSES + java.io.File.pathSeparator + TEST_CLASSES);
            }
            case "uicheck" -> {
                compileMain();
                uiCheck();
            }
            case "all" -> {
                compileMain();
                test();
                jar();
            }
            default -> {
                System.err.println("Unknown task: " + task + " (use all, jar, test, uicheck, featurecheck, run or clean)");
                System.exit(2);
            }
        }
    }

    static void clean() throws IOException {
        if (!Files.exists(OUT)) return;
        try (Stream<Path> s = Files.walk(OUT)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        System.out.println("Deleted " + ROOT.relativize(OUT));
    }

    static void compileMain() throws IOException {
        clean();
        compile(ROOT.resolve("src/main/java"), CLASSES, null);
        copyTree(ROOT.resolve("src/main/resources"), CLASSES);
        // Draw the launch splash with the same code and fonts the app uses.
        try {
            int code = run(List.of(javaBin(), "-Djava.awt.headless=true", "-cp", CLASSES.toString(),
                    "dev.restclient.ui.Splash", CLASSES.toString()));
            if (code != 0) System.err.println("Couldn't draw the splash image; the jar will start without one.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void test() throws Exception {
        compile(ROOT.resolve("src/test/java"), TEST_CLASSES, CLASSES.toString());
        String cp = CLASSES + java.io.File.pathSeparator + TEST_CLASSES;
        int code = run(List.of(javaBin(), "-Djava.awt.headless=true", "-cp", cp,
                "dev.restclient.testkit.TestRunner", TEST_CLASSES.toString()));
        if (code != 0) {
            System.err.println("Tests failed.");
            System.exit(code);
        }
    }

    /** Drives the real UI with keyboard and mouse events. Needs a display (or Xvfb). */
    static void uiCheck() throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.out.println("No display: skipping the UI interaction check (on Linux CI use xvfb-run).");
            return;
        }
        compile(ROOT.resolve("src/test/java"), TEST_CLASSES, CLASSES.toString());
        String cp = CLASSES + java.io.File.pathSeparator + TEST_CLASSES;
        int code = run(List.of(javaBin(), "-cp", cp, "dev.restclient.ui.InteractionCheck"));
        if (code != 0) {
            System.err.println("UI check failed.");
            System.exit(code);
        }
        featureCheck(cp);
    }

    /** End-to-end feature check against a local HTTP/HTTPS server, then a restart on the same data folder. */
    static void featureCheck(String cp) throws Exception {
        Path home = Files.createTempDirectory("rc-features");
        int code = run(List.of(javaBin(), "-cp", cp, "dev.restclient.ui.FeatureCheck", "--home", home.toString()));
        if (code == 0) code = run(List.of(javaBin(), "-cp", cp, "dev.restclient.ui.FeatureCheck", "--relaunch", home.toString()));
        if (code != 0) {
            System.err.println("Feature check failed.");
            System.exit(code);
        }
    }

    static void compile(Path sourceRoot, Path out, String classpath) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(sourceRoot)) {
            s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> files.add(p.toString()));
        }
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            System.err.println("No Java compiler found. Run this with a JDK, not a JRE.");
            System.exit(1);
        }
        // "this-escape" only exists from JDK 21; older compilers would reject it.
        String lint = Runtime.version().feature() >= 21 ? "-Xlint:all,-serial,-this-escape" : "-Xlint:all,-serial";
        List<String> argv = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-g",
                lint, "-d", out.toString()));
        if (classpath != null) {
            argv.add("-cp");
            argv.add(classpath);
        }
        argv.addAll(files);
        System.out.println("Compiling " + files.size() + " files from " + ROOT.relativize(sourceRoot));
        int code = javac.run(null, null, null, argv.toArray(new String[0]));
        if (code != 0) {
            System.err.println("Compilation failed.");
            System.exit(code);
        }
    }

    static void jar() throws IOException {
        java.util.spi.ToolProvider jarTool = java.util.spi.ToolProvider.findFirst("jar")
                .orElseThrow(() -> new IllegalStateException("The jar tool is missing from this JDK"));
        Files.deleteIfExists(JAR);
        List<String> args = new ArrayList<>(List.of("--create", "--file", JAR.toString(), "--main-class", MAIN));
        if (Files.exists(CLASSES.resolve("splash.png"))) {
            Path manifest = OUT.resolve("MANIFEST.MF");
            Files.writeString(manifest, "SplashScreen-Image: splash.png\n");
            args.addAll(List.of("--manifest", manifest.toString()));
        }
        args.addAll(List.of("-C", CLASSES.toString(), "."));
        int code = jarTool.run(System.out, System.err, args.toArray(new String[0]));
        if (code != 0) System.exit(code);
        System.out.println("Built " + ROOT.relativize(JAR) + " (" + Files.size(JAR) / 1024 + " KB). Run: java -jar "
                + ROOT.relativize(JAR));
    }

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static int run(List<String> cmd) throws IOException, InterruptedException {
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
