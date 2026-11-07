package dev.restclient.ui;

import dev.restclient.testkit.Test;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertTrue;

/** The in-house SVG path parser and syntax lexers. Headless. */
class RenderingHelpersTest {

    @Test
    void svgPathWithLinesAndRelativeCommands() {
        Rectangle2D b = SvgIcon.path("M3 8h10M8 3v10").getBounds2D();
        assertEquals(3.0, b.getMinX());
        assertEquals(13.0, b.getMaxX());
        assertEquals(3.0, b.getMinY());
        assertEquals(13.0, b.getMaxY());
    }

    @Test
    void svgArcIsAHalfCircle() {
        // "M5.5 7V5a2.5 2.5 0 0 1 5 0" — the lock shackle: an arc over the top from (5.5,5) to (10.5,5)
        Path2D p = SvgIcon.path("M5.5 7V5a2.5 2.5 0 0 1 5 0v2");
        Rectangle2D b = p.getBounds2D();
        assertTrue(Math.abs(b.getMinY() - 2.5) < 0.15, "arc reaches y=2.5 but was " + b.getMinY());
        assertTrue(Math.abs(b.getMinX() - 5.5) < 0.01 && Math.abs(b.getMaxX() - 10.5) < 0.01, b.toString());
    }

    @Test
    void svgCurvesAndClose() {
        Rectangle2D b = SvgIcon.path("M8 1.8l5.5 2v4.2c0 3-2.3 5.3-5.5 6.2-3.2-.9-5.5-3.2-5.5-6.2V3.8z").getBounds2D();
        assertTrue(b.getMinX() >= 2.4 && b.getMaxX() <= 13.6 && b.getMaxY() <= 14.3, b.toString());
    }

    @Test
    void jsonLexerFindsKeysStringsNumbersAndLiterals() {
        List<Lexer.Token> t = Lexer.json("  \"id\": \"x\\\"y\", \"n\": -1.5e3, \"ok\": true");
        assertEquals(Lexer.Type.KEY, typeAt(t, 3));
        assertEquals(Lexer.Type.STRING, typeAt(t, 10));
        assertEquals(Lexer.Type.NUMBER, typeAt(t, 24));
        assertEquals(Lexer.Type.LITERAL, typeAt(t, 38));
        assertEquals(39, t.get(t.size() - 1).end());
    }

    @Test
    void markupLexerFindsTagsAttributesAndComments() {
        String s = "<a href=\"x\">text</a><!-- c -->";
        List<Lexer.Token> t = Lexer.markup(s);
        assertEquals(Lexer.Type.TAG, typeAt(t, 1));
        assertEquals(Lexer.Type.ATTR, typeAt(t, 4));
        assertEquals(Lexer.Type.STRING, typeAt(t, 9));
        assertEquals(Lexer.Type.PLAIN, typeAt(t, 13));
        assertEquals(Lexer.Type.COMMENT, typeAt(t, 23));
    }

    private static Lexer.Type typeAt(List<Lexer.Token> tokens, int offset) {
        for (Lexer.Token t : tokens) if (offset >= t.start() && offset < t.end()) return t.type();
        return null;
    }
}
