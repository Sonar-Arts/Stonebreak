package com.stonebreak.openmason;

import com.openmason.api.target.ResourceRoot;
import com.openmason.main.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Where the Stonebreak target reads and writes the game's assets: the folder set on the plugin's
 * Preferences page ({@link StonebreakPreferencePage}), saved per machine.
 *
 * <p>The target's {@link ResourceRoot} already resolves the
 * {@value StonebreakGameTarget#RESOURCES_PROPERTY} property first, then a checkout found from Open
 * Mason's working folder, then the copy of the game's resources bundled in the plugin jar (read-only:
 * no {@code target:} write root, so exports fall back to the project folder and UI deploy has nowhere
 * to go). This class feeds the saved folder into that property, so the setting is honoured
 * everywhere the target is resolved (asset catalog, write roots, UI deploy, numeric ids) and takes
 * effect without a restart. A property given on the command line wins over the setting.
 */
public final class StonebreakResources {

    private static final Logger logger = LoggerFactory.getLogger(StonebreakResources.class);

    /** The system property the target's {@link ResourceRoot} reads. */
    public static final String PROPERTY = StonebreakGameTarget.RESOURCES_PROPERTY;

    private static final String KEY = "resources";

    /** Where the folder in use came from. */
    public enum Source {
        /** {@code -Dopenmason.target.stonebreak.resources} at launch (overrides the setting). */
        COMMAND_LINE,
        /** The folder saved on the Preferences page. */
        SETTING,
        /** A Stonebreak checkout found from Open Mason's working folder. */
        WORKING_DIRECTORY,
        /** No checkout: the read-only copy bundled with the plugin. */
        BUNDLED
    }

    /** The folder in use, or {@code tree == null} for {@link Source#BUNDLED}. */
    public record Status(Source source, Path tree) {
    }

    private final Path settingsFile;
    private final String commandLine;
    private Path saved;

    private StonebreakResources(Path settingsFile, String commandLine, Path saved) {
        this.settingsFile = settingsFile;
        this.commandLine = commandLine;
        this.saved = saved;
    }

    /** Where the setting is kept: {@code <Open Mason data folder>/plugin-settings/stonebreak.properties}. */
    public static Path defaultSettingsFile() {
        return AppPaths.dataRoot().resolve("plugin-settings").resolve("stonebreak.properties");
    }

    /**
     * Reads the saved folder and applies it (unless the property was given on the command line).
     * Call once, before anything resolves the target.
     */
    public static StonebreakResources install(Path settingsFile) {
        Objects.requireNonNull(settingsFile, "settingsFile");
        String commandLine = System.getProperty(PROPERTY);
        if (commandLine != null && commandLine.isBlank()) {
            commandLine = null;
        }
        StonebreakResources r = new StonebreakResources(settingsFile, commandLine, read(settingsFile));
        r.apply();
        Status s = r.status();
        switch (s.source()) {
            case BUNDLED -> logger.warn("[stonebreak] No Stonebreak checkout set: reading the plugin's bundled copy "
                    + "(read-only). Set it in Preferences > Stonebreak to export into the game.");
            default -> logger.info("[stonebreak] Stonebreak resources: {} ({})", s.tree(), s.source());
        }
        return r;
    }

    /**
     * The game's resource folder for a folder the user picked: the folder itself when it holds the
     * game's assets ({@code sbo/}), else its {@code src/main/resources} (the {@code stonebreak-game}
     * module) or {@code stonebreak-game/src/main/resources} (a checkout's root). Null when none is.
     */
    public static Path normalize(Path picked) {
        if (picked == null) {
            return null;
        }
        Path p = picked.toAbsolutePath().normalize();
        for (Path candidate : List.of(p, p.resolve("src/main/resources"), p.resolve(StonebreakGameTarget.SOURCE_TREE))) {
            if (Files.isDirectory(candidate.resolve("sbo"))) {
                return candidate.normalize();
            }
        }
        return null;
    }

    /** {@link #normalize} of a typed path; null for blank or unparsable input. */
    public static Path normalize(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return normalize(Path.of(typed.trim()));
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** The saved folder, or null when none is set. */
    public Path saved() {
        return saved;
    }

    /** The property's value given at launch, or null: it overrides the setting. */
    public String commandLine() {
        return commandLine;
    }

    /**
     * Saves {@code tree} (already {@link #normalize normalized}; null clears the setting) and applies
     * it at once.
     */
    public void save(Path tree) throws IOException {
        Properties p = new Properties();
        if (tree != null) {
            p.setProperty(KEY, tree.toAbsolutePath().normalize().toString());
        }
        Files.createDirectories(settingsFile.getParent());
        try (OutputStream out = Files.newOutputStream(settingsFile)) {
            p.store(out, "Open Mason Stonebreak plugin: where the Stonebreak target reads and writes assets");
        }
        saved = tree == null ? null : tree.toAbsolutePath().normalize();
        apply();
        logger.info("[stonebreak] Stonebreak resources folder {}", saved == null ? "cleared" : "set to " + saved);
    }

    /** The folder the target uses right now, and why. */
    public Status status() {
        Path named = dir(System.getProperty(PROPERTY));
        if (named != null) {
            return new Status(commandLine != null ? Source.COMMAND_LINE : Source.SETTING, named);
        }
        Path found = new ResourceRoot(List.of(StonebreakGameTarget.SOURCE_TREE), null, null)
                .locateFrom(Path.of("").toAbsolutePath());
        return found != null ? new Status(Source.WORKING_DIRECTORY, found) : new Status(Source.BUNDLED, null);
    }

    private void apply() {
        if (commandLine != null) {
            return; // the launch flag wins
        }
        if (saved != null) {
            System.setProperty(PROPERTY, saved.toString());
        } else {
            System.clearProperty(PROPERTY);
        }
    }

    private static Path read(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(settingsFile)) {
            p.load(in);
        } catch (IOException e) {
            logger.warn("[stonebreak] Cannot read {}", settingsFile, e);
            return null;
        }
        String value = p.getProperty(KEY);
        if (value == null || value.isBlank()) {
            return null;
        }
        Path tree = Path.of(value.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(tree)) {
            logger.warn("[stonebreak] The saved Stonebreak resources folder no longer exists: {}", tree);
        }
        return tree;
    }

    private static Path dir(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path p = Path.of(value.trim()).toAbsolutePath().normalize();
        return Files.isDirectory(p) ? p : null;
    }
}
