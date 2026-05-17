package com.openmason.engine.format.oma;

/**
 * Open Mason Animation (.omanim) file format specification.
 *
 * <p>An {@code .omanim} file is a ZIP archive:
 * <ul>
 *   <li>{@code manifest.json} — clip metadata: {@code version, name, fps,
 *       duration, loop, modelRef, tracks[]}, where each track entry holds
 *       {@code partId}, {@code partName} and a {@code dataFile} reference.</li>
 *   <li>{@code track_<uuid>.json} — per-part keyframe data:
 *       {@code partId} and a {@code keyframes[]} array of
 *       {@code {time, position[3], rotation[3], scale[3], easing}}.</li>
 * </ul>
 *
 * <p>This is the counterpart specification to {@link OMAReader}. It carries no
 * behaviour — only the constants needed to locate entries inside the archive.
 */
public final class OMAFormat {

    /** Current format version produced by Open Mason. */
    public static final String FORMAT_VERSION = "1.0";

    /** File extension for animation clips. */
    public static final String FILE_EXTENSION = ".omanim";

    /** Manifest filename inside the ZIP archive. */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Filename prefix for per-track keyframe data files. */
    public static final String TRACK_PREFIX = "track_";

    private OMAFormat() {
        throw new UnsupportedOperationException("Utility class");
    }
}
