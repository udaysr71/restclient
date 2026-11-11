package dev.restclient.openapi;

import dev.restclient.json.Json;
import dev.restclient.testkit.Test;

import java.util.List;
import java.util.Map;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertNull;
import static dev.restclient.testkit.Assertions.assertThrows;
import static dev.restclient.testkit.Assertions.assertTrue;

class YamlTest {

    /** Parses YAML and writes it as compact JSON, which is easy to compare. */
    private static String json(String yaml) throws Exception {
        return Json.write(Yaml.parse(yaml), false);
    }

    @Test
    void mappingsListsAndScalars() throws Exception {
        assertEquals("{\"a\":1,\"b\":\"two\",\"c\":true,\"d\":null,\"e\":null,\"f\":-1.5e3,\"g\":\"3.0.1\",\"h\":\"0x1F\",\"i\":\"\"}",
                json("a: 1\nb: two\nc: true\nd: ~\ne:\nf: -1.5e3\ng: 3.0.1\nh: 0x1F\ni: \"\"\n"));
        assertEquals("{\"list\":[\"a\",2,false],\"nested\":{\"x\":{\"y\":\"z\"}}}",
                json("list:\n  - a\n  - 2\n  - false\nnested:\n  x:\n    y: z\n"));
        assertEquals("{\"required\":[\"id\",\"name\"],\"type\":\"object\"}", json("required:\n- id\n- name\ntype: object\n"),
                "a list may sit at the same indentation as its key");
        assertEquals("[{\"name\":\"id\",\"in\":\"path\",\"schema\":{\"type\":\"string\"}},{\"name\":\"q\"}]",
                json("- name: id\n  in: path\n  schema:\n    type: string\n- name: q\n"));
        assertEquals("[[1,2],[3]]", json("- - 1\n  - 2\n- - 3\n"));
        assertEquals("[null,\"a\"]", json("-\n- a\n"));
        assertEquals("\"just text\"", json("just text"));
        assertNull(Yaml.parse(""));
        assertNull(Yaml.parse("# only a comment\n"));
    }

    @Test
    void keysAreAlwaysText() throws Exception {
        assertEquals("{\"200\":{\"description\":\"OK\"},\"true\":1,\"/pets/{id}\":2,\"quoted: key\":3,\"it's\":4}",
                json("200:\n  description: OK\ntrue: 1\n/pets/{id}: 2\n\"quoted: key\": 3\n'it''s': 4\n"));
    }

    @Test
    void commentsAndDocumentMarkers() throws Exception {
        assertEquals("{\"a\":\"b\",\"url\":\"http://x/#frag\",\"c\":\"has # inside\"}",
                json("%YAML 1.2\n---\n# top\na: b # trailing\n\nurl: http://x/#frag\nc: \"has # inside\" # real comment\n...\nignored: true\n"));
        assertEquals("{\"first\":1}", json("first: 1\n---\nsecond: 2\n"), "only the first document is read");
    }

    @Test
    void quotedStrings() throws Exception {
        assertEquals("{\"a\":\"line\\nbreak \\\"q\\\" \\\\ é €\",\"b\":\"it's\",\"c\":\"no \\\\n escape\",\"d\":\"true\",\"e\":\"12\"}",
                json("a: \"line\\nbreak \\\"q\\\" \\\\ \\xe9 \\u20AC\"\nb: 'it''s'\nc: 'no \\n escape'\nd: \"true\"\ne: '12'\n"));
        assertEquals("{\"a\":\"one two\\nthree\",\"b\":1}", json("a: \"one\n  two\n\n  three\"\nb: 1\n"), "quoted text may run over lines");
    }

    @Test
    void textBlocks() throws Exception {
        assertEquals("{\"lit\":\"line 1\\n  indented\\n\\nline 4\\n\",\"next\":1}", json("lit: |\n  line 1\n    indented\n\n  line 4\nnext: 1\n"));
        assertEquals("{\"fold\":\"one two\\nthree\\n\"}", json("fold: >\n  one\n  two\n\n  three\n"));
        assertEquals("{\"strip\":\"a\\nb\",\"keep\":\"a\\n\\n\",\"z\":0}", json("strip: |-\n  a\n  b\n\nkeep: |+\n  a\n\nz: 0\n"));
        assertEquals("[\"x\\n\",{\"d\":\"# not a comment\\n\"}]", json("- |\n  x\n- d: |\n    # not a comment\n"));
        assertEquals("{\"plain\":\"one two three\",\"k\":1}", json("plain: one\n  two\n  three\nk: 1\n"), "plain text may continue on indented lines");
    }

    @Test
    void inlineCollections() throws Exception {
        assertEquals("{\"a\":[1,\"two\",[true,null],{\"k\":\"v\",\"n\":null}],\"b\":{},\"c\":[]}", json("a: [1, two, [true, ~], {k: v, n}]\nb: {}\nc: []\n"));
        assertEquals("{\"enum\":[\"a\",\"b, c\",\"d\"],\"x\":1}", json("enum: [a,\n  \"b, c\", # comment\n  d]\nx: 1\n"), "inline lists may run over lines");
        assertEquals("{\"j\":{\"a\":1,\"b\":[\"x\"],\"url\":\"http://h/p\"}}", json("j: {\"a\":1, \"b\":[\"x\"], url: http://h/p}\n"), "JSON is YAML");
    }

    @Test
    void anchorsAliasesAndMerges() throws Exception {
        assertEquals("{\"base\":{\"type\":\"object\",\"n\":1},\"copy\":{\"type\":\"object\",\"n\":1},\"more\":{\"type\":\"object\",\"n\":2,\"extra\":true},\"l\":[\"s\",\"s\"]}",
                json("base: &b\n  type: object\n  n: 1\ncopy: *b\nmore:\n  <<: *b\n  n: 2\n  extra: true\nl: [&s s, *s]\n".replace("[&s s, *s]", "\n  - &s s\n  - *s")));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: *nope\n")).getMessage().contains("isn't defined"));
    }

    @Test
    void tagsAreIgnored() throws Exception {
        assertEquals("{\"a\":\"12\",\"b\":{\"c\":1}}", json("a: !!str \"12\"\nb: !!map\n  c: 1\n"));
    }

    @Test
    void problemsNameTheLine() {
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: 1\n\tb: 2\n")).getMessage().startsWith("line 2: a tab"));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a:\n  b: 1\n c: 2\n")).getMessage().startsWith("line 3"));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: 1\nnot a pair\n")).getMessage().contains("expected “name: value”"));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: \"open\n")).getMessage().contains("never closed"));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: [1, 2\n")).getMessage().contains("never closed"));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: \"x\" y\n")).getMessage().contains("after the closing quote"));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("? a\n: b\n")).getMessage().contains("complex keys"));
    }

    @Test
    void hostileInputFailsCleanly() {
        // a "billion laughs": each level repeats the one before ten times
        StringBuilder bomb = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i < 12; i++) {
            bomb.append("a").append(i).append(": &a").append(i).append(" [");
            for (int k = 0; k < 10; k++) bomb.append(k == 0 ? "" : ", ").append("*a").append(i - 1);
            bomb.append("]\n");
        }
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse(bomb.toString())).getMessage().contains("too many values"));

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) deep.append(" ".repeat(i)).append("k:\n");
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse(deep.toString())).getMessage().contains("nested too deeply"));
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: " + "[".repeat(5000))).getMessage().length() > 0);
        assertTrue(assertThrows(YamlException.class, () -> Yaml.parse("a: " + "[".repeat(5000) + "]".repeat(5000))).getMessage().contains("nested too deeply"));
    }

    @Test
    void resultsUseTheSameTypesAsTheJsonParser() throws Exception {
        Object v = Yaml.parse("n: 12\ns: x\nb: true\nl: [1]\nm: {a: 1}\n");
        Map<?, ?> m = (Map<?, ?>) v;
        assertTrue(m.get("n") instanceof Json.Num && m.get("s") instanceof String && m.get("b") instanceof Boolean
                && m.get("l") instanceof List<?> && m.get("m") instanceof Map<?, ?>);
        assertEquals(Json.write(Json.parse("{\"n\":12,\"s\":\"x\",\"b\":true,\"l\":[1],\"m\":{\"a\":1}}"), false), Json.write(v, false));
    }
}
