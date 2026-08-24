package com.openmason.main.systems.assets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Path safety for the asset-lens tools: every filesystem address an MCP client
 * or LLM supplies goes through here.
 *
 * <ul>
 *   <li>Asset paths are canonicalized (symlinks resolved) and must stay inside
 *       the game resource tree (or the read-only classpath mirror) with a
 *       {@code .sbo}/{@code .sbe} extension.</li>
 *   <li>Exports are confined to {@code ~/.openmason/exports/<assetId>/} with a
 *       sanitized, separator-free file name — never into game resources.</li>
 * </ul>
 */
public final class AssetPathGuard {

    private AssetPathGuard() {
    }

    /**
     * Canonicalize a caller-supplied asset path and verify it stays inside one
     * of the allowed roots with an allowed extension.
     *
     * @throws IllegalArgumentException with a teaching message when rejected
     */
    public static Path requireAssetPath(String rawPath, Path... allowedRoots) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is empty");
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".sbo") && !lower.endsWith(".sbe")) {
            throw new IllegalArgumentException(
                    "path must point at a .sbo or .sbe file, got: " + rawPath);
        }
        Path canonical;
        try {
            canonical = Path.of(rawPath).toAbsolutePath().normalize().toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("path does not exist or is unreadable: " + rawPath);
        }
        for (Path root : allowedRoots) {
            if (root == null) {
                continue;
            }
            try {
                Path canonicalRoot = root.toRealPath();
                if (canonical.startsWith(canonicalRoot)) {
                    return canonical;
                }
            } catch (IOException ignored) {
                // root missing — try the next one
            }
        }
        throw new IllegalArgumentException("path_outside_game_resources: " + rawPath
                + " is not inside the game resource tree — address assets by objectId instead");
    }

    /** Tool-owned export directory for one asset, created on demand. */
    public static Path exportDir(String assetId) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".openmason", "exports",
                sanitizeFileName(assetId, "asset"));
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Sanitize a caller-supplied file name: separators and control characters
     * stripped, never empty, never dot-only.
     */
    public static String sanitizeFileName(String name, String fallback) {
        String cleaned = name == null ? "" : name.trim()
                .replaceAll("[/\\\\:\\x00-\\x1f]", "_")
                .replaceAll("^\\.+", "");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    /** Sanitized {@code .png} file name inside the asset's export dir. */
    public static Path exportPngTarget(String assetId, String requestedName) throws IOException {
        String base = sanitizeFileName(requestedName, "texture");
        if (!base.toLowerCase(Locale.ROOT).endsWith(".png")) {
            base = base + ".png";
        }
        return exportDir(assetId).resolve(base);
    }
}
