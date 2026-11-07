package dev.restclient.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locations of everything the app stores:
 * <pre>
 * ~/.restclient/
 *   collections/      one JSON file per collection
 *   environments/     one JSON file per environment, plus globals.json
 *   certs/            extra trusted certificates and keystores
 *   history.json
 *   settings.json
 *   session.json      open tabs and window layout
 * </pre>
 * The base folder can be moved with {@code -Drestclient.home=/some/dir} (used by tests).
 */
public final class AppPaths {
    private final Path base;

    public AppPaths(Path base) {
        this.base = base;
    }

    public static AppPaths defaultPaths() {
        String override = System.getProperty("restclient.home");
        Path base = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".restclient");
        return new AppPaths(base);
    }

    public Path base() { return base; }
    public Path collections() { return base.resolve("collections"); }
    public Path environments() { return base.resolve("environments"); }
    public Path certs() { return base.resolve("certs"); }
    public Path history() { return base.resolve("history.json"); }
    public Path settings() { return base.resolve("settings.json"); }
    public Path session() { return base.resolve("session.json"); }

    /** Creates the folder layout on first run. */
    public void ensureExists() throws IOException {
        Files.createDirectories(collections());
        Files.createDirectories(environments());
        Files.createDirectories(certs());
    }

    /** "~/.restclient/certs" style display path. */
    public static String display(Path p) {
        String home = System.getProperty("user.home");
        String s = p.toAbsolutePath().toString();
        if (home != null && s.startsWith(home)) return "~" + s.substring(home.length());
        return s;
    }
}
