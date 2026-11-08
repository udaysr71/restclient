package dev.restclient;

import dev.restclient.store.AppPaths;
import dev.restclient.store.Repository;
import dev.restclient.ui.AppContext;
import dev.restclient.ui.MainFrame;
import dev.restclient.ui.Platform;
import dev.restclient.ui.Splash;
import dev.restclient.ui.Theme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Taskbar;

/** Entry point: {@code java -jar restclient.jar}. */
public final class App {
    private App() {
    }

    public static void main(String[] args) {
        if (Platform.isMac()) {
            // Must be set before any AWT class loads.
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "REST Client");
            System.setProperty("apple.awt.application.appearance", "system");
        }
        System.setProperty("sun.java2d.uiScale.enabled", "true");

        Splash.progress(0.15);
        Repository repo = new Repository(AppPaths.defaultPaths());
        try {
            repo.load();
            Splash.progress(0.45);
        } catch (Exception e) {
            fatal("Couldn't open the data folder " + AppPaths.display(repo.paths().base()) + ":\n" + e.getMessage());
            return;
        }

        SwingUtilities.invokeLater(() -> {
            Theme.setZoom((float) repo.settings().zoom, false);
            Theme.setFonts(repo.settings().uiFontFamily, repo.settings().codeFontFamily, false);
            dev.restclient.ui.Motion.setReduced(repo.settings().reduceMotion);
            dev.restclient.ui.Themes.useDir(repo.paths().base().resolve("themes"));
            dev.restclient.ui.Themes.reload();
            Theme.apply(repo.settings().theme, repo.settings().customTheme, false);
            dev.restclient.ui.Shadow.Factory.install();
            Splash.progress(0.65);
            try {
                if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    Taskbar.getTaskbar().setIconImage(Theme.appIcon(512));
                }
            } catch (UnsupportedOperationException | SecurityException ignored) {
                // not supported here
            }
            AppContext ctx;
            try {
                ctx = new AppContext(repo);
            } catch (IllegalStateException e) {
                fatal(e.getMessage());
                return;
            }
            MainFrame frame = new MainFrame(ctx);
            Splash.progress(1.0);
            dev.restclient.ui.Motion.fadeIn(frame);
            frame.setVisible(true);
        });
    }

    private static void fatal(String message) {
        JOptionPane.showMessageDialog(null, message, "REST Client", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }
}
