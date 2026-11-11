package com.stonebreak.rendering.sbo;

import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampCache;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.multipart.MultipartRegistry;
import com.stonebreak.blocks.multipart.MultipartShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bakes neighbour-driven shapes into the stamp cache: for each block with a {@code multipart}
 * table, one stamp per neighbour combination, merged from the SBO states its parts name and stored
 * under that combination's {@link MultipartShape#key}. The mesher then draws a cell by looking its
 * key up like any other state.
 */
public final class MultipartStamps {

    private static final Logger logger = LoggerFactory.getLogger(MultipartStamps.class);

    private MultipartStamps() {
    }

    /**
     * Whether {@code block} hides a neighbour's face on {@code face}, for the mesher's face culling.
     *
     * <p>The stamp cache answers for all of a block's variants at once (a face occludes only if
     * every variant fills it). A multipart block's variants are its neighbour combinations, and
     * the rounded ones fill no plane, so that answer would say a leaf never occludes, and the
     * cutout-against-opaque cull would leave a leaf's flat side z-fighting the log beside it. A
     * multipart block answers with its default model instead: a side is only ever flat and
     * filled where its neighbour fills the cell, which is exactly when the question is asked.
     */
    public static boolean occludesFace(SBOStampCache cache, com.openmason.engine.voxel.IBlockType block, int face) {
        if (block instanceof BlockType type && MultipartRegistry.of(type) != null) {
            BlockStamp base = cache.get(type);
            if (base != null && face >= 0 && face < base.occludesFace().length) {
                return base.occludesFace()[face];
            }
        }
        return cache.occludesFace(block, face);
    }

    /** Registers every compiled shape whose block has a stamp. */
    public static void register(SBOStampCache cache) {
        for (MultipartShape shape : MultipartRegistry.all()) {
            register(cache, shape);
        }
    }

    /** Registers one block's shapes; does nothing when the block has no stamp. */
    public static void register(SBOStampCache cache, MultipartShape shape) {
        BlockType type = shape.owner();
        BlockStamp base = cache.get(type);
        if (base == null) {
            return; // not a stamp block (animated, or no mesh)
        }
        Map<String, BlockStamp> parts = new HashMap<>();
        for (String state : shape.partStates()) {
            BlockStamp stamp = cache.get(type, state);
            if (stamp == base) {
                // The cache falls back to the default model for an unknown state; drawing that
                // would put a whole cube where a part should be.
                logger.error("{}: multipart names state '{}', which the SBO lacks", type.name(), state);
                continue;
            }
            parts.put(state, stamp);
        }
        for (int mask = 0; mask < shape.maskCount(); mask++) {
            List<BlockStamp> drawn = new ArrayList<>();
            for (String state : shape.states(mask)) {
                BlockStamp part = parts.get(state);
                if (part != null) {
                    drawn.add(part);
                }
            }
            cache.put(type, shape.key(mask), StampComposer.compose(drawn));
        }
        logger.debug("Registered {} multipart shapes for {}", shape.maskCount(), type.name());
    }
}
