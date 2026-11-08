package dev.restclient.ui;

import dev.restclient.model.Settings;
import dev.restclient.store.AppPaths;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Theme files: the bundled ones (Solarized Sepia and Dark) and the user's own in {@code ~/.restclient/themes}.
 * The folder is watched, so saving a file updates the app; {@link #onChange} listeners run on the EDT.
 */
public final class Themes {
    /** A choice in the Appearance menu and the Settings picker. */
    public record Choice(Settings.ThemeMode mode, String custom, String label) {
        public boolean matches(Settings s) {
            return s.theme == mode && (mode != Settings.ThemeMode.CUSTOM || custom.equals(s.customTheme));
        }

        public Theme.Palette palette() {
            return Theme.paletteFor(mode, custom);
        }
    }

    private static final Map<String, Theme.Palette> USER = new LinkedHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static Path dir;
    private static Thread watcher;

    private Themes() {
    }

    /** The themes folder (created on demand). */
    public static synchronized Path dir() {
        if (dir == null) dir = AppPaths.defaultPaths().base().resolve("themes");
        return dir;
    }

    /** Sets the folder (the app's data folder + "themes"; tests use a temporary one). */
    public static synchronized void useDir(Path d) {
        dir = d;
    }

    // ---- bundled

    static Theme.Palette bundled(String id) {
        try (InputStream in = Themes.class.getResourceAsStream("/themes/" + id + ".css")) {
            if (in == null) throw new IllegalStateException("missing bundled theme " + id);
            ThemeFile.Parsed p = ThemeFile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return build(id, p, null, base -> base.equals("dark") ? Theme.Palette.DARK : Theme.Palette.LIGHT, Set.of(), false);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- user themes

    /** The user's themes, sorted by name. Call {@link #reload} first. */
    public static synchronized List<Theme.Palette> user() {
        return List.copyOf(USER.values());
    }

    /** A palette by id: built-in, bundled, or the user's. Null if there's none. */
    public static synchronized Theme.Palette find(String id) {
        if (!ThemeFile.isId(id)) return null;
        for (Theme.Palette p : Theme.Palette.builtIns()) if (p.id.equals(id)) return p;
        return USER.get(id);
    }

    /** Every choice, in menu order: Match System, Light, Dark, the Solarized pair, then the user's themes. */
    public static List<Choice> choices() {
        List<Choice> out = new ArrayList<>();
        for (Settings.ThemeMode m : Settings.ThemeMode.values()) {
            if (m != Settings.ThemeMode.CUSTOM) out.add(new Choice(m, null, m.label));
        }
        for (Theme.Palette p : user()) out.add(new Choice(Settings.ThemeMode.CUSTOM, p.id, p.label));
        return out;
    }

    /** The choice that selects a palette id. */
    public static Choice choiceFor(String id) {
        return switch (id) {
            case "light" -> new Choice(Settings.ThemeMode.LIGHT, null, "Light");
            case "dark" -> new Choice(Settings.ThemeMode.DARK, null, "Dark");
            case "solarized-sepia" -> new Choice(Settings.ThemeMode.SEPIA, null, Settings.ThemeMode.SEPIA.label);
            case "solarized-dark" -> new Choice(Settings.ThemeMode.SOLARIZED_DARK, null, Settings.ThemeMode.SOLARIZED_DARK.label);
            default -> {
                Theme.Palette p = find(id);
                yield new Choice(Settings.ThemeMode.CUSTOM, id, p == null ? id : p.label);
            }
        };
    }

    /** Where the light/dark toggle goes from a palette: its {@code toggle}, else Light ↔ Dark. */
    public static Choice toggled(Theme.Palette p) {
        if (p.toggle != null && find(p.toggle) != null) return choiceFor(p.toggle);
        return choiceFor(p.dark ? "light" : "dark");
    }

    /**
     * Reads every {@code *.css} in the folder whose name is an allowed id (lowercase letters, digits, - and _);
     * other files, folders and symlinks are skipped. Runs on the EDT (contrast checks swap the active palette briefly). */
    public static synchronized void reload() {
        Map<String, ThemeFile.Parsed> parsed = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        Path d = dir();
        if (Files.isDirectory(d)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.css")) {
                for (Path f : ds) {
                    // only regular files, never symlinks (which could point anywhere), with allow-listed names
                    if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    String id = idOf(f);
                    if (!ThemeFile.isId(id)) continue;
                    if (Set.of("light", "dark", "solarized-sepia", "solarized-dark").contains(id)) id = "user-" + id;
                    try {
                        if (Files.size(f) > ThemeFile.MAX_BYTES) {
                            parsed.put(id, new ThemeFile.Parsed(null, null, null, Map.of(), List.of("The file is larger than "
                                    + ThemeFile.MAX_BYTES / 1024 + " KB, so it wasn't read. Theme files only need colours.")));
                        } else {
                            parsed.put(id, ThemeFile.parse(Files.readString(f, StandardCharsets.UTF_8)));
                        }
                        files.put(id, f);
                    } catch (IOException | RuntimeException e) {
                        // unreadable (or not UTF-8): list it with the reason so it's not silently missing
                        parsed.put(id, new ThemeFile.Parsed(null, null, null, Map.of(),
                                List.of("Couldn't read the file (it must be UTF-8 text): " + e.getClass().getSimpleName())));
                        files.put(id, f);
                    }
                }
            } catch (IOException e) {
                // folder vanished between the check and the listing; treat as empty
            }
        }
        Map<String, Theme.Palette> built = new LinkedHashMap<>();
        for (String id : parsed.keySet()) resolveUser(id, parsed, files, built, new HashSet<>());
        USER.clear();
        built.values().stream().sorted(Comparator.comparing(p -> p.label.toLowerCase(Locale.ROOT)))
                .forEach(p -> USER.put(p.id, p));
    }

    private static Theme.Palette resolveUser(String id, Map<String, ThemeFile.Parsed> parsed, Map<String, Path> files,
                                             Map<String, Theme.Palette> built, Set<String> visiting) {
        Theme.Palette done = built.get(id);
        if (done != null) return done;
        visiting.add(id);
        Theme.Palette p = build(id, parsed.get(id), files.get(id), base -> {
            Theme.Palette b = null;
            for (Theme.Palette bi : Theme.Palette.builtIns()) if (bi.id.equals(base)) b = bi;
            if (b == null && parsed.containsKey(base) && !visiting.contains(base)) b = resolveUser(base, parsed, files, built, visiting);
            return b;
        }, visiting, true);
        built.put(id, p);
        return p;
    }

    private interface BaseLookup {
        Theme.Palette find(String id);
    }

    private static Theme.Palette build(String id, ThemeFile.Parsed p, Path file, BaseLookup lookup, Set<String> visiting,
                                       boolean checkContrast) {
        List<String> warnings = new ArrayList<>(p.warnings());
        String baseId = p.base() == null ? "light" : p.base().strip().toLowerCase(Locale.ROOT);
        Theme.Palette base = lookup.find(baseId);
        if (base == null) {
            warnings.add(visiting.contains(baseId)
                    ? "base: " + baseId + " leads back to this theme, so Light is used instead."
                    : "base: \"" + p.base() + "\" isn't a theme (use light, dark, or another theme's file name); using Light.");
            base = Theme.Palette.LIGHT;
        }
        Map<String, Integer> colors = new LinkedHashMap<>(base.overrides);
        colors.putAll(p.colors());
        String label = p.name() == null || p.name().isBlank() ? prettify(id) : p.name().strip();
        String toggle = p.toggle() == null || p.toggle().isBlank() ? null : p.toggle().strip().toLowerCase(Locale.ROOT);
        Theme.Palette pal = new Theme.Palette(id, label, base.dark, toggle, colors, warnings, file);
        if (!checkContrast) return pal;
        warnings.addAll(Contrast.check(pal));
        if (toggle != null && find(toggle) == null && !toggle.equals(id)) {
            // resolved later in the same reload is fine; only flag names that can't exist
            Path other = dir().resolve(toggle + ".css");
            if (!Files.exists(other)) warnings.add("toggle: \"" + toggle + "\" isn't a theme; the light/dark button will use Light or Dark.");
        }
        return new Theme.Palette(id, label, base.dark, toggle, colors, warnings, file);
    }

    static String idOf(Path f) {
        String n = f.getFileName().toString();
        return n.substring(0, n.length() - 4).toLowerCase(Locale.ROOT);
    }

    private static String prettify(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.split("[-_ ]+")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.isEmpty() ? id : b.toString();
    }

    // ---- export

    /**
     * Writes the palette as a complete theme file named after {@code name} in the themes folder, and returns it.
     * Never overwrites: "nord.css" becomes "nord-2.css" if taken.
     */
    public static Path export(Theme.Palette p, String name) throws IOException {
        // the same allow-list as names read from files, so the written file can't be malformed by the name
        String safe = ThemeFile.safeName(name);
        name = safe == null ? "My Theme" : safe;
        Files.createDirectories(dir());
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) slug = "my-theme";
        Path target = dir().resolve(slug + ".css");
        for (int n = 2; Files.exists(target); n++) target = dir().resolve(slug + "-" + n + ".css");
        Files.writeString(target, ThemeFile.write(name, p.dark, p::argb), StandardCharsets.UTF_8);
        return target;
    }

    // ---- watching

    /** Runs on the EDT after the user's themes change on disk (already reloaded). */
    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    /** Starts watching the themes folder (creating it, with a README, if needed). Safe to call more than once. */
    public static synchronized void watch() {
        if (watcher != null) return;
        Path d = dir();
        try {
            Files.createDirectories(d);
            Path readme = d.resolve("README.txt");
            if (!Files.exists(readme)) Files.writeString(readme, README, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // read-only home: themes still load from whatever exists
        }
        watcher = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                d.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = ws.take();
                    // editors save in bursts (temp file, rename, touch): wait for it to settle
                    Thread.sleep(150);
                    key.pollEvents();
                    for (WatchKey k; (k = ws.poll()) != null; ) {
                        k.pollEvents();
                        k.reset();
                    }
                    key.reset();
                    SwingUtilities.invokeLater(() -> {
                        reload();
                        for (Runnable r : LISTENERS) r.run();
                    });
                }
            } catch (IOException | InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                // stop watching; themes still load on next start
            }
        }, "theme-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static final String README = """
            REST Client themes
            ==================

            Put .css theme files in this folder. They appear in View > Appearance and
            Settings > General, and the app updates as soon as you save a file.

            Quickest start: View > Appearance > Export Current Theme... writes a complete
            file with every colour, commented, that you can edit here.

            A minimal theme:

                @theme {
                  name: "My Theme";
                  base: dark;        /* light, dark, or another theme's file name */
                }
                :root {
                  --accent: #E05D44;
                  --panelBg: #1A1B26;
                }

            Anything you leave out comes from the base theme. Colours can be #RGB,
            #RRGGBB, #RRGGBBAA, rgb(), rgba() or var(--a-name-you-defined).
            Problems and low-contrast text are listed in Settings > General.
            """;
}
