package dev.restclient.ui;

import dev.restclient.testkit.AfterEach;
import dev.restclient.testkit.Test;

import java.util.List;

import static dev.restclient.testkit.Assertions.assertTrue;

/**
 * WCAG 2.1 AA contrast for every palette that ships with the app: 4.5:1 for text (including text on the accent
 * colour, status badges, syntax and method colours) and 3:1 for input outlines and the focus ring (1.4.11).
 * The checks themselves live in {@link Contrast}, which Settings also uses to warn about user themes.
 */
class ContrastTest {
    @AfterEach
    void reset() {
        Theme.usePalette(false);
    }

    @Test
    void lightPaletteMeetsAA() {
        check(Theme.Palette.LIGHT);
    }

    @Test
    void darkPaletteMeetsAA() {
        check(Theme.Palette.DARK);
    }

    @Test
    void solarizedSepiaMeetsAA() {
        check(Theme.Palette.SEPIA);
    }

    @Test
    void solarizedDarkMeetsAA() {
        check(Theme.Palette.SOLARIZED_DARK);
    }

    /** The bundled theme files read cleanly: no unknown names, bad values or broken var() references. */
    @Test
    void bundledThemeFilesHaveNoProblems() {
        for (Theme.Palette p : List.of(Theme.Palette.SEPIA, Theme.Palette.SOLARIZED_DARK)) {
            assertTrue(p.warnings.isEmpty(), p.label + ": " + p.warnings);
        }
    }

    /** Every colour name a theme file can set is a real colour in Theme. */
    @Test
    void everyThemeTokenResolves() {
        for (String t : ThemeFile.tokens()) assertTrue(Theme.colorOf(t) != null, t);
    }

    private static void check(Theme.Palette palette) {
        List<String> failures = Contrast.check(palette);
        assertTrue(failures.isEmpty(), palette.label + " palette: " + failures);
    }
}
