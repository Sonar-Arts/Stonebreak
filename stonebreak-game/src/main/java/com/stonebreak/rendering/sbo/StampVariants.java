package com.stonebreak.rendering.sbo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stamp variants the game derives from the SBO stamps at startup (stair facings, stalagmite sizes /
 * orientations / parts), registered under the state strings the world stores. Shared by the renderer and the
 * headless model-coverage test (no GL here).
 */
public final class StampVariants {

    private static final Logger LOGGER = LoggerFactory.getLogger(StampVariants.class);

    private StampVariants() {
    }

    /** Registers every derived variant for {@code types} (the stamped SBO blocks). */
    public static void registerAll(com.openmason.engine.voxel.stamp.render.StampCache cache,
                                   java.util.Collection<com.stonebreak.blocks.BlockType> types) {
        registerFacingRotations(cache, types);
        registerStalagmiteVariants(cache);
        MultipartStamps.register(cache);
    }

    /**
     * Registers a pre-rotated stamp for each facing of every orientable block — stairs and the
     * spiked wall. Rotating once at startup (four cheap array copies per type) keeps the
     * per-cube mesh path exactly as it was — look the stamp up by state name and copy it.
     *
     * <p>The key is the state string the block writes at placement, run through the same
     * {@code BlockRenderState.meshVariantKey} projection the mesher applies when it reads the
     * state back. Deriving both ends from one rule is what stops a block from silently meshing
     * un-rotated.
     */
    static void registerFacingRotations(
            com.openmason.engine.voxel.stamp.render.StampCache cache,
            java.util.Collection<com.stonebreak.blocks.BlockType> types) {
        for (com.stonebreak.blocks.BlockType type : types) {
            com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp base =
                    com.stonebreak.blocks.BlockFacing.isOrientable(type) ? cache.get(type) : null;
            if (base == null) {
                continue;
            }
            for (com.stonebreak.blocks.stairs.StairState.Facing facing
                    : com.stonebreak.blocks.stairs.StairState.Facing.values()) {
                String key = com.stonebreak.blocks.BlockRenderState.meshVariantKey(
                        com.stonebreak.blocks.BlockFacing.stateStringFor(type, facing));
                cache.put(type, key,
                        com.openmason.engine.voxel.stamp.StampRotator.rotateY(base, facing.quarterTurns()));
            }
            LOGGER.debug("[Renderer] Registered {} orientations for {}",
                    com.stonebreak.blocks.stairs.StairState.Facing.values().length, type.name());
        }
    }

    /**
     * Stalagmite variants, registered the way stairs register their facings: each size's SBO
     * model, mirrored for hanging and turned for every stair facing, under its
     * {@code StalagmiteState} string — so orientation needs no SBO states. Part cells (above an
     * upright anchor, below a hanging one) draw nothing: the anchor's model already reaches
     * through them. Their empty stamp keeps the cache from falling back to the default model
     * there and, having no flush geometry, never hides a neighbour's face.
     */
    static void registerStalagmiteVariants(
            com.openmason.engine.voxel.stamp.render.StampCache cache) {
        com.stonebreak.blocks.BlockType type = com.stonebreak.blocks.BlockType.LIMESTONE_STALAGMITE;
        if (type == null || !cache.has(type)) {
            return;
        }
        int registered = 0;
        for (int size = 1; size <= com.stonebreak.blocks.stalagmite.Stalagmite.MAX_SIZE; size++) {
            String sboState = new com.stonebreak.blocks.stalagmite.StalagmiteState(size, false, null).sboStateName();
            com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp upright = cache.get(type, sboState);
            if (upright == null) {
                continue;
            }
            com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp hanging =
                    com.openmason.engine.voxel.stamp.StampRotator.flipY(upright);
            for (boolean hang : new boolean[]{false, true}) {
                for (com.stonebreak.blocks.stairs.StairState.Facing facing
                        : com.stonebreak.blocks.stairs.StairState.Facing.values()) {
                    String key = com.stonebreak.blocks.BlockRenderState.meshVariantKey(
                            new com.stonebreak.blocks.stalagmite.StalagmiteState(size, hang, facing).toStateString());
                    cache.put(type, key, com.openmason.engine.voxel.stamp.StampRotator
                            .rotateY(hang ? hanging : upright, facing.quarterTurns()));
                    registered++;
                }
            }
        }

        int faceCount = com.openmason.engine.voxel.stamp.render.StampFaceConventions.FACE_COUNT;
        com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp[] faces =
                new com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp[faceCount];
        com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp empty =
                new com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp(
                        new float[0], new float[0], new float[0], new float[0], 0);
        java.util.Arrays.fill(faces, empty);
        com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp nothing =
                new com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp(
                        faces, faces.clone(), new boolean[faceCount]);
        cache.put(type, com.stonebreak.blocks.stalagmite.Stalagmite.UPPER, nothing);
        cache.put(type, com.stonebreak.blocks.stalagmite.Stalagmite.LOWER, nothing);
        LOGGER.debug("[Renderer] Registered {} stalagmite variants", registered);
    }

}
