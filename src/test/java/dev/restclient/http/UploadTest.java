package dev.restclient.http;

import dev.restclient.model.BodyType;
import dev.restclient.model.Environment;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.testkit.Test;
import dev.restclient.vars.VariableResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertNull;
import static dev.restclient.testkit.Assertions.assertThrows;
import static dev.restclient.testkit.Assertions.assertTrue;

class UploadTest {

    private static String header(PreparedRequest p, String name) {
        for (KeyValue kv : p.headers) if (kv.key.equalsIgnoreCase(name)) return kv.value;
        return null;
    }

    private static VariableResolver vars(Path dir) {
        Environment e = new Environment();
        e.variables.add(new Variable("dir", dir.toString()));
        e.variables.add(new Variable("who", "ann"));
        e.variables.add(new Variable("pw", "hunter2", true));
        return new VariableResolver(e, null, null);
    }

    @Test
    void multipartBodyHasTextAndFilePartsWithTheRightBytes() throws Exception {
        Path dir = Files.createTempDirectory("rc-upload");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff};
        Files.write(dir.resolve("pic.png"), png);
        RequestDef r = new RequestDef();
        r.url = "https://example.com/upload";
        r.bodyType = BodyType.MULTIPART;
        r.multipart.add(new KeyValue("user", "{{who}}"));
        r.multipart.add(new KeyValue("skipped", "x", false));
        r.multipart.add(KeyValue.file("photo", "{{dir}}/pic.png", true));
        r.headers.add(new KeyValue("Content-Type", "multipart/form-data")); // typed by hand, without a boundary

        PreparedRequest p = PreparedRequest.prepare(r, vars(dir));
        String ct = header(p, "Content-Type");
        assertTrue(ct.startsWith("multipart/form-data; boundary=----RestClientBoundary"), ct);
        assertEquals(1L, p.headers.stream().filter(h -> h.key.equalsIgnoreCase("Content-Type")).count(), "one Content-Type");
        String b = p.boundary;
        String sent = new String(p.bodyBytes, StandardCharsets.ISO_8859_1);
        String expected = "--" + b + "\r\nContent-Disposition: form-data; name=\"user\"\r\n\r\nann\r\n"
                + "--" + b + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"pic.png\"\r\nContent-Type: image/png\r\n\r\n"
                + new String(png, StandardCharsets.ISO_8859_1) + "\r\n--" + b + "--\r\n";
        assertEquals(expected, sent);
        assertTrue(p.body.contains("(contents of pic.png, 8 B)"), p.body);
        assertFalse(p.body.contains("skipped"));
    }

    @Test
    void theShownCopyUsesTheSameBoundaryMasksSecretsAndReadsNoFiles() throws Exception {
        Path dir = Files.createTempDirectory("rc-upload");
        Files.writeString(dir.resolve("a.txt"), "hello");
        RequestDef r = new RequestDef();
        r.url = "https://example.com/upload";
        r.bodyType = BodyType.MULTIPART;
        r.multipart.add(new KeyValue("password", "{{pw}}"));
        r.multipart.add(KeyValue.file("doc", dir.resolve("a.txt").toString(), true));
        PreparedRequest sent = PreparedRequest.prepare(r, vars(dir));
        Files.delete(dir.resolve("a.txt")); // the shown copy must not need it
        PreparedRequest shown = PreparedRequest.prepareMasked(r, vars(dir), sent);
        assertEquals(header(sent, "Content-Type"), header(shown, "Content-Type"));
        assertNull(shown.bodyBytes);
        assertTrue(shown.body.contains(VariableResolver.MASK) && !shown.body.contains("hunter2"), shown.body);
        assertTrue(shown.body.contains("(contents of a.txt)"), shown.body);
        assertTrue(new String(sent.bodyBytes, StandardCharsets.UTF_8).contains("hunter2"), "the real request has the real value");
    }

    @Test
    void namesCannotBreakOutOfThePartHeader() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://example.com/";
        r.bodyType = BodyType.MULTIPART;
        r.multipart.add(new KeyValue("a\"\r\nX-Injected: 1", "v"));
        String sent = new String(PreparedRequest.prepare(r, VariableResolver.empty()).bodyBytes, StandardCharsets.UTF_8);
        assertFalse(sent.contains("\r\nX-Injected"), sent);
        assertTrue(sent.contains("name=\"a%22%0D%0AX-Injected: 1\""), sent);
    }

    @Test
    void aFileBodyIsSentAsIsWithATypeFromItsExtension() throws Exception {
        Path dir = Files.createTempDirectory("rc-upload");
        Files.writeString(dir.resolve("data.json"), "{\"a\":1}");
        RequestDef r = new RequestDef();
        r.url = "https://example.com/";
        r.bodyType = BodyType.BINARY;
        r.bodyFile = "{{dir}}/data.json";
        PreparedRequest p = PreparedRequest.prepare(r, vars(dir));
        assertEquals("{\"a\":1}", new String(p.bodyBytes, StandardCharsets.UTF_8));
        assertEquals("application/json", header(p, "Content-Type"));
        assertEquals("(contents of data.json, 7 B)", p.body);

        r.headers.add(new KeyValue("Content-Type", "application/x-custom"));
        assertEquals("application/x-custom", header(PreparedRequest.prepare(r, vars(dir)), "Content-Type"), "your own header wins");
        assertEquals("application/octet-stream", MediaTypes.forFile("/x/archive.bin"));
        assertEquals("application/octet-stream", MediaTypes.forFile("/dir.with.dots/noext"));
    }

    @Test
    void missingOrUnchosenFilesAreReportedAndAreNotUrlProblems() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://example.com/";
        r.bodyType = BodyType.BINARY;
        PreparedRequest.InvalidRequestException none = assertThrows(PreparedRequest.InvalidRequestException.class,
                () -> PreparedRequest.prepare(r, VariableResolver.empty()));
        assertTrue(none.getMessage().contains("Choose a file"), none.getMessage());
        assertFalse(none.urlProblem);

        r.bodyFile = "/definitely/not/here.bin";
        PreparedRequest.InvalidRequestException missing = assertThrows(PreparedRequest.InvalidRequestException.class,
                () -> PreparedRequest.prepare(r, VariableResolver.empty()));
        assertTrue(missing.getMessage().contains("no file at"), missing.getMessage());

        r.bodyType = BodyType.MULTIPART;
        r.multipart.add(KeyValue.file("f", "", true));
        assertTrue(assertThrows(PreparedRequest.InvalidRequestException.class, () -> PreparedRequest.prepare(r, VariableResolver.empty()))
                .getMessage().contains("“f”"));
        RequestDef bad = new RequestDef();
        assertTrue(assertThrows(PreparedRequest.InvalidRequestException.class, () -> PreparedRequest.prepare(bad, VariableResolver.empty())).urlProblem);
    }

    @Test
    void copiesAndComparisonsIncludeTheNewFields() {
        RequestDef r = new RequestDef();
        r.multipart.add(KeyValue.file("f", "/a", true));
        r.bodyFile = "/b";
        r.captures.add(new KeyValue("token", "body.token"));
        RequestDef c = r.copy();
        assertTrue(r.sameContent(c));
        assertTrue(c.multipart.get(0).file);
        c.multipart.get(0).file = false;
        assertFalse(r.sameContent(c), "text vs file is a change");
        c = r.copy();
        c.captures.get(0).value = "status";
        assertFalse(r.sameContent(c));
        c = r.copy();
        c.bodyFile = "/c";
        assertFalse(r.sameContent(c));
    }
}
