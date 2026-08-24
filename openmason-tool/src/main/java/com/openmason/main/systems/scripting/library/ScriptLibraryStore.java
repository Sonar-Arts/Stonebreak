package com.openmason.main.systems.scripting.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The script library: user scripts in {@code ~/.openmason/scripts/} plus
 * read-only bundled examples on the classpath
 * ({@code /scripting/examples/}). Shared by the Scripting window UI and the
 * {@code script_*} MCP tools so humans, MCP clients and the assistant see the
 * same library.
 */
public final class ScriptLibraryStore {

    /** Read cap — scripts are code, not data dumps. */
    public static final int MAX_SCRIPT_BYTES = 512 * 1024;
    private static final String EXAMPLES_RESOURCE_DIR = "/scripting/examples/";
    private static final List<String> BUNDLED_EXAMPLES = List.of(
            "checker_texture.py", "staircase_parts.py", "bounce_anim.py", "ops_sample.json");

    /** One library entry. */
    public record ScriptEntry(String name, String language, long sizeBytes, long modifiedMillis,
                              boolean example) {
    }

    private final Path userDir;

    public ScriptLibraryStore() {
        this(Path.of(System.getProperty("user.home"), ".openmason", "scripts"));
    }

    /** Test seam. */
    public ScriptLibraryStore(Path userDir) {
        this.userDir = userDir;
    }

    public Path userDir() {
        return userDir;
    }

    /** User scripts first (by name), then bundled examples. */
    public List<ScriptEntry> list() {
        List<ScriptEntry> out = new ArrayList<>();
        if (Files.isDirectory(userDir)) {
            try (Stream<Path> stream = Files.list(userDir)) {
                stream.filter(p -> isScriptName(p.getFileName().toString()))
                        .sorted()
                        .forEach(p -> {
                            try {
                                out.add(new ScriptEntry(p.getFileName().toString(),
                                        languageOf(p.getFileName().toString()),
                                        Files.size(p), Files.getLastModifiedTime(p).toMillis(),
                                        false));
                            } catch (IOException ignored) {
                                // unreadable entry — skip
                            }
                        });
            } catch (IOException ignored) {
                // directory vanished — treat as empty
            }
        }
        for (String example : BUNDLED_EXAMPLES) {
            if (getClass().getResource(EXAMPLES_RESOURCE_DIR + example) != null) {
                out.add(new ScriptEntry(example, languageOf(example), -1, 0, true));
            }
        }
        return out;
    }

    /** Read a script by name (user scripts shadow same-named examples). */
    public String read(String name) throws IOException {
        String safe = requireScriptName(name);
        Path userFile = userDir.resolve(safe);
        if (Files.isRegularFile(userFile)) {
            if (Files.size(userFile) > MAX_SCRIPT_BYTES) {
                throw new IllegalArgumentException("script '" + safe + "' exceeds the "
                        + (MAX_SCRIPT_BYTES / 1024) + " KB read cap");
            }
            return Files.readString(userFile, StandardCharsets.UTF_8);
        }
        if (BUNDLED_EXAMPLES.contains(safe)) {
            try (InputStream in = getClass().getResourceAsStream(EXAMPLES_RESOURCE_DIR + safe)) {
                if (in != null) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw unknownScript(safe);
    }

    /** True when a user script (not example) with this name exists. */
    public boolean exists(String name) {
        return Files.isRegularFile(userDir.resolve(requireScriptName(name)));
    }

    /**
     * Save a user script atomically (temp + move).
     *
     * @throws IllegalArgumentException {@code script_exists} without overwrite
     */
    public void save(String name, String source, boolean overwrite) throws IOException {
        String safe = requireScriptName(name);
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SCRIPT_BYTES) {
            throw new IllegalArgumentException("script exceeds the "
                    + (MAX_SCRIPT_BYTES / 1024) + " KB cap");
        }
        Files.createDirectories(userDir);
        Path target = userDir.resolve(safe);
        if (!overwrite && Files.exists(target)) {
            throw new IllegalArgumentException("script_exists: '" + safe
                    + "' already exists — pass overwrite:true to replace it");
        }
        Path temp = Files.createTempFile(userDir, ".save-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Delete a user script. Bundled examples refuse with a teaching error. */
    public void delete(String name) throws IOException {
        String safe = requireScriptName(name);
        Path target = userDir.resolve(safe);
        if (Files.isRegularFile(target)) {
            Files.delete(target);
            return;
        }
        if (BUNDLED_EXAMPLES.contains(safe)) {
            throw new IllegalArgumentException("'" + safe
                    + "' is a bundled read-only example — duplicate it into your library instead");
        }
        throw unknownScript(safe);
    }

    // ---------------------------------------------------------------- naming

    /** Language from extension: {@code python} or {@code json_ops}. */
    public static String languageOf(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".json") ? "json_ops" : "python";
    }

    private static boolean isScriptName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".py") || lower.endsWith(".json");
    }

    /**
     * Validate + normalize a script name: no separators, no traversal, forced
     * {@code .py}/{@code .json} extension ({@code .py} default).
     */
    public static String requireScriptName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("script name is required");
        }
        String cleaned = name.trim();
        if (cleaned.contains("/") || cleaned.contains("\\") || cleaned.contains("..")
                || cleaned.startsWith(".")) {
            throw new IllegalArgumentException("script name must be a plain file name "
                    + "(no folders): " + name);
        }
        if (!isScriptName(cleaned)) {
            cleaned = cleaned + ".py";
        }
        return cleaned;
    }

    private IllegalArgumentException unknownScript(String name) {
        List<String> known = list().stream().map(ScriptEntry::name).toList();
        return new IllegalArgumentException("No script '" + name + "'. Known: " + known
                + " (call script_list)");
    }
}
