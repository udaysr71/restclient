package dev.restclient.store;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HistoryEntry;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.testkit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertNotNull;
import static dev.restclient.testkit.Assertions.assertTrue;

class RepositoryTest {

    @Test
    void savesAndReloadsEverything() throws Exception {
        Path base = Files.createTempDirectory("restclient-repo");
        Repository repo = new Repository(new AppPaths(base));
        repo.load();
        assertTrue(Files.isDirectory(base.resolve("certs")), "folder layout is created on first run");

        ApiCollection c = new ApiCollection();
        c.name = "Orders API";
        c.variables.add(new Variable("page", "25"));
        CollectionNode folder = CollectionNode.folder("Orders");
        RequestDef r = new RequestDef();
        r.name = "List orders";
        r.url = "{{baseUrl}}/v2/orders";
        folder.children.add(CollectionNode.request(r));
        c.items.add(folder);
        repo.addCollection(c);

        Environment staging = new Environment();
        staging.name = "Staging";
        staging.variables.add(new Variable("token", "t", true));
        repo.addEnvironment(staging);
        repo.setActiveEnvironment(staging);
        repo.globals().variables.add(new Variable("ua", "RestClient"));
        repo.saveEnvironment(repo.globals());

        repo.settings().historyLimit = 2;
        for (int i = 0; i < 3; i++) {
            HistoryEntry h = new HistoryEntry();
            h.sentUrl = "https://x.test/" + i;
            repo.addHistory(h);
        }
        assertEquals(2, repo.history().size());
        repo.flush();

        Repository again = new Repository(new AppPaths(base));
        again.load();
        assertEquals(1, again.collections().size());
        ApiCollection c2 = again.collections().get(0);
        assertEquals("Orders API", c2.name);
        assertEquals("List orders", c2.items.get(0).children.get(0).request.name);
        assertEquals("Orders API › Orders", c2.pathTo(c2.items.get(0).children.get(0).id));
        assertNotNull(again.activeEnvironment());
        assertTrue(again.activeEnvironment().variables.get(0).secret);
        assertEquals("RestClient", again.globals().variables.get(0).value);
        assertEquals("https://x.test/2", again.history().get(0).sentUrl);

        // human-readable files, no stray temp files
        try (Stream<Path> files = Files.list(base.resolve("collections"))) {
            List<Path> list = files.toList();
            assertEquals(1, list.size());
            assertTrue(list.get(0).getFileName().toString().startsWith("orders-api-"));
            assertTrue(Files.readString(list.get(0)).contains("\"name\": \"Orders API\""));
        }
    }

    @Test
    void renamingACollectionReplacesItsFile() throws Exception {
        Path base = Files.createTempDirectory("restclient-repo");
        Repository repo = new Repository(new AppPaths(base));
        repo.load();
        ApiCollection c = new ApiCollection();
        c.name = "First";
        repo.addCollection(c);
        c.name = "Second";
        repo.saveCollection(c);
        repo.flush();
        try (Stream<Path> files = Files.list(base.resolve("collections"))) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, names.size(), names.toString());
            assertTrue(names.get(0).startsWith("second-"));
        }
    }

    @Test
    void corruptFileIsKeptAndDefaultsUsed() throws Exception {
        Path base = Files.createTempDirectory("restclient-repo");
        Files.writeString(base.resolve("settings.json"), "{ not json");
        Repository repo = new Repository(new AppPaths(base));
        repo.load();
        assertEquals(200, repo.settings().historyLimit);
        assertFalse(repo.loadWarnings().isEmpty());
        try (Stream<Path> files = Files.list(base)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("settings.json.corrupt-")));
        }
    }

    @Test
    void fileNamesAreSlugs() {
        assertEquals("orders-api-12345678.json", Repository.fileName("Orders API!", "1234-5678-90"));
        assertEquals("untitled-abc.json", Repository.fileName("   ", "abc"));
    }

    /** flush() waits for pending writes but keeps the writer alive, so later saves still reach the disk. */
    @Test
    void savesAfterFlushStillReachTheDisk() throws Exception {
        Path base = Files.createTempDirectory("rc-flush");
        Repository repo = new Repository(new AppPaths(base));
        repo.load();
        dev.restclient.model.ApiCollection c = new dev.restclient.model.ApiCollection();
        c.name = "Before";
        repo.addCollection(c);
        repo.flush();
        c.name = "After flush";
        repo.saveCollection(c);
        repo.flush();
        Repository again = new Repository(new AppPaths(base));
        again.load();
        assertEquals("After flush", again.collections().get(0).name);
    }

    /** Undo of a single history deletion puts that entry back and keeps all the others. */
    @Test
    void restoringHistoryMergesInsteadOfReplacing() throws Exception {
        Repository repo = new Repository(new AppPaths(Files.createTempDirectory("rc-hist")));
        repo.load();
        List<dev.restclient.model.HistoryEntry> made = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            dev.restclient.model.HistoryEntry h = new dev.restclient.model.HistoryEntry();
            h.timestamp = 1000 + i;
            h.sentUrl = "http://x/" + i;
            repo.addHistory(h);
            made.add(h);
        }
        dev.restclient.model.HistoryEntry victim = made.get(2);
        repo.removeHistory(victim);
        assertEquals(3, repo.history().size());
        repo.restoreHistory(List.of(victim));
        assertEquals(4, repo.history().size(), "every entry is still there");
        assertEquals(victim, repo.history().get(1), "back in its place by time");

        List<dev.restclient.model.HistoryEntry> cleared = repo.clearHistory();
        dev.restclient.model.HistoryEntry newer = new dev.restclient.model.HistoryEntry();
        newer.timestamp = 5000;
        repo.addHistory(newer);
        repo.restoreHistory(cleared);
        assertEquals(5, repo.history().size(), "undoing Clear keeps entries recorded since");
        assertEquals(newer, repo.history().get(0));
        repo.restoreHistory(cleared);
        assertEquals(5, repo.history().size(), "restoring twice doesn't duplicate");
    }
}
