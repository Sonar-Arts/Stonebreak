package com.stonebreak.openmason.assets;

import com.stonebreak.gameplay.SboGameplay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.main.systems.menus.dialogs.SBOObjectIndex;
import com.openmason.main.systems.target.GameTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Stonebreak's texture-atlas layout rule (#431, was {@code SBOExportWindow.findFreeAtlasSlot}): new
 * blocks get the first atlas tile no baked texture and no shipped block SBO already uses.
 */
public final class AtlasSlots {

    private static final Logger logger = LoggerFactory.getLogger(AtlasSlots.class);

    private AtlasSlots() {
    }

    /**
     * Return the first free {@code [tileX, tileY]} on the texture atlas in
     * row-major order. Occupancy is computed fresh on every call as the
     * union of:
     * <ul>
     *   <li>baked entries in {@code texture atlas/atlas_metadata.json}</li>
     *   <li>declared {@code atlasX/atlasY} (≥ 0) in every {@code .sbo} file
     *       currently in {@code sbo/blocks/} on disk</li>
     * </ul>
     *
     * <p>Because the SBO scan reads the live filesystem (via
     * {@link SBOObjectIndex#discover}), back-to-back exports automatically
     * see prior writes, and deleted SBOs free their slot. No in-memory
     * reservation needed.
     *
     * <p>Returns {@code [-1, -1]} when the atlas is fully occupied or the
     * metadata is unreachable — callers should treat that as "no slot" and
     * rely on the game's runtime dynamic allocator.
     */
    public static int[] findFreeAtlasSlot() {
        var target = GameTargets.shared().active();
        if (target.isEmpty()) {
            return new int[]{-1, -1};
        }
        // The game target's atlas (#382: read from its resources, not the tool's class path).
        try (InputStream in = target.get().resources().open("texture atlas/atlas_metadata.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            int tileSize = Math.max(1, root.path("textureSize").asInt(16));
            JsonNode size = root.path("atlasSize");
            int cols = Math.max(1, size.path("width").asInt(256) / tileSize);
            int rows = Math.max(1, size.path("height").asInt(256) / tileSize);

            Set<Long> occupied = new HashSet<>();
            markBakedTiles(root.path("textures"), tileSize, cols, rows, occupied);
            markSboDeclaredTiles(cols, rows, occupied);

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (!occupied.contains(((long) r << 32) | (c & 0xFFFFFFFFL))) {
                        return new int[]{c, r};
                    }
                }
            }
            logger.warn("Atlas is fully occupied — defaulting atlasX/Y to -1");
        } catch (java.io.FileNotFoundException e) {
            logger.debug("No atlas_metadata.json in the game target — defaulting atlasX/Y to -1");
        } catch (IOException e) {
            logger.warn("Failed to scan atlas_metadata.json for free slot — defaulting atlasX/Y to -1", e);
        }
        return new int[]{-1, -1};
    }

    /**
     * Mark every tile covered by a baked {@code atlas_metadata.json} entry.
     * Off-grid pixel rects are rounded out to all tiles they touch.
     */
    private static void markBakedTiles(JsonNode textures, int tileSize, int cols, int rows, Set<Long> occupied) {
        Iterator<JsonNode> it = textures.elements();
        while (it.hasNext()) {
            JsonNode entry = it.next();
            int x = entry.path("x").asInt(-1);
            int y = entry.path("y").asInt(-1);
            if (x < 0 || y < 0) continue;
            int w = Math.max(1, entry.path("width").asInt(tileSize));
            int h = Math.max(1, entry.path("height").asInt(tileSize));
            int c0 = x / tileSize;
            int r0 = y / tileSize;
            int c1 = (x + w - 1) / tileSize;
            int r1 = (y + h - 1) / tileSize;
            for (int r = r0; r <= r1 && r < rows; r++) {
                for (int c = c0; c <= c1 && c < cols; c++) {
                    occupied.add(((long) r << 32) | (c & 0xFFFFFFFFL));
                }
            }
        }
    }

    /**
     * Mark the tile declared by each block {@code .sbo} of the game target whose
     * {@code gameProperties.atlasX/atlasY} are both ≥ 0. Unreadable files are
     * skipped — best-effort, since one bad file shouldn't block exporting.
     */
    private static void markSboDeclaredTiles(int cols, int rows, Set<Long> occupied) {
        SBOParser parser = new SBOParser();
        for (Path path : SBOObjectIndex.listAll().stream()
                .filter(SBOObjectIndex.Entry::isBlock).map(SBOObjectIndex.Entry::sourcePath).toList()) {
            try {
                SBOParser.RawParse raw = parser.parseRaw(path);
                SboGameplay.GameProperties gp = SboGameplay.of(raw.manifest()).gameProperties();
                if (gp == null) continue;
                int c = gp.atlasX();
                int r = gp.atlasY();
                if (c < 0 || r < 0 || c >= cols || r >= rows) continue;
                occupied.add(((long) r << 32) | (c & 0xFFFFFFFFL));
            } catch (IOException ignored) {
                // best-effort; skip unreadable SBOs
            }
        }
    }
}
