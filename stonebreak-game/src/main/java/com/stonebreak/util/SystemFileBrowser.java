package com.stonebreak.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens a directory in the host OS file manager.
 *
 * <p>The platform command is spawned directly rather than going through
 * {@code java.awt.Desktop}: touching Desktop initialises the AWT toolkit, and on macOS an
 * AWT toolkit in a GLFW process wants the main thread that GLFW already owns. Desktop is
 * still tried as a fallback, but on a background thread so a slow or hung toolkit can never
 * stall the render loop.
 */
public final class SystemFileBrowser {

    private SystemFileBrowser() {
    }

    /**
     * Shows {@code dir} in the file manager. Returns false if the directory does not exist
     * or no launcher could be started; the call never throws and never blocks on the caller's
     * thread for longer than spawning a process.
     */
    public static boolean open(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            System.err.println("Cannot open folder, not a directory: " + dir);
            return false;
        }

        String path = dir.toAbsolutePath().toString();
        String os = System.getProperty("os.name", "").toLowerCase();

        String[] command;
        if (os.contains("win")) {
            command = new String[]{"explorer.exe", path};
        } else if (os.contains("mac") || os.contains("darwin")) {
            command = new String[]{"open", path};
        } else {
            command = new String[]{"xdg-open", path};
        }

        try {
            // Deliberately not waiting on the exit code: explorer.exe reports failure (1)
            // even when it opened the window, and xdg-open may outlive us.
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException e) {
            System.err.println("Could not launch file manager (" + command[0] + "): " + e.getMessage());
            return openWithDesktop(new File(path));
        }
    }

    private static boolean openWithDesktop(File dir) {
        try {
            if (!java.awt.Desktop.isDesktopSupported()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(dir);
            } catch (Exception | UnsatisfiedLinkError e) {
                System.err.println("Desktop fallback could not open " + dir + ": " + e.getMessage());
            }
        }, "open-folder-fallback");
        t.setDaemon(true);
        t.start();
        return true;
    }
}
