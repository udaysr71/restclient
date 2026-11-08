package dev.restclient.ui;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A plain-text code editor with JSON / XML / HTML syntax colouring, built on {@link JTextArea}
 * (so undo, selection, highlights and variable chips work as usual). Colouring is done per visible
 * line at paint time, so large documents stay fast. Includes find with match highlighting.
 */
public class CodeArea extends JTextArea {
    public enum Lang { NONE, JSON, XML, HTML }

    private Lang lang = Lang.NONE;
    private final List<Object> marks = new ArrayList<>();
    private int version;

    public CodeArea() {
        super();
        init();
    }

    public CodeArea(int rows, int columns) {
        super(rows, columns);
        init();
    }

    private void init() {
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                version++;
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                version++;
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    @Override
    public void updateUI() {
        setUI(new SyntaxUI());
        setFont(Theme.mono(13f));
        setBackground(Theme.editorBg());
        setForeground(Theme.text());
        setCaretColor(Theme.text());
        setSelectionColor(Theme.textSelection());
        setSelectedTextColor(Theme.text());
        setTabSize(2);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    public void setLanguage(Lang l) {
        lang = l == null ? Lang.NONE : l;
        version++;
        repaint();
    }

    public Lang language() {
        return lang;
    }

    // ---- find ------------------------------------------------------------------------------

    /** Highlights every match and returns how many there are. */
    public int markAll(String query, boolean matchCase) {
        clearMarks();
        if (query == null || query.isEmpty()) return 0;
        String text = matchCase ? getText() : getText().toLowerCase(Locale.ROOT);
        String q = matchCase ? query : query.toLowerCase(Locale.ROOT);
        DefaultHighlighter.DefaultHighlightPainter p = new DefaultHighlighter.DefaultHighlightPainter(Theme.findMatch());
        int n = 0;
        for (int i = text.indexOf(q); i >= 0 && n < 10_000; i = text.indexOf(q, i + q.length())) {
            try {
                marks.add(getHighlighter().addHighlight(i, i + q.length(), p));
                n++;
            } catch (BadLocationException ignored) {
                break;
            }
        }
        return n;
    }

    public void clearMarks() {
        for (Object m : marks) getHighlighter().removeHighlight(m);
        marks.clear();
    }

    /** Selects the next (or previous) match after the caret, wrapping around. */
    public boolean find(String query, boolean matchCase, boolean forward) {
        if (query == null || query.isEmpty()) return false;
        String text = matchCase ? getText() : getText().toLowerCase(Locale.ROOT);
        String q = matchCase ? query : query.toLowerCase(Locale.ROOT);
        int i;
        if (forward) {
            i = text.indexOf(q, getSelectionEnd());
            if (i < 0) i = text.indexOf(q);
        } else {
            i = getSelectionStart() > 0 ? text.lastIndexOf(q, getSelectionStart() - 1) : -1;
            if (i < 0) i = text.lastIndexOf(q);
        }
        if (i < 0) return false;
        select(i, i + q.length());
        try {
            Rectangle2D r = modelToView2D(i);
            if (r != null) scrollRectToVisible(r.getBounds());
        } catch (BadLocationException ignored) {
            // ignore
        }
        return true;
    }

    // ---- colouring -------------------------------------------------------------------------

    private final class SyntaxUI extends BasicTextAreaUI {
        @Override
        public View create(Element e) {
            return getLineWrap() ? super.create(e) : new SyntaxView(e);
        }
    }

    private final class SyntaxView extends PlainView {
        private final Map<Integer, List<Lexer.Token>> cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, List<Lexer.Token>> e) {
                return size() > 400;
            }
        };
        private int cacheVersion = -1;

        SyntaxView(Element e) {
            super(e);
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1) throws BadLocationException {
            if (lang == Lang.NONE) return super.drawUnselectedText(g, x, y, p0, p1);
            Document doc = getDocument();
            Element root = doc.getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(p0));
            int ls = line.getStartOffset();
            int le = Math.min(line.getEndOffset() - 1, doc.getLength());
            if (cacheVersion != version) {
                cache.clear();
                cacheVersion = version;
            }
            List<Lexer.Token> tokens = cache.get(ls);
            if (tokens == null) {
                tokens = Lexer.lex(lang, doc.getText(ls, Math.max(0, le - ls)));
                cache.put(ls, tokens);
            }
            Segment seg = getLineBuffer();
            int from = p0 - ls;
            int to = p1 - ls;
            for (Lexer.Token t : tokens) {
                int s = Math.max(t.start(), from);
                int e = Math.min(t.end(), to);
                if (s >= e) continue;
                g.setColor(color(t.type()));
                doc.getText(ls + s, e - s, seg);
                x = Utilities.drawTabbedText(seg, x, y, g, this, ls + s);
            }
            return x;
        }

        private Color color(Lexer.Type t) {
            return switch (t) {
                case KEY -> Theme.synKey();
                case STRING -> Theme.synString();
                case NUMBER -> Theme.synNumber();
                case LITERAL -> Theme.synLiteral();
                case PUNCT -> Theme.synPunct();
                case TAG -> Theme.synTag();
                case ATTR -> Theme.synAttr();
                case COMMENT -> Theme.synComment();
                case PLAIN -> getForeground();
            };
        }
    }

    /** Wraps the area in a scroll pane with a line-number gutter. */
    public static JScrollPane scroll(CodeArea area, boolean lineNumbers) {
        JScrollPane sp = new JScrollPane(area);
        if (lineNumbers) sp.setRowHeaderView(new Gutter(area));
        Theme.bg(sp.getViewport(), Theme::editorBg);
        return sp;
    }

    /** Line numbers, right-aligned, only for the visible lines. */
    static final class Gutter extends JComponent {
        private final CodeArea area;

        Gutter(CodeArea area) {
            this.area = area;
            area.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    changed();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    changed();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
            area.addPropertyChangeListener("font", e -> changed());
        }

        private void changed() {
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            int lines = area.getLineCount();
            int digits = Math.max(2, String.valueOf(lines).length());
            FontMetrics fm = getFontMetrics(area.getFont().deriveFont(area.getFont().getSize2D() - 1f));
            return new Dimension(digits * fm.charWidth('0') + 22, area.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Rectangle clip = g.getClipBounds();
            g.setColor(Theme.editorBg());
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            Graphics2D g2 = (Graphics2D) g.create();
            dev.restclient.ui.laf.Paint.text(g2); // the OS setting (ClearType on Windows), like Swing's own text
            g2.setFont(area.getFont().deriveFont(area.getFont().getSize2D() - 1f));
            g2.setColor(Theme.disabledText());
            FontMetrics fm = g2.getFontMetrics();
            Element root = area.getDocument().getDefaultRootElement();
            int start = area.viewToModel2D(new Point(0, clip.y));
            int end = area.viewToModel2D(new Point(0, clip.y + clip.height));
            int first = root.getElementIndex(Math.max(0, start));
            int last = root.getElementIndex(Math.max(0, end));
            int areaAscent = area.getFontMetrics(area.getFont()).getAscent();
            for (int line = first; line <= last; line++) {
                try {
                    Rectangle2D r = area.modelToView2D(root.getElement(line).getStartOffset());
                    if (r == null) continue;
                    String n = String.valueOf(line + 1);
                    int x = getWidth() - 12 - fm.stringWidth(n);
                    g2.drawString(n, x, (int) r.getY() + areaAscent);
                } catch (BadLocationException ignored) {
                    // ignore
                }
            }
            g2.dispose();
        }
    }
}
