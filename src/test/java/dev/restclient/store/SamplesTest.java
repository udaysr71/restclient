package dev.restclient.store;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HttpMethod;
import dev.restclient.postman.Postman;
import dev.restclient.testkit.Test;
import dev.restclient.vars.VariableResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertTrue;

class SamplesTest {
    @Test
    void theSampleCoversEveryFeatureArea() {
        ApiCollection c = Samples.httpbin();
        assertEquals(7, c.items.size());
        Set<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
        int requests = 0;
        for (CollectionNode f : c.items) {
            assertTrue(f.folder && !f.children.isEmpty(), f.name + " is a non-empty folder");
            Set<String> names = new HashSet<>();
            for (CollectionNode n : f.children) {
                assertTrue(names.add(n.name), "names are unique within " + f.name + ": " + n.name);
                assertEquals(n.name, n.request.name);
                methods.add(n.request.method);
                requests++;
            }
        }
        assertEquals(EnumSet.allOf(HttpMethod.class), methods, "every method appears");
        assertTrue(requests >= 35, "requests: " + requests);
        assertTrue(c.variables.stream().anyMatch(v -> v.secret), "shows secret variables");
    }

    /** Every {{variable}} resolves from the collection, except the one that's meant to be undefined. */
    @Test
    void variablesResolveExceptTheDeliberateOne() {
        ApiCollection c = Samples.httpbin();
        VariableResolver r = new VariableResolver(null, c, null);
        List<String> undefined = new ArrayList<>();
        c.forEachRequest(n -> {
            List<String> texts = new ArrayList<>(List.of(n.request.url, n.request.body == null ? "" : n.request.body));
            n.request.headers.forEach(h -> texts.add(h.value));
            n.request.form.forEach(h -> texts.add(h.value));
            if (n.request.auth != null) texts.addAll(List.of(n.request.auth.username, n.request.auth.password, n.request.auth.token));
            for (String t : texts) {
                for (VariableResolver.Token tok : VariableResolver.tokens(t)) if (!r.isDefined(tok.name())) undefined.add(tok.name());
            }
        });
        assertEquals(List.of("notDefinedYet"), undefined.stream().distinct().toList());
    }

    @Test
    void theLocalEnvironmentOverridesBaseUrlAndUserId() {
        Environment e = Samples.localEnvironment();
        assertEquals(Samples.LOCAL_ENV, e.name);
        assertTrue(e.variables.stream().anyMatch(v -> v.key.equals("baseUrl") && v.value.startsWith("http://localhost")));
        assertTrue(e.variables.stream().anyMatch(v -> v.key.equals("userId")));
    }

    /** The files in samples/ are what the code generates (run "java Build.java samples" after changing it). */
    @Test
    void theSamplesFolderIsUpToDate() throws Exception {
        Path dir = Path.of("samples");
        if (!Files.isDirectory(dir)) return; // tests run from another folder
        assertEquals(Samples.postmanCollection(), Files.readString(dir.resolve("httpbin.postman_collection.json")),
                "samples/httpbin.postman_collection.json is out of date: run java Build.java samples");
        assertEquals(Samples.postmanEnvironment(), Files.readString(dir.resolve("local-httpbin.postman_environment.json")));
    }

    @Test
    void thePostmanFileImportsBackCompletely() throws Exception {
        Postman.Result r = Postman.read(Samples.postmanCollection());
        ApiCollection back = r.collection();
        int[] original = {0};
        Samples.httpbin().forEachRequest(n -> original[0]++);
        assertEquals(original[0], r.requests());
        assertEquals(7, r.folders());
        assertTrue(r.notes().isEmpty(), "nothing lost: " + r.notes());
        assertEquals(Samples.NAME, back.name);
    }
}
