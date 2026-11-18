package com.stonebreak.world.save;

import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.WorldData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Which saved worlds this build can open (cubic voxel runtime, Stonebreak #447). The game is pre-alpha: there is
 * NO migration. A world written in another save format (the column format v1: 16x256x16 chunk files under
 * {@code chunks/}), or with other vertical bounds / cube edge, is reported as an incompatible version - in the
 * world list and when it is opened - and never loaded, rewritten or deleted.
 *
 * <p>Layout of a world in the current format ({@value #CURRENT_VERSION}):
 * <pre>
 *   world.json        creation record (seed, generator, terrain scale, format version, bounds) - never rewritten
 *   metadata.json     the same record + play time, world time, weather, settings - rewritten at every save
 *   player.json       the host's player (from its server-side record)
 *   players/*.json    remote players
 *   cubes/            the native cv_store: r.X.Y.Z.cvr cube regions (8^3 cubes), c.X.Z.cvc column regions,
 *                     block_states.txt (STATE layer ids)
 *   fastlod/          FastLOD cache
 * </pre>
 */
public final class WorldFormat {

    /** The cubic format (cv_store cube + column records, SBC1 game blobs, double positions). */
    public static final int CURRENT_VERSION = 2;
    /** The column format (per-chunk {@code .sbc} files); records that never wrote a version read as this. */
    public static final int LEGACY_COLUMN_VERSION = 1;

    /** Bounds a record without them describes: the old 0..256 column world of 16-wide chunks. */
    public static final long LEGACY_MIN_Y = 0;
    public static final long LEGACY_MAX_Y = 256;
    public static final int LEGACY_EDGE = 16;

    public static final String CUBES_DIR = "cubes";
    public static final String LEGACY_CHUNKS_DIR = "chunks";
    private static final String LEGACY_CHUNK_SUFFIX = ".sbc";

    /** What the world list and the load error start with. ASCII only (the menu font has no dashes). */
    public static final String INCOMPATIBLE = "Incompatible version";

    private WorldFormat() {
    }

    /** The verdict on one world: compatible, or why not (a sentence for the player). */
    public record Check(boolean compatible, String reason) {
        static final Check OK = new Check(true, null);

        static Check incompatible(String why) {
            return new Check(false, INCOMPATIBLE + ": " + why);
        }
    }

    /** Checks a world's record against this build (format version, bounds, cube edge). Null data = OK. */
    public static Check check(WorldData data) {
        if (data == null) {
            return Check.OK;
        }
        int v = data.getFormatVersion();
        if (v < CURRENT_VERSION) {
            return Check.incompatible("this world was saved by an older version of Stonebreak (save format "
                + v + ") and cannot be opened by this one.");
        }
        if (v > CURRENT_VERSION) {
            return Check.incompatible("this world was saved by a newer version of Stonebreak (save format "
                + v + ") and cannot be opened by this one.");
        }
        if (data.getMinY() != WorldConfiguration.MIN_Y || data.getMaxY() != WorldConfiguration.MAX_Y
                || data.getCubeEdge() != WorldConfiguration.CUBE_EDGE) {
            return Check.incompatible("this world was made with another world height (y " + data.getMinY()
                + " to " + data.getMaxY() + ", cubes of " + data.getCubeEdge() + ") and cannot be opened.");
        }
        return Check.OK;
    }

    /**
     * {@link #check(WorldData)} plus what is on disk: column chunk files without a cube store are an old
     * world even when its record claims the current format (a hand-edited or half-written record).
     */
    public static Check check(WorldData data, Path worldDir) {
        Check byRecord = check(data);
        if (!byRecord.compatible() || worldDir == null) {
            return byRecord;
        }
        if (hasLegacyColumnFiles(worldDir) && !Files.isDirectory(worldDir.resolve(CUBES_DIR))) {
            return Check.incompatible("this world holds chunk files of an older version of Stonebreak and "
                + "cannot be opened by this one.");
        }
        return Check.OK;
    }

    /** True when {@code data} can be opened by this build (the world list's quick test). */
    public static boolean isCompatible(WorldData data) {
        return check(data).compatible();
    }

    /** True when the world directory holds at least one column-format chunk file ({@code chunks/**}/*.sbc). */
    public static boolean hasLegacyColumnFiles(Path worldDir) {
        Path chunks = worldDir.resolve(LEGACY_CHUNKS_DIR);
        if (!Files.isDirectory(chunks)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(chunks, 2)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(LEGACY_CHUNK_SUFFIX)
                && Files.isRegularFile(p));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** True when the world's cube store directory holds any file (cube/column regions, block states). */
    public static boolean hasCubeStoreFiles(Path worldDir) {
        Path cubes = worldDir.resolve(CUBES_DIR);
        if (!Files.isDirectory(cubes)) {
            return false;
        }
        try (Stream<Path> files = Files.list(cubes)) {
            return files.anyMatch(Files::isRegularFile);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
