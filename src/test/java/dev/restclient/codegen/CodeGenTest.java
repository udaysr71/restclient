package dev.restclient.codegen;

import dev.restclient.codegen.CodeGen.Language;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.Environment;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.testkit.Test;
import dev.restclient.vars.VariableResolver;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertTrue;

class CodeGenTest {
    private static final String NASTY = "it's \"quoted\" \\ back\nnew $HOME `cmd` ${x} ’curly’ é";

    private static VariableResolver vars() {
        Environment e = new Environment();
        e.variables.add(new Variable("baseUrl", "https://api.example.com"));
        e.variables.add(new Variable("token", "tok123", true));
        return new VariableResolver(e, null, null);
    }

    private static RequestDef json() {
        RequestDef r = new RequestDef();
        r.method = HttpMethod.POST;
        r.url = "{{baseUrl}}/orders?q=a b";
        r.headers.add(new KeyValue("X-Note", NASTY.replace("\n", " ")));
        r.headers.add(new KeyValue("X-Off", "no", false));
        r.auth.type = Auth.Type.BEARER;
        r.auth.token = "{{token}}";
        r.bodyType = BodyType.JSON;
        r.body = "{\"note\": \"" + "it's \\\"x\\\"" + "\"}\n";
        r.timeoutSeconds = 12;
        return r;
    }

    private static RequestDef multipart() {
        RequestDef r = new RequestDef();
        r.method = HttpMethod.POST;
        r.url = "{{baseUrl}}/upload";
        r.bodyType = BodyType.MULTIPART;
        r.multipart.add(new KeyValue("note", NASTY));
        r.multipart.add(KeyValue.file("photo", "/tmp/my \"pics\"/a.png", true));
        return r;
    }

    private static void compiles(String source) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) return; // running on a JRE: nothing to compile with
        Path dir = Files.createTempDirectory("rc-codegen");
        Files.writeString(dir.resolve("Main.java"), source);
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int rc = javac.run(null, null, err, "-d", dir.toString(), dir.resolve("Main.java").toString());
        assertEquals(0, rc, "generated Java must compile:\n" + err + "\n" + source);
    }

    @Test
    void javaCompilesForEveryBodyKind() throws Exception {
        String code = CodeGen.generate(Language.JAVA, json(), vars(), true);
        assertTrue(code.contains("URI.create(\"https://api.example.com/orders?q=a%20b\")"), code);
        assertTrue(code.contains(".header(\"Authorization\", \"Bearer tok123\")"), code);
        assertTrue(code.contains(".timeout(Duration.ofSeconds(12))"), code);
        assertTrue(code.contains(".method(\"POST\", HttpRequest.BodyPublishers.ofString("), code);
        assertFalse(code.contains("X-Off"));
        assertTrue(code.contains("skips TLS verification"), "insecure is called out, not reproduced");
        compiles(code);
        compiles(CodeGen.generate(Language.JAVA, multipart(), vars(), false));
        RequestDef file = new RequestDef();
        file.url = "example.com";
        file.method = HttpMethod.PUT;
        file.bodyType = BodyType.BINARY;
        file.bodyFile = "C:\\data\\in.bin";
        String f = CodeGen.generate(Language.JAVA, file, null, false);
        assertTrue(f.contains("BodyPublishers.ofFile(Path.of(\"C:\\\\data\\\\in.bin\"))") && f.contains("\"http://example.com\""), f);
        compiles(f);
        RequestDef form = new RequestDef();
        form.url = "https://example.com";
        form.bodyType = BodyType.FORM;
        form.form.add(new KeyValue("a b", "c&d"));
        String fo = CodeGen.generate(Language.JAVA, form, null, false);
        assertTrue(fo.contains("ofString(\"a+b=c%26d\")") && fo.contains("application/x-www-form-urlencoded"), fo);
        compiles(fo);
        RequestDef get = new RequestDef();
        get.url = "https://example.com";
        get.headers.add(new KeyValue("Host", "x")); // HttpClient refuses this one
        String g = CodeGen.generate(Language.JAVA, get, null, false);
        assertFalse(g.contains("\"Host\""));
        assertTrue(g.contains("BodyPublishers.noBody()"));
        compiles(g);
    }

    @Test
    void everythingFromTheRequestIsAnEscapedLiteral() {
        String q = CodeGen.q(NASTY);
        assertEquals("\"it's \\\"quoted\\\" \\\\ back\\nnew $HOME `cmd` ${x} ’curly’ é\"", q);
        assertEquals("'it''s \"quoted\" \\ back\nnew $HOME `cmd` ${x} ’’curly’’ é'", CodeGen.ps(NASTY),
                "PowerShell: single quotes, straight and curly, are doubled; nothing else is special");
        for (Language l : Language.values()) {
            String code = CodeGen.generate(l, multipart(), vars(), false);
            assertFalse(code.contains("it's \"quoted\""), l + " must not contain the raw text:\n" + code);
        }
    }

    @Test
    void python() {
        String code = CodeGen.generate(Language.PYTHON, json(), vars(), true);
        assertTrue(code.startsWith("import requests\n"), code);
        assertTrue(code.contains("    \"POST\",\n    \"https://api.example.com/orders?q=a%20b\",\n"), code);
        assertTrue(code.contains("\"Authorization\": \"Bearer tok123\","), code);
        assertTrue(code.contains(".encode(\"utf-8\"),"), code);
        assertTrue(code.contains("timeout=12,") && code.contains("verify=False,  #"), code);
        String m = CodeGen.generate(Language.PYTHON, multipart(), vars(), false);
        assertTrue(m.contains("(\"photo\", (\"a.png\", open(\"/tmp/my \\\"pics\\\"/a.png\", \"rb\"), \"image/png\")),"), m);
        assertTrue(m.contains("(\"note\", (None, "), m);
        assertFalse(m.contains("Content-Type"), "requests writes the multipart header itself");
    }

    @Test
    void javascript() {
        String code = CodeGen.generate(Language.JAVASCRIPT, json(), vars(), false);
        assertTrue(code.contains("await fetch(\"https://api.example.com/orders?q=a%20b\", {\n  method: \"POST\","), code);
        assertTrue(code.contains("[\"Content-Type\", \"application/json\"],"), code);
        assertTrue(code.contains("signal: AbortSignal.timeout(12000),"), code);
        String m = CodeGen.generate(Language.JAVASCRIPT, multipart(), vars(), false);
        assertTrue(m.contains("import { openAsBlob } from \"node:fs\";"), m);
        assertTrue(m.contains("form.append(\"photo\", await openAsBlob(") && m.contains("body: form,"), m);
        RequestDef form = new RequestDef();
        form.url = "https://example.com";
        form.method = HttpMethod.POST;
        form.bodyType = BodyType.FORM;
        form.form.add(new KeyValue("a", "1"));
        String f = CodeGen.generate(Language.JAVASCRIPT, form, null, false);
        assertTrue(f.contains("body: new URLSearchParams([\n    [\"a\", \"1\"],\n  ]),"), f);
        assertFalse(f.contains("headers"), "URLSearchParams sets the type itself");
    }

    @Test
    void powershell() {
        String code = CodeGen.generate(Language.POWERSHELL, json(), vars(), true);
        assertTrue(code.contains("$headers = @{\n    'X-Note' = 'it''s"), code);
        assertTrue(code.contains("    'Authorization' = 'Bearer tok123'\n}"), code);
        assertTrue(code.contains("-Uri 'https://api.example.com/orders?q=a%20b' `\n    -Method POST `\n    -Headers $headers `\n    -ContentType 'application/json' `\n    -Body '"), code);
        assertTrue(code.contains("-TimeoutSec 12") && code.contains("-SkipCertificateCheck"), code);
        String m = CodeGen.generate(Language.POWERSHELL, multipart(), vars(), false);
        assertTrue(m.contains("'photo' = Get-Item -LiteralPath '/tmp/my \"pics\"/a.png'") && m.contains("-Form $form"), m);
        RequestDef basic = new RequestDef();
        basic.url = "https://example.com";
        basic.auth.type = Auth.Type.BASIC;
        basic.auth.username = "u";
        basic.auth.password = "p";
        assertTrue(CodeGen.generate(Language.POWERSHELL, basic, null, false).contains("'Authorization' = 'Basic dTpw'"));
    }
}
