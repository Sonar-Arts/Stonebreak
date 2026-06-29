package com.stonebreak.world.save;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Single source of truth for where worlds are stored on disk.
 *
 * <p>Worlds live under a per-user application-data directory rather than the process working
 * directory, so saves survive working-directory changes / reinstalls and never pollute the
 * repository checkout. The location is platform-appropriate:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\Stonebreak\worlds}</li>
 *   <li>macOS:   {@code ~/Library/Application Support/Stonebreak/worlds}</li>
 *   <li>Linux:   {@code $XDG_DATA_HOME/Stonebreak/worlds} (else {@code ~/.local/share/Stonebreak/worlds})</li>
 * </ul>
 *
 * <p>All world paths produced here are absolute, so they may be handed directly to
 * {@code SaveService} and resolved against (e.g. {@code worldDir/fastlod/cache.sqlite}).
 */
public final class WorldStorage {

    private static final String APP_DIR_NAME = "Stonebreak";
    private static final String WORLDS_DIR_NAME = "worlds";

    private WorldStorage() {
    }

    /** Base directory that contains one subdirectory per world. */
    public static Path worldsRoot() {
        return appDataDir().resolve(WORLDS_DIR_NAME);
    }

    /** Directory for a single named world. */
    public static Path worldDir(String worldName) {
        return worldsRoot().resolve(worldName);
    }

    /** String form of {@link #worldDir(String)}, for APIs that take a path string (e.g. SaveService). */
    public static String worldPath(String worldName) {
        return worldDir(worldName).toString();
    }

    private static Path appDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, APP_DIR_NAME);
            }
            return userHome().resolve("AppData").resolve("Roaming").resolve(APP_DIR_NAME);
        }

        if (os.contains("mac") || os.contains("darwin")) {
            return userHome().resolve("Library").resolve("Application Support").resolve(APP_DIR_NAME);
        }

        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isBlank()) {
            return Paths.get(xdgData, APP_DIR_NAME);
        }
        return userHome().resolve(".local").resolve("share").resolve(APP_DIR_NAME);
    }

    private static Path userHome() {
        return Paths.get(System.getProperty("user.home", "."));
    }
}
