package com.openmason.main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Platform-native filesystem locations for OpenMason application data.
 *
 * <p>Data root ("appdata") resolution per platform:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\OpenMason} (falls back to {@code ~/AppData/Roaming/OpenMason})</li>
 *   <li>macOS: {@code ~/Library/Application Support/OpenMason}</li>
 *   <li>Linux/other: {@code $XDG_DATA_HOME/OpenMason}, falling back to {@code ~/.local/share/OpenMason}</li>
 * </ul>
 *
 * <p>The legacy configuration directory ({@code ~/.openmason}) intentionally stays where it is —
 * only user data such as projects uses the platform-native root.
 */
public final class AppPaths {

    private static final Logger logger = LoggerFactory.getLogger(AppPaths.class);

    private static final String APP_DIR_NAME = "OpenMason";

    private AppPaths() {
    }

    /**
     * OS-native OpenMason data directory (not created on access).
     */
    public static Path dataRoot() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path home = Paths.get(System.getProperty("user.home"));

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path roaming = (appData != null && !appData.isBlank())
                    ? Paths.get(appData)
                    : home.resolve("AppData").resolve("Roaming");
            return roaming.resolve(APP_DIR_NAME);
        }
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve(APP_DIR_NAME);
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path dataHome = (xdgDataHome != null && !xdgDataHome.isBlank())
                ? Paths.get(xdgDataHome)
                : home.resolve(".local").resolve("share");
        return dataHome.resolve(APP_DIR_NAME);
    }

    /**
     * Default base folder for OpenMason projects; each project gets its own subfolder here
     * unless the user overrides the location at creation time.
     */
    public static Path defaultProjectsDir() {
        return dataRoot().resolve("Projects");
    }

    /**
     * Legacy configuration directory ({@code ~/.openmason}) — config.properties,
     * recent-projects.json, and themes live here.
     */
    public static Path legacyConfigDir() {
        return Paths.get(System.getProperty("user.home")).resolve(".openmason");
    }

    /**
     * Creates the directory (and parents) if missing. Logs and returns the path on failure
     * rather than throwing, so callers can proceed and surface the IO error at use time.
     */
    public static Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            logger.error("Failed to create directory: {}", dir, e);
        }
        return dir;
    }
}
