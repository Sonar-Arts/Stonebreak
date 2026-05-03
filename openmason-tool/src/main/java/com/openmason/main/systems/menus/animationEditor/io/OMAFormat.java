package com.openmason.main.systems.menus.animationEditor.io;

/**
 * Open Mason Animation (.oma) file format constants.
 *
 * <p>Layout: ZIP archive containing one {@code manifest.json} (clip metadata
 * and a list of track descriptors) plus one {@code track_<sanitized-id>.json}
 * per part track.
 */
public final class OMAFormat {

    public static final String FORMAT_VERSION = "1.0";
    // .oma collides with Sony OpenMG Audio on Windows (file-association hijack); .omanim is unambiguous.
    public static final String FILE_EXTENSION = ".omanim";
    public static final String MANIFEST_FILENAME = "manifest.json";
    public static final String TRACK_PREFIX = "track_";

    private OMAFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String ensureExtension(String filePath) {
        if (filePath == null || filePath.isBlank()) return filePath;
        String trimmed = filePath.trim();
        return trimmed.toLowerCase().endsWith(FILE_EXTENSION) ? trimmed : trimmed + FILE_EXTENSION;
    }

    /**
     * Sanitize a partId for safe use as a filename inside the ZIP.
     */
    public static String trackFilename(String partId) {
        String safe = partId == null ? "unknown" : partId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return TRACK_PREFIX + safe + ".json";
    }
}
