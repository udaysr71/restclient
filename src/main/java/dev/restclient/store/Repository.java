package dev.restclient.store;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HistoryEntry;
import dev.restclient.model.Session;
import dev.restclient.model.Settings;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory state plus persistence. All mutation happens on one thread (the Swing EDT in the app);
 * changes are serialized immediately and written by a background thread, so the UI never waits on disk.
 */
public final class Repository {
    private static final Logger LOG = Logger.getLogger(Repository.class.getName());

    public enum Change { COLLECTIONS, ENVIRONMENTS, HISTORY, SETTINGS }

    public interface Listener {
        void changed(Change change);
    }

    private final AppPaths paths;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "restclient-writer");
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> loadWarnings = new ArrayList<>();

    private final List<ApiCollection> collections = new ArrayList<>();
    private final Map<String, Path> collectionFiles = new HashMap<>();
    private final List<Environment> environments = new ArrayList<>();
    private final Map<String, Path> environmentFiles = new HashMap<>();
    private Environment globals = Environment.globals();
    private List<HistoryEntry> history = new ArrayList<>();
    private Settings settings = new Settings();

    public Repository(AppPaths paths) {
        this.paths = paths;
    }

    public AppPaths paths() {
        return paths;
    }

    /** Loads everything from disk, creating the folder layout if needed. */
    public void load() throws IOException {
        paths.ensureExists();
        settings = readOr(paths.settings(), Settings.class, new Settings());
        HistoryList h = readOr(paths.history(), HistoryList.class, new HistoryList());
        history = h.entries == null ? new ArrayList<>() : h.entries;

        collections.clear();
        collectionFiles.clear();
        for (Path f : jsonFiles(paths.collections())) {
            ApiCollection c = readOr(f, ApiCollection.class, null);
            if (c != null) {
                collections.add(c);
                collectionFiles.put(c.id, f);
            }
        }
        collections.sort(Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT)));

        environments.clear();
        environmentFiles.clear();
        for (Path f : jsonFiles(paths.environments())) {
            Environment e = readOr(f, Environment.class, null);
            if (e == null) continue;
            if (e.isGlobals()) {
                globals = e;
            } else {
                environments.add(e);
                environmentFiles.put(e.id, f);
            }
        }
        environments.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
    }

    public List<String> loadWarnings() {
        return loadWarnings;
    }

    private <T> T readOr(Path file, Class<T> type, T fallback) {
        try {
            T v = JsonStore.readIfExists(file, type);
            return v == null ? fallback : v;
        } catch (IOException e) {
            // Keep the broken file for the user and carry on with defaults.
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(file, backup);
            } catch (IOException ignored) {
                // leave it
            }
            loadWarnings.add("Couldn't read " + AppPaths.display(file) + " (kept as " + backup.getFileName() + ")");
            LOG.log(Level.WARNING, "Failed to read " + file, e);
            return fallback;
        }
    }

    private static List<Path> jsonFiles(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) out.add(p);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    // ---- listeners -------------------------------------------------------------------------

    public void addListener(Listener l) {
        listeners.add(l);
    }

    private void fire(Change c) {
        for (Listener l : listeners) l.changed(c);
    }

    // ---- collections -----------------------------------------------------------------------

    public List<ApiCollection> collections() {
        return collections;
    }

    public ApiCollection collection(String id) {
        if (id == null) return null;
        for (ApiCollection c : collections) if (c.id.equals(id)) return c;
        return null;
    }

    /** Collection that contains a node, or null. */
    public ApiCollection collectionOf(String nodeId) {
        for (ApiCollection c : collections) if (c.find(nodeId) != null) return c;
        return null;
    }

    public void addCollection(ApiCollection c) {
        collections.add(c);
        saveCollection(c);
    }

    public void deleteCollection(ApiCollection c) {
        collections.remove(c);
        Path f = collectionFiles.remove(c.id);
        if (f != null) writer.execute(() -> deleteQuietly(f));
        fire(Change.COLLECTIONS);
    }

    public void saveCollection(ApiCollection c) {
        Path target = paths.collections().resolve(fileName(c.name, c.id));
        Path old = collectionFiles.put(c.id, target);
        write(target, c, old != null && !old.equals(target) ? old : null);
        fire(Change.COLLECTIONS);
    }

    /** Finds the saved request node and collection for a tab. */
    public CollectionNode node(String collectionId, String nodeId) {
        ApiCollection c = collection(collectionId);
        return c == null || nodeId == null ? null : c.find(nodeId);
    }

    // ---- environments ----------------------------------------------------------------------

    public List<Environment> environments() {
        return environments;
    }

    public Environment globals() {
        return globals;
    }

    public Environment environment(String id) {
        if (id == null) return null;
        if (Environment.GLOBALS_ID.equals(id)) return globals;
        for (Environment e : environments) if (e.id.equals(id)) return e;
        return null;
    }

    public Environment activeEnvironment() {
        Environment e = environment(settings.activeEnvironmentId);
        return e == null || e.isGlobals() ? null : e;
    }

    public void setActiveEnvironment(Environment e) {
        settings.activeEnvironmentId = e == null ? null : e.id;
        saveSettings();
        fire(Change.ENVIRONMENTS);
    }

    public void addEnvironment(Environment e) {
        environments.add(e);
        saveEnvironment(e);
    }

    public void deleteEnvironment(Environment e) {
        environments.remove(e);
        Path f = environmentFiles.remove(e.id);
        if (f != null) writer.execute(() -> deleteQuietly(f));
        if (e.id.equals(settings.activeEnvironmentId)) {
            settings.activeEnvironmentId = null;
            saveSettings();
        }
        fire(Change.ENVIRONMENTS);
    }

    public void saveEnvironment(Environment e) {
        if (e.isGlobals()) {
            write(paths.environments().resolve("globals.json"), e, null);
        } else {
            Path target = paths.environments().resolve(fileName(e.name, e.id));
            Path old = environmentFiles.put(e.id, target);
            write(target, e, old != null && !old.equals(target) ? old : null);
        }
        fire(Change.ENVIRONMENTS);
    }

    // ---- history ---------------------------------------------------------------------------

    public List<HistoryEntry> history() {
        return history;
    }

    public void addHistory(HistoryEntry e) {
        history.add(0, e);
        int limit = Math.max(1, settings.historyLimit);
        while (history.size() > limit) history.remove(history.size() - 1);
        saveHistory();
    }

    public void removeHistory(HistoryEntry e) {
        history.remove(e);
        saveHistory();
    }

    /** Clears history and returns what was removed so it can be restored (undo). */
    public List<HistoryEntry> clearHistory() {
        List<HistoryEntry> old = history;
        history = new ArrayList<>();
        saveHistory();
        return old;
    }

    public void restoreHistory(List<HistoryEntry> entries) {
        history = new ArrayList<>(entries);
        saveHistory();
    }

    private void saveHistory() {
        HistoryList l = new HistoryList();
        l.entries = history;
        write(paths.history(), l, null);
        fire(Change.HISTORY);
    }

    /** Wrapper so history.json is an object (room for a version field later). */
    public static class HistoryList {
        public int version = 1;
        public List<HistoryEntry> entries = new ArrayList<>();
    }

    // ---- settings & session ----------------------------------------------------------------

    public Settings settings() {
        return settings;
    }

    public void saveSettings() {
        write(paths.settings(), settings, null);
        fire(Change.SETTINGS);
    }

    public Session loadSession() {
        return readOr(paths.session(), Session.class, new Session());
    }

    public void saveSession(Session s) {
        write(paths.session(), s, null);
    }

    // ---- writing ---------------------------------------------------------------------------

    private void write(Path target, Object value, Path deleteAfter) {
        final byte[] bytes;
        try {
            bytes = JsonStore.toBytes(value); // snapshot now, on the caller's thread
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not serialize " + target, e);
            return;
        }
        writer.execute(() -> {
            try {
                JsonStore.writeBytes(target, bytes);
                if (deleteAfter != null) deleteQuietly(deleteAfter);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Could not write " + target, e);
            }
        });
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete " + p, e);
        }
    }

    /** Waits for pending writes (on exit and in tests). */
    public void flush() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String fileName(String name, String id) {
        String slug = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) slug = "untitled";
        if (slug.length() > 40) slug = slug.substring(0, 40);
        String shortId = id.replace("-", "");
        shortId = shortId.substring(0, Math.min(8, shortId.length()));
        return slug + "-" + shortId + ".json";
    }
}
