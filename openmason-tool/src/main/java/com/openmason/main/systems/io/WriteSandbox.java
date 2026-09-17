package com.openmason.main.systems.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Path safety for every file an MCP client or the in-tool assistant writes.
 *
 * <p>A target is accepted only when it canonicalizes (symlinks resolved on the
 * deepest existing ancestor) to somewhere inside one of the {@link WriteRoots}
 * and carries the {@link WriteKind}'s extension. Accepted spellings:
 * <ul>
 *   <li>an absolute path,</li>
 *   <li>{@code project:models/x.omo}, {@code game:sbo/blocks/SB_X.sbo},
 *       {@code exports:x.png} — a root prefix plus a relative path,</li>
 *   <li>a bare relative path, resolved against the kind's default root; a
 *       single bare name additionally lands in the kind's default sub-folder
 *       ({@code SB_X.sbo} → {@code game:sbo/blocks/SB_X.sbo}).</li>
 * </ul>
 *
 * <p>The static helpers at the bottom are the former {@code AssetPathGuard}:
 * read-side asset path checks and the tool-owned {@code ~/.openmason/exports}
 * jail used by {@code asset_texture_export}.
 */
public final class WriteSandbox {

    private final WriteRoots roots;

    public WriteSandbox(WriteRoots roots) {
        this.roots = roots;
    }

    public WriteRoots roots() {
        return roots;
    }

    /**
     * Resolve a caller-supplied spelling into an approved target.
     *
     * @throws IllegalArgumentException with a teaching message when refused
     */
    public WriteTarget resolve(WriteKind kind, String raw) {
        if (kind == null) {
            throw new IllegalArgumentException("write kind is required");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is empty");
        }
        String spelled = raw.trim();
        Path candidate;
        Path asPath = Path.of(spelled);
        if (asPath.isAbsolute()) {
            candidate = asPath;
        } else {
            int colon = spelled.indexOf(':');
            WriteRoot prefixRoot = colon > 0 ? WriteRoot.fromPrefix(spelled.substring(0, colon)) : null;
            if (prefixRoot != null) {
                String rel = spelled.substring(colon + 1).replaceFirst("^[/\\\\]+", "");
                if (rel.isBlank()) {
                    throw new IllegalArgumentException("path after '" + prefixRoot.prefix()
                            + ":' is empty — give a file name");
                }
                Path base = requireRoot(prefixRoot);
                candidate = base.resolve(rel);
            } else if (colon > 0 && colon < spelled.length() - 1
                    && !Character.isLetter(spelled.charAt(0))) {
                throw new IllegalArgumentException("unknown root prefix in '" + spelled
                        + "' — use project:, game: or exports:");
            } else {
                Path base = requireRoot(kind.defaultRoot());
                Path rel = Path.of(spelled);
                if (rel.getNameCount() == 1 && !kind.defaultSubdir().isEmpty()) {
                    base = base.resolve(kind.defaultSubdir());
                }
                candidate = base.resolve(rel);
            }
        }
        return check(kind, candidate);
    }

    /** Validate an absolute path (extension + containment). */
    public WriteTarget check(WriteKind kind, Path absolute) {
        Path withExt = absolute.resolveSibling(kind.ensureExtension(
                absolute.getFileName() == null ? "" : absolute.getFileName().toString()));
        Path canonical = canonicalize(withExt);
        Map<WriteRoot, Path> present = roots.present();
        for (Map.Entry<WriteRoot, Path> e : present.entrySet()) {
            Path root = canonicalize(e.getValue());
            if (canonical.startsWith(root)) {
                return new WriteTarget(kind, canonical, e.getKey(), Files.exists(canonical));
            }
        }
        throw new IllegalArgumentException("path_outside_sandbox: " + absolute
                + " is not inside any writable root (" + describeRoots(present)
                + ") — use a project:/game:/exports: path, or pass prompt:true to let the user pick");
    }

    /**
     * Resolve a spelling (absolute, {@code root:rel}, or project-relative) to
     * an existing regular file inside one of the roots — for source assets an
     * export embeds (clips, override models, sound samples). No extension rule.
     */
    public Path resolveExisting(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is empty");
        }
        String spelled = raw.trim();
        Path candidate = Path.of(spelled);
        if (!candidate.isAbsolute()) {
            int colon = spelled.indexOf(':');
            WriteRoot prefixRoot = colon > 0 ? WriteRoot.fromPrefix(spelled.substring(0, colon)) : null;
            Path base = requireRoot(prefixRoot != null ? prefixRoot : WriteRoot.PROJECT);
            candidate = base.resolve(prefixRoot != null
                    ? spelled.substring(colon + 1).replaceFirst("^[/\\\\]+", "") : spelled);
        }
        Path canonical = canonicalize(candidate);
        if (!Files.isRegularFile(canonical)) {
            throw new IllegalArgumentException("no_such_file: " + raw);
        }
        for (Map.Entry<WriteRoot, Path> e : roots.present().entrySet()) {
            if (canonical.startsWith(canonicalize(e.getValue()))) {
                return canonical;
            }
        }
        throw new IllegalArgumentException("path_outside_sandbox: " + raw
                + " is not inside any root (" + describeRoots() + ")");
    }

    /** Human-readable list of the roots that currently resolve. */
    public String describeRoots() {
        return describeRoots(roots.present());
    }

    private static String describeRoots(Map<WriteRoot, Path> present) {
        if (present.isEmpty()) {
            return "no roots available";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<WriteRoot, Path> e : present.entrySet()) {
            parts.add(e.getKey().prefix() + ": " + e.getValue());
        }
        return String.join(", ", parts);
    }

    private Path requireRoot(WriteRoot root) {
        Path p = roots.path(root);
        if (p == null) {
            throw new IllegalArgumentException("root_unavailable: the " + root.prefix()
                    + " root does not exist in this session (" + describeRoots() + ")");
        }
        return p;
    }

    /**
     * Absolute + normalized, with symlinks resolved on the deepest ancestor
     * that exists so {@code ..} and link tricks cannot escape a root.
     */
    static Path canonicalize(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path existing = abs;
        List<String> tail = new ArrayList<>();
        while (existing != null && !Files.exists(existing)) {
            tail.add(0, existing.getFileName().toString());
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            return abs;
        }
        for (String s : tail) {
            real = real.resolve(s);
        }
        return real;
    }

    // ------------------------------------------------------------------
    // Former AssetPathGuard: read-side asset paths + the exports jail
    // ------------------------------------------------------------------

    /** {@code ~/.openmason/exports}, the tool-owned scratch root (not created). */
    public static Path exportsRoot() {
        return Path.of(System.getProperty("user.home"), ".openmason", "exports");
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
        Path dir = exportsRoot().resolve(sanitizeFileName(assetId, "asset"));
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

    /**
     * A plain file name for the Save Sheet: no separators, no {@code ..}, no
     * leading dot, not empty.
     *
     * @throws IllegalArgumentException when the name is not plain
     */
    public static String requirePlainFileName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("file name is empty");
        }
        if (n.contains("/") || n.contains("\\") || n.contains("..") || n.startsWith(".")
                || n.chars().anyMatch(c -> c < 0x20 || c == ':')) {
            throw new IllegalArgumentException("file name must be a plain name (no folders): " + name);
        }
        return n;
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
