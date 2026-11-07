package dev.restclient.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the app's own small SVG icons: {@code path}, {@code rect}, {@code circle} and {@code ellipse}
 * with stroke/fill attributes, and path commands M L H V C S Q T A Z. It's vector, so it stays sharp at any
 * scale. Palette colors are swapped for theme colors at paint time, so icons follow light/dark mode.
 */
public final class SvgIcon implements Icon {
    private static final Pattern ELEMENT = Pattern.compile("<(svg|path|rect|circle|ellipse)\\b([^>]*)>");
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z][\\w-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private record Part(Shape shape, String stroke, String fill, float strokeWidth) {
    }

    private final List<Part> parts = new ArrayList<>();
    private final int width;
    private final int height;
    private double viewWidth = 16;
    private double viewHeight = 16;

    public SvgIcon(String resource, int width, int height) {
        this.width = width;
        this.height = height;
        try (InputStream in = SvgIcon.class.getResourceAsStream("/" + resource)) {
            if (in != null) parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // a broken icon paints nothing rather than breaking the UI
        }
    }

    private void parse(String svg) {
        Map<String, String> root = new HashMap<>();
        Matcher m = ELEMENT.matcher(svg);
        while (m.find()) {
            Map<String, String> a = attributes(m.group(2));
            String tag = m.group(1);
            if (tag.equals("svg")) {
                root = a;
                String vb = a.get("viewBox");
                if (vb != null) {
                    double[] v = numbers(vb);
                    if (v.length == 4) {
                        viewWidth = v[2];
                        viewHeight = v[3];
                    }
                }
                continue;
            }
            Shape shape = switch (tag) {
                case "path" -> path(a.getOrDefault("d", ""));
                case "rect" -> new RoundRectangle2D.Double(d(a, "x"), d(a, "y"), d(a, "width"), d(a, "height"),
                        2 * d(a, "rx"), 2 * (a.containsKey("ry") ? d(a, "ry") : d(a, "rx")));
                case "circle" -> new Ellipse2D.Double(d(a, "cx") - d(a, "r"), d(a, "cy") - d(a, "r"), 2 * d(a, "r"), 2 * d(a, "r"));
                case "ellipse" -> new Ellipse2D.Double(d(a, "cx") - d(a, "rx"), d(a, "cy") - d(a, "ry"), 2 * d(a, "rx"), 2 * d(a, "ry"));
                default -> null;
            };
            if (shape == null) continue;
            String stroke = a.getOrDefault("stroke", root.getOrDefault("stroke", "none"));
            String fill = a.getOrDefault("fill", root.getOrDefault("fill", "none"));
            float sw = (float) parseOr(a.getOrDefault("stroke-width", root.getOrDefault("stroke-width", "1")), 1);
            parts.add(new Part(shape, stroke, fill, sw));
        }
    }

    private static Map<String, String> attributes(String s) {
        Map<String, String> out = new HashMap<>();
        Matcher m = ATTR.matcher(s);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static double d(Map<String, String> a, String key) {
        return parseOr(a.get(key), 0);
    }

    private static double parseOr(String s, double fallback) {
        try {
            return s == null ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double[] numbers(String s) {
        Matcher m = NUMBER.matcher(s);
        List<Double> out = new ArrayList<>();
        while (m.find()) out.add(Double.parseDouble(m.group()));
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    // ---- path data -------------------------------------------------------------------------

    /** Parses SVG path data into a Java2D path. */
    static Path2D path(String data) {
        Path2D.Double p = new Path2D.Double();
        Tokens t = new Tokens(data);
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        double lastCx = 0;
        double lastCy = 0;
        char cmd = 0;
        char prev = 0;
        boolean started = false;
        while (t.more()) {
            if (t.isCommand()) cmd = t.command();
            else if (cmd == 0) break;
            boolean rel = Character.isLowerCase(cmd);
            char c = Character.toUpperCase(cmd);
            double bx = rel ? x : 0;
            double by = rel ? y : 0;
            switch (c) {
                case 'M' -> {
                    x = bx + t.num();
                    y = by + t.num();
                    p.moveTo(x, y);
                    startX = x;
                    startY = y;
                    started = true;
                    cmd = rel ? 'l' : 'L'; // further pairs are line-tos
                }
                case 'L' -> {
                    x = bx + t.num();
                    y = by + t.num();
                    lineTo(p, x, y, started);
                    started = true;
                }
                case 'H' -> {
                    x = bx + t.num();
                    lineTo(p, x, y, started);
                }
                case 'V' -> {
                    y = by + t.num();
                    lineTo(p, x, y, started);
                }
                case 'C' -> {
                    double x1 = bx + t.num();
                    double y1 = by + t.num();
                    double x2 = bx + t.num();
                    double y2 = by + t.num();
                    x = bx + t.num();
                    y = by + t.num();
                    p.curveTo(x1, y1, x2, y2, x, y);
                    lastCx = x2;
                    lastCy = y2;
                }
                case 'S' -> {
                    boolean smooth = prev == 'C' || prev == 'S';
                    double x1 = smooth ? 2 * x - lastCx : x;
                    double y1 = smooth ? 2 * y - lastCy : y;
                    double x2 = bx + t.num();
                    double y2 = by + t.num();
                    x = bx + t.num();
                    y = by + t.num();
                    p.curveTo(x1, y1, x2, y2, x, y);
                    lastCx = x2;
                    lastCy = y2;
                }
                case 'Q' -> {
                    double x1 = bx + t.num();
                    double y1 = by + t.num();
                    x = bx + t.num();
                    y = by + t.num();
                    p.quadTo(x1, y1, x, y);
                    lastCx = x1;
                    lastCy = y1;
                }
                case 'T' -> {
                    boolean smooth = prev == 'Q' || prev == 'T';
                    double x1 = smooth ? 2 * x - lastCx : x;
                    double y1 = smooth ? 2 * y - lastCy : y;
                    x = bx + t.num();
                    y = by + t.num();
                    p.quadTo(x1, y1, x, y);
                    lastCx = x1;
                    lastCy = y1;
                }
                case 'A' -> {
                    double rx = t.num();
                    double ry = t.num();
                    double rot = t.num();
                    boolean large = t.flag();
                    boolean sweep = t.flag();
                    double nx = bx + t.num();
                    double ny = by + t.num();
                    arcTo(p, x, y, rx, ry, rot, large, sweep, nx, ny);
                    x = nx;
                    y = ny;
                }
                case 'Z' -> {
                    p.closePath();
                    x = startX;
                    y = startY;
                }
                default -> {
                    return p; // unsupported command: stop rather than guess
                }
            }
            prev = c;
            if (c == 'Z') cmd = 0;
        }
        return p;
    }

    private static void lineTo(Path2D p, double x, double y, boolean started) {
        if (started || p.getCurrentPoint() != null) p.lineTo(x, y);
        else p.moveTo(x, y);
    }

    /** SVG elliptical arc (endpoint form) appended as Java2D curves. */
    static void arcTo(Path2D p, double x0, double y0, double rx, double ry, double angleDeg, boolean large, boolean sweep,
                      double x, double y) {
        if (x0 == x && y0 == y) return;
        if (rx == 0 || ry == 0) {
            p.lineTo(x, y);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double angle = Math.toRadians(angleDeg % 360);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double dx2 = (x0 - x) / 2;
        double dy2 = (y0 - y) / 2;
        double x1 = cos * dx2 + sin * dy2;
        double y1 = -sin * dx2 + cos * dy2;
        double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
        double den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cx1 = coef * (rx * y1 / ry);
        double cy1 = coef * -(ry * x1 / rx);
        double cx = cos * cx1 - sin * cy1 + (x0 + x) / 2;
        double cy = sin * cx1 + cos * cy1 + (y0 + y) / 2;
        double ux = (x1 - cx1) / rx;
        double uy = (y1 - cy1) / ry;
        double vx = (-x1 - cx1) / rx;
        double vy = (-y1 - cy1) / ry;
        double start = Math.toDegrees(Math.atan2(uy, ux));
        double extent = Math.toDegrees(Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy));
        if (!sweep && extent > 0) extent -= 360;
        else if (sweep && extent < 0) extent += 360;
        // Java2D angles go counter-clockwise with y up, so negate.
        Arc2D arc = new Arc2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry, -start, -extent, Arc2D.OPEN);
        AffineTransform at = AffineTransform.getRotateInstance(angle, cx, cy);
        p.append(at.createTransformedShape(arc).getPathIterator(null), true);
    }

    private static final class Tokens {
        private final String s;
        private int i;

        Tokens(String s) {
            this.s = s;
        }

        private void skip() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
        }

        boolean more() {
            skip();
            return i < s.length();
        }

        boolean isCommand() {
            skip();
            return i < s.length() && Character.isLetter(s.charAt(i)) && s.charAt(i) != 'e' && s.charAt(i) != 'E';
        }

        char command() {
            skip();
            return s.charAt(i++);
        }

        double num() {
            skip();
            Matcher m = NUMBER.matcher(s);
            if (!m.find(i) || m.start() != i) throw new IllegalArgumentException("Bad number in path at " + i);
            i = m.end();
            return Double.parseDouble(m.group());
        }

        boolean flag() {
            skip();
            char c = s.charAt(i++);
            return c == '1';
        }
    }

    // ---- painting --------------------------------------------------------------------------

    private static Color color(String spec) {
        if (spec == null || spec.equals("none")) return null;
        return switch (spec.toUpperCase()) {
            case "#6E6E6E" -> Theme.iconColor();
            case "#389FD6" -> Theme.accent();
            case "#59A869" -> Theme.okFg();
            case "#EDA200" -> Theme.warnIcon();
            case "#DB5860" -> Theme.errFg();
            case "#FFFFFF" -> Color.WHITE;
            default -> Theme.hex(spec, Theme.iconColor());
        };
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            g2.scale(width / viewWidth, height / viewHeight);
            if (c != null && !c.isEnabled()) g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.4f));
            for (Part part : parts) {
                Color fill = color(part.fill);
                if (fill != null) {
                    g2.setColor(fill);
                    g2.fill(part.shape);
                }
                Color stroke = color(part.stroke);
                if (stroke != null) {
                    g2.setColor(stroke);
                    g2.setStroke(new BasicStroke(part.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(part.shape);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }
}
