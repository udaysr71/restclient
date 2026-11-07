package dev.restclient.vars;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.Environment;
import dev.restclient.model.Variable;
import dev.restclient.testkit.Test;

import java.util.List;
import java.util.Set;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertTrue;

class VariableResolverTest {

    private static Environment env(String name, Variable... vars) {
        Environment e = new Environment();
        e.name = name;
        e.variables.addAll(List.of(vars));
        return e;
    }

    private static ApiCollection collection(Variable... vars) {
        ApiCollection c = new ApiCollection();
        c.name = "Orders API";
        c.variables.addAll(List.of(vars));
        return c;
    }

    @Test
    void precedenceIsEnvironmentThenCollectionThenGlobal() {
        Environment staging = env("Staging", new Variable("baseUrl", "https://staging.example.com"));
        ApiCollection col = collection(new Variable("baseUrl", "https://col.example.com"), new Variable("page", "10"));
        Environment globals = env("Globals", new Variable("baseUrl", "https://api.example.com"),
                new Variable("page", "99"), new Variable("ua", "RestClient"));
        VariableResolver v = new VariableResolver(staging, col, globals);

        assertEquals("https://staging.example.com/a?p=10&ua=RestClient", v.resolve("{{baseUrl}}/a?p={{page}}&ua={{ua}}"));
        VariableResolver.Resolution r = v.lookup("baseUrl").orElseThrow();
        assertEquals(VariableResolver.Scope.ENVIRONMENT, r.scope());
        assertEquals("Staging", r.sourceName());
        assertEquals(3, v.lookupAll("baseUrl").size());
        assertEquals(VariableResolver.Scope.COLLECTION, v.lookup("page").orElseThrow().scope());
        assertEquals(VariableResolver.Scope.GLOBAL, v.lookup("ua").orElseThrow().scope());
    }

    @Test
    void disabledVariablesFallThrough() {
        Variable off = new Variable("host", "env-host");
        off.enabled = false;
        VariableResolver v = new VariableResolver(env("E", off), null, env("G", new Variable("host", "global-host")));
        assertEquals("global-host", v.resolve("{{host}}"));
    }

    @Test
    void undefinedStayAsPlaceholdersAndAreReported() {
        VariableResolver v = new VariableResolver(env("Staging", new Variable("a", "1")), null, null);
        assertEquals("1-{{tenantId}}", v.resolve("{{a}}-{{tenantId}}"));
        assertEquals(Set.of("tenantId"), v.unresolved("{{a}}-{{tenantId}}"));
        assertFalse(v.isDefined("tenantId"));
    }

    @Test
    void whitespaceInsideBracesIsAllowed() {
        VariableResolver v = new VariableResolver(env("E", new Variable("x", "y")), null, null);
        assertEquals("y", v.resolve("{{ x }}"));
    }

    @Test
    void nestedValuesResolveWithoutLoopingForever() {
        VariableResolver v = new VariableResolver(env("E",
                new Variable("host", "api.{{domain}}"),
                new Variable("domain", "example.com"),
                new Variable("loop", "{{loop}}!")), null, null);
        assertEquals("https://api.example.com", v.resolve("https://{{host}}"));
        assertTrue(v.resolve("{{loop}}").startsWith("{{loop}}"));
    }

    @Test
    void secretsAreMaskedOnlyWhenAsked() {
        VariableResolver v = new VariableResolver(env("E", new Variable("token", "abc", true)), null, null);
        assertEquals("Bearer abc", v.resolve("Bearer {{token}}"));
        assertEquals("Bearer " + VariableResolver.MASK, v.resolveMasked("Bearer {{token}}"));
    }

    @Test
    void dollarSignsInValuesAreLiteral() {
        VariableResolver v = new VariableResolver(env("E", new Variable("p", "a$1\\b")), null, null);
        assertEquals("a$1\\b", v.resolve("{{p}}"));
    }

    @Test
    void tokensReportPositions() {
        List<VariableResolver.Token> t = VariableResolver.tokens("{{baseUrl}}/v2?limit={{ pageSize }}");
        assertEquals(2, t.size());
        assertEquals(new VariableResolver.Token(0, 11, "baseUrl"), t.get(0));
        assertEquals("pageSize", t.get(1).name());
        assertEquals(35, t.get(1).end());
    }

    @Test
    void namesForAutocompleteAreDistinctAndSorted() {
        VariableResolver v = new VariableResolver(env("E", new Variable("b", "1"), new Variable("A", "2")),
                collection(new Variable("b", "3")), null);
        assertEquals(List.of("A", "b"), List.copyOf(v.names()));
    }
}
