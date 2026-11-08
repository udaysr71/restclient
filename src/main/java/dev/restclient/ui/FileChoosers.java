package dev.restclient.ui;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.util.function.BiFunction;

/**
 * Open and save pickers: always the operating system's own file dialog (Explorer on Windows, GTK on Linux,
 * Finder on macOS), so it looks and behaves like every other app's and follows the system's light/dark theme.
 *
 * <p>Swing's JFileChooser isn't used: the app's look and feel builds on Swing's Basic look and feel, whose file
 * chooser has no file list at all (only Metal and the platform looks fill one in), so it opened as an empty box
 * with a plain, unthemed title bar.
 *
 * <p>The native dialogs ask before replacing an existing file themselves.
 */
final class FileChoosers {
    private static File lastDir;
    /** For automated checks, which can't drive a native dialog: (title, suggested name) → chosen file or null. */
    static BiFunction<String, String, File> testPicker;

    private FileChoosers() {
    }

    /** Picks a .json file to open, or null. */
    static File openJson(Component parent, String title) {
        if (testPicker != null) return testPicker.apply(title, null);
        FileDialog fd = dialog(parent, title, FileDialog.LOAD);
        fd.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".json"));
        // Windows ignores filename filters; a wildcard file name filters its list instead
        if (Platform.isWindows()) fd.setFile("*.json");
        return show(fd);
    }

    /** Picks where to save, suggesting {@code name} (in {@code dir}, if given). Null if cancelled. */
    static File save(Component parent, String title, String name) {
        return save(parent, title, name, null);
    }

    static File save(Component parent, String title, String name, File dir) {
        if (testPicker != null) return testPicker.apply(title, name);
        FileDialog fd = dialog(parent, title, FileDialog.SAVE);
        if (dir != null) fd.setDirectory(dir.getPath());
        fd.setFile(name);
        return show(fd);
    }

    private static FileDialog dialog(Component parent, String title, int mode) {
        Window w = parent instanceof Window win ? win : SwingUtilities.getWindowAncestor(parent);
        FileDialog fd = w instanceof Dialog d ? new FileDialog(d, title, mode)
                : new FileDialog(w instanceof Frame f ? f : null, title, mode);
        if (lastDir != null) fd.setDirectory(lastDir.getPath());
        return fd;
    }

    private static File show(FileDialog fd) {
        fd.setVisible(true);
        if (fd.getFile() == null) return null;
        File chosen = new File(fd.getDirectory(), fd.getFile());
        lastDir = chosen.getParentFile();
        return chosen;
    }
}
