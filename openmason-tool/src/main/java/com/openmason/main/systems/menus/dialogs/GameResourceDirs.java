package com.openmason.main.systems.menus.dialogs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the game's source resource tree ({@code stonebreak-game/src/main/resources})
 * on disk so export / open dialogs can start in the folder an asset actually
 * belongs in instead of the OS default. Resolution walks up from the working
 * directory so launching from the repo root, a module folder, or a nested
 * run directory all work. Every accessor returns {@code null} when the tree
 * cannot be found (e.g. running from a packaged jar), in which case callers
 * fall back to the dialog service's last-used-directory memory.
 */
public final class GameResourceDirs {

    private static final Path RESOURCES_REL = Path.of("stonebreak-game", "src", "main", "resources");

    /** Resource sub-folder per SBO object type ({@link #sboFolderFor}). */
    static final String SBO_BLOCKS = "sbo/blocks";
    static final String SBO_ITEMS = "sbo/items";
    static final String SBO_MODELS = "sbo/models";
    static final String SBE_ROOT = "sbe";
    static final String SBE_MOBS = "sbe/Mobs";

    private GameResourceDirs() {}

    /** Absolute path of the game resources root, or {@code null}. */
    public static Path resourcesRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        // Direct hit: cwd *is* the resources folder (or a module folder).
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(RESOURCES_REL);
            if (Files.isDirectory(candidate)) return candidate;
            // Launched from inside stonebreak-game/: "src/main/resources" relative.
            Path inner = dir.resolve("src/main/resources");
            if (dir.getFileName() != null && "stonebreak-game".equals(dir.getFileName().toString())
                    && Files.isDirectory(inner)) {
                return inner;
            }
        }
        return null;
    }

    /** Absolute path of {@code <resources>/<relative>} if it exists, else {@code null}. */
    public static String resolve(String relative) {
        Path root = resourcesRoot();
        if (root == null) return null;
        Path p = root.resolve(relative);
        return Files.isDirectory(p) ? p.toString() : null;
    }

    /** {@code sbo/blocks}, or {@code null}. */
    public static String sboBlocks() { return resolve(SBO_BLOCKS); }

    /** {@code sbo/items}, or {@code null}. */
    public static String sboItems() { return resolve(SBO_ITEMS); }

    /** {@code sbe} root, or {@code null}. */
    public static String sbeRoot() { return resolve(SBE_ROOT); }

    /**
     * The folder an SBO of the given manifest object type is shipped from:
     * blocks → {@code sbo/blocks}, items → {@code sbo/items}, everything
     * else → {@code sbo/models}. Falls back to the {@code sbo} root when the
     * specific folder doesn't exist yet.
     */
    public static String sboFolderFor(String objectType) {
        String t = objectType == null ? "" : objectType.trim().toLowerCase();
        String specific = switch (t) {
            case "block" -> SBO_BLOCKS;
            case "item" -> SBO_ITEMS;
            default -> SBO_MODELS;
        };
        String dir = resolve(specific);
        return dir != null ? dir : resolve("sbo");
    }

    /**
     * The folder an SBE of the given entity type is shipped from: mobs →
     * {@code sbe/Mobs}; other types have no dedicated folder yet and land in
     * the {@code sbe} root so the user picks the sub-folder.
     */
    public static String sbeFolderFor(String entityType) {
        String t = entityType == null ? "" : entityType.trim().toLowerCase();
        if ("mob".equals(t)) {
            String mobs = resolve(SBE_MOBS);
            if (mobs != null) return mobs;
        }
        return sbeRoot();
    }

    /** True when {@code dir} is inside the game resources tree. */
    static boolean isInsideResources(String dir) {
        Path root = resourcesRoot();
        if (root == null || dir == null) return false;
        try {
            return Path.of(dir).toAbsolutePath().normalize().startsWith(root.normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Suggested file name for an exported asset, following the shipped
     * {@code SB_Oak_Door.sbo} convention: {@code SB_} + title-cased words
     * joined by underscores + extension. Falls back to {@code fallback} when
     * the name is blank.
     */
    public static String suggestedFileName(String objectName, String extension, String fallback) {
        String base = objectName == null ? "" : objectName.trim();
        if (base.isEmpty()) return fallback;
        StringBuilder sb = new StringBuilder("SB_");
        boolean startOfWord = true;
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(startOfWord ? Character.toUpperCase(c) : c);
                startOfWord = false;
            } else if (!startOfWord) {
                sb.append('_');
                startOfWord = true;
            }
        }
        while (sb.length() > 3 && sb.charAt(sb.length() - 1) == '_') sb.setLength(sb.length() - 1);
        if (sb.length() == 3) return fallback;
        return sb + "." + extension;
    }
}
