package dev.restclient.ui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.function.DoubleConsumer;

/**
 * Small, quick animations that make changes easy to follow: things slide to where they're going, appear with a
 * short fade, and theme switches cross-fade. Durations stay between 120 and 260 ms with an ease-out curve, the
 * range macOS and Fluent use, so nothing ever makes you wait. Turned off by Settings › Reduce motion or
 * {@code -Drestclient.reduceMotion=true}.
 */
public final class Motion {
    /** 60 frames a second. */
    private static final int FRAME_MS = 16;
    private static boolean reduced = Boolean.getBoolean("restclient.reduceMotion");

    private Motion() {
    }

    public static boolean enabled() {
        return !reduced && !GraphicsEnvironment.isHeadless();
    }

    public static void setReduced(boolean r) {
        reduced = r || Boolean.getBoolean("restclient.reduceMotion");
    }

    /** Ease-out cubic: fast start, gentle landing. */
    public static double easeOut(double t) {
        double u = 1 - t;
        return 1 - u * u * u;
    }

    /** A handle to stop an animation early (e.g. when a new one replaces it). */
    public interface Handle {
        void stop();
    }

    /**
     * Calls {@code frame} with eased progress 0→1 on the UI thread over {@code ms} milliseconds, then {@code done}.
     * With motion off, jumps straight to 1.
     */
    public static Handle animate(int ms, DoubleConsumer frame, Runnable done) {
        if (!enabled() || ms <= 0) {
            frame.accept(1);
            if (done != null) done.run();
            return () -> { };
        }
        long start = System.nanoTime();
        Timer t = new Timer(FRAME_MS, null);
        t.addActionListener(e -> {
            double p = Math.min(1, (System.nanoTime() - start) / 1e6 / ms);
            frame.accept(easeOut(p));
            if (p >= 1) {
                t.stop();
                if (done != null) done.run();
            }
        });
        t.setInitialDelay(0);
        t.start();
        frame.accept(0);
        return t::stop;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ---- window fades -----------------------------------------------------------------------

    private static boolean canFade(Window w) {
        if (!enabled()) return false;
        try {
            GraphicsDevice d = w.getGraphicsConfiguration() != null ? w.getGraphicsConfiguration().getDevice()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (!d.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT)) return false;
            // Opacity only works on undecorated windows.
            return !(w instanceof java.awt.Frame f && !f.isUndecorated()) && !(w instanceof java.awt.Dialog dl && !dl.isUndecorated());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Fades a window in (and lifts it 6px into place) when it opens. Call before {@code setVisible(true)}.
     * Windows with a system title bar can't change opacity and just appear.
     */
    public static void fadeIn(Window w) {
        if (!canFade(w)) return;
        try {
            w.setOpacity(0f);
        } catch (RuntimeException e) {
            return;
        }
        w.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                w.removeWindowListener(this);
                Point end = w.getLocation();
                animate(160, t -> {
                    try {
                        w.setOpacity((float) Math.max(0.01, t));
                        w.setLocation(end.x, (int) Math.round(end.y + 6 * (1 - t)));
                    } catch (RuntimeException ex) {
                        // window went away mid-fade
                    }
                }, () -> {
                    try {
                        w.setOpacity(1f);
                        w.setLocation(end);
                    } catch (RuntimeException ignored) {
                        // closed
                    }
                });
            }
        });
    }

    // ---- cross-fade -------------------------------------------------------------------------

    /**
     * Runs {@code change} (typically a theme switch) and cross-fades from a snapshot of how the window looked
     * before, so the whole window eases into the new colours instead of flashing.
     */
    public static void crossfade(RootPaneContainer window, Runnable change) {
        JRootPane root = window.getRootPane();
        if (!enabled() || !root.isShowing() || root.getWidth() <= 0 || root.getHeight() <= 0) {
            change.run();
            return;
        }
        BufferedImage before = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = before.createGraphics();
        root.paint(g);
        g.dispose();
        change.run();
        JLayeredPane layers = window.getLayeredPane();
        Point offset = SwingUtilities.convertPoint(root, 0, 0, layers);
        float[] alpha = {1f};
        JComponent overlay = new JComponent() {
            @Override
            protected void paintComponent(Graphics gr) {
                Graphics2D g2 = (Graphics2D) gr.create();
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha[0]));
                g2.drawImage(before, 0, 0, null);
                g2.dispose();
            }

            @Override
            public boolean contains(int x, int y) {
                return false; // never takes the mouse
            }
        };
        overlay.setBounds(offset.x, offset.y, before.getWidth(), before.getHeight());
        layers.add(overlay, Integer.valueOf(JLayeredPane.DRAG_LAYER + 10));
        animate(260, t -> {
            alpha[0] = (float) (1 - t);
            overlay.repaint();
        }, () -> {
            layers.remove(overlay);
            layers.repaint(overlay.getBounds());
        });
    }

    /** Convenience for frames. */
    public static void crossfade(JFrame f, Runnable change) {
        crossfade((RootPaneContainer) f, change);
    }

    /** Repaints a component on each frame of an animation. */
    static DoubleConsumer repainting(Component c, DoubleConsumer frame) {
        return t -> {
            frame.accept(t);
            c.repaint();
        };
    }
}
