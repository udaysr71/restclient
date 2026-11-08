package dev.restclient.ui;

import dev.restclient.model.Settings;
import dev.restclient.testkit.AfterEach;
import dev.restclient.testkit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertNull;
import static dev.restclient.testkit.Assertions.assertTrue;

class ThemeFileTest {
    @AfterEach
    void reset() {
        Theme.usePalette(false);
    }

    private static void eq(int expected, Integer actual) {
        eq(expected, actual, "colour");
    }

    private static void eq(int expected, Integer actual, String what) {
        assertEquals((Object) Integer.valueOf(expected), (Object) actual, what);
    }

    @Test
    void colourFormats() {
        eq(0xFF112233, ThemeFile.color("#123"));
        eq(0x44112233, ThemeFile.color("#1234"));
        eq(0xFFABCDEF, ThemeFile.color("#abcdef"));
        eq(0x80ABCDEF, ThemeFile.color("#ABCDEF80"));
        eq(0xFF0A141E, ThemeFile.color("rgb(10, 20, 30)"));
        eq(0x800A141E, ThemeFile.color("rgba(10,20,30,0.5)"));
        eq(0x400A141E, ThemeFile.color("rgba(10, 20, 30, 25%)"));
        assertNull(ThemeFile.color("#12"));
        assertNull(ThemeFile.color("#GGGGGG"));
        assertNull(ThemeFile.color("rgb(300, 0, 0)"));
        assertNull(ThemeFile.color("rgba(0, 0, 0, 2)"));
        assertNull(ThemeFile.color("blue"));
    }

    @Test
    void parsesMetaVariablesCommentsAndMethodNames() {
        ThemeFile.Parsed p = ThemeFile.parse("""
                /* a comment with { braces } and ; */
                @theme { name: "Nord"; base: dark; toggle: 'nord-light' }
                :root {
                  --polar: #2E3440;
                  --panelBg: var(--polar);   /* own variable */
                  --editorBg: var( --panelBg );
                  --accent: #5E81AC;
                  --method-GET: #A3BE8C;
                }
                """);
        assertEquals("Nord", p.name());
        assertEquals("dark", p.base());
        assertEquals("nord-light", p.toggle());
        eq(0xFF2E3440, p.colors().get("panelBg"));
        eq(0xFF2E3440, p.colors().get("editorBg"));
        eq(0xFFA3BE8C, p.colors().get("method.GET"));
        assertFalse(p.colors().containsKey("polar"), "own variables aren't colours");
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
    }

    @Test
    void reportsProblemsWithoutFailing() {
        ThemeFile.Parsed p = ThemeFile.parse("""
                @theme { name: X; colour: red; }
                .button { padding: 4px; }
                :root {
                  --acent: #fff;
                  --text: notacolour;
                  --link: var(--missing);
                  --separator: var(--a);
                  --a: var(--separator);
                  --accent: #123456;
                  broken
                }
                """);
        String all = String.join("\n", p.warnings());
        assertTrue(all.contains("Did you mean --accent?"), all);
        assertTrue(all.contains("--text"), all);
        assertTrue(all.contains("var(--missing)"), all);
        assertTrue(all.contains("refers to itself"), all);
        assertTrue(all.contains(".button"), all);
        assertTrue(all.contains("colour"), all);
        assertTrue(all.contains("Missing ':'"), all);
        eq(0xFF123456, p.colors().get("accent"), "good lines still apply");
    }

    /** A theme file is untrusted input: only allow-listed shapes get through. */
    @Test
    void allowListsRejectEverythingElse() {
        ThemeFile.Parsed p = ThemeFile.parse("""
                @theme {
                  name: "<html><img src='http://example.com/x.png'>Evil";
                  base: ../../etc/passwd;
                  toggle: "C:\\Windows";
                  import: "http://example.com/x.css";
                }
                :root {
                  --accent: rgba(0, 0, 0, NaN);
                  --text: rgb(1e2, 0, 0);
                  --link: #12345;
                  --panelBg: url(http://example.com/x.png);
                  --menuBg: expression(alert(1));
                  --a b: #fff;
                  --separator: var(--x, #fff);
                  --x: #000;
                  --okFg: rgb( 1 , 2 , 3 );
                  --errFg: rgba(1, 2, 3, .5);
                  --infoFg: rgba(1, 2, 3, 100%);
                }
                """);
        assertEquals("htmlimg src'httpexample.comx.png'Evil", p.name(), "no < > = / : survive");
        assertFalse(p.name().toLowerCase().contains("<html"), "can't start Swing HTML");
        assertTrue(p.base() == null, "paths aren't theme ids");
        assertNull(p.toggle());
        for (String bad : List.of("accent", "text", "link", "panelBg", "menuBg", "separator")) {
            assertFalse(p.colors().containsKey(bad), bad + " should be rejected");
        }
        eq(0xFF010203, p.colors().get("okFg"));
        eq(0x80010203, p.colors().get("errFg"));
        eq(0xFF010203, p.colors().get("infoFg"));
        String all = String.join("\n", p.warnings());
        assertTrue(all.contains("import"), all);
        assertTrue(all.contains("base:"), all);
        assertTrue(all.contains("isn't a valid name"), all);
    }

    @Test
    void limitsBoundSizeAndDepth() {
        assertTrue(ThemeFile.parse("/*" + "x".repeat(ThemeFile.MAX_BYTES) + "*/").warnings().get(0).contains("larger than"));
        StringBuilder chain = new StringBuilder(":root { --accent: var(--v0);");
        for (int i = 0; i < 40; i++) chain.append(" --v").append(i).append(": var(--v").append(i + 1).append(");");
        chain.append(" --v40: #fff; }");
        ThemeFile.Parsed deep = ThemeFile.parse(chain.toString());
        assertFalse(deep.colors().containsKey("accent"), "deep var() chains stop");
        assertTrue(String.join("\n", deep.warnings()).contains("nest more than"), deep.warnings().toString());
        StringBuilder many = new StringBuilder(":root {");
        for (int i = 0; i < 1000; i++) many.append(" --v").append(i).append(": #fff;");
        assertTrue(String.join("\n", ThemeFile.parse(many.append(" }").toString()).warnings()).contains("declarations"));
        assertTrue(ThemeFile.isId("nord-light_2"));
        for (String bad : List.of("../x", "x.css", "X", "a b", "", "-x", "a/b", "a\\b")) assertFalse(ThemeFile.isId(bad), bad);
    }

    @Test
    void exportWritesEveryColourAndReadsBackIdentically() {
        for (Theme.Palette pal : Theme.Palette.builtIns()) {
            String css = ThemeFile.write(pal.label, pal.dark, pal::argb);
            ThemeFile.Parsed back = ThemeFile.parse(css);
            assertTrue(back.warnings().isEmpty(), pal.label + ": " + back.warnings());
            assertEquals(ThemeFile.tokens().size(), back.colors().size());
            for (String t : ThemeFile.tokens()) eq(pal.argb(t), back.colors().get(t), pal.label + " " + t);
            assertFalse(css.contains("\r"), "plain \\n line endings");
        }
    }

    @Test
    void userThemesLoadWithBaseChainsTogglesAndContrastWarnings() throws Exception {
        Path dir = Files.createTempDirectory("rc-themes");
        Path saved = Themes.dir();
        try {
            Themes.useDir(dir);
            Files.writeString(dir.resolve("nord.css"), """
                    @theme { name: "Nord"; base: dark; toggle: nord-light; }
                    :root { --panelBg: #2E3440; --accent: #5E81AC; }
                    """);
            Files.writeString(dir.resolve("nord-light.css"), """
                    @theme { name: "Nord Light"; base: nord; }
                    :root { --text: #3B4252; }
                    """);
            Files.writeString(dir.resolve("loop.css"), "@theme { base: loop2; } :root { --text: #000; }");
            Files.writeString(dir.resolve("loop2.css"), "@theme { base: loop; } :root { --text: #000; }");
            Files.writeString(dir.resolve("dark.css"), ":root { --accent: #FF0000; }");
            Files.writeString(dir.resolve("Bad Name.css"), ":root { --accent: #FF0000; }");
            Files.writeString(dir.resolve("huge.css"), ":root { --accent: #FF0000; }" + " ".repeat(ThemeFile.MAX_BYTES));
            try {
                Files.createSymbolicLink(dir.resolve("link.css"), dir.resolve("nord.css"));
            } catch (UnsupportedOperationException | java.io.IOException ignored) {
                // no symlinks on this file system
            }
            Themes.reload();

            Theme.Palette nord = Themes.find("nord");
            assertEquals("Nord", nord.label);
            assertTrue(nord.dark, "dark base");
            eq(0xFF2E3440, nord.argb("panelBg"));
            eq(Theme.Palette.DARK.argb("text"), nord.argb("text"), "unset colours come from the base");
            assertEquals(dir.resolve("nord.css"), nord.file);

            Theme.Palette light = Themes.find("nord-light");
            eq(0xFF2E3440, light.argb("panelBg"), "inherits from another user theme");
            assertTrue(light.warnings.stream().anyMatch(w -> w.startsWith("Low contrast: --text")), light.warnings.toString());

            Themes.Choice t = Themes.toggled(nord);
            assertEquals(Settings.ThemeMode.CUSTOM, t.mode());
            assertEquals("nord-light", t.custom());
            assertEquals(Settings.ThemeMode.LIGHT, Themes.toggled(light).mode(), "no toggle: dark theme → Light");
            assertEquals(Settings.ThemeMode.SOLARIZED_DARK, Themes.toggled(Theme.Palette.SEPIA).mode());

            assertTrue(java.util.stream.Stream.of("loop", "loop2").flatMap(id -> Themes.find(id).warnings.stream())
                    .anyMatch(w -> w.contains("leads back")), "cycles are caught");
            assertEquals("light", Theme.Palette.LIGHT.id);
            assertTrue(Themes.find("user-dark") != null, "a file can't replace a built-in theme");
            assertTrue(Themes.find("dark") == Theme.Palette.DARK);

            assertTrue(Themes.find("bad name") == null, "file names outside the allow-list are skipped");
            assertTrue(Themes.find("link") == null, "symlinks are skipped");
            assertTrue(Themes.find("huge").warnings.get(0).contains("larger than"), "oversized files aren't read");
            assertTrue(Themes.find("../nord") == null, "lookups only take ids");
            List<Themes.Choice> choices = Themes.choices();
            assertEquals("Match System", choices.get(0).label());
            assertTrue(choices.stream().anyMatch(c -> c.label().equals("Nord")));

            assertEquals(Theme.Palette.LIGHT, Theme.paletteFor(Settings.ThemeMode.CUSTOM, "gone"), "missing file → Light");

            Path out = Themes.export(Theme.Palette.SEPIA, "My Sepia!");
            assertEquals("my-sepia.css", out.getFileName().toString());
            assertEquals("my-sepia-2.css", Themes.export(Theme.Palette.SEPIA, "My Sepia!").getFileName().toString());
            Path odd = Themes.export(Theme.Palette.SEPIA, "x */ :root { --accent: #f00 } /*");
            ThemeFile.Parsed oddBack = ThemeFile.parse(Files.readString(odd));
            assertTrue(oddBack.warnings().isEmpty(), "a strange name can't break the written file: " + oddBack.warnings());
            Themes.reload();
            Theme.Palette mine = Themes.find("my-sepia");
            assertTrue(mine.warnings.isEmpty(), mine.warnings.toString());
            for (String tok : ThemeFile.tokens()) eq(Theme.Palette.SEPIA.argb(tok), mine.argb(tok), tok);
        } finally {
            Themes.useDir(saved);
            Themes.reload();
        }
    }
}
