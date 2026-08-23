package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe cache for pre-computed SBO block stamps, optionally keyed by
 * named state variant (SBO 1.3+).
 *
 * <p>Lookup model: for each block type ID, a small map of {@code stateName ->
 * BlockStamp}. The {@code null} key holds the default (no-state) stamp. State
 * lookup falls back to the default when an unknown state name is requested.
 *
 * <p>Populated during initialization, read-only at runtime — no synchronization
 * needed after init.
 */
public class SBOStampCache {

    private final Map<Integer, Map<String, BlockStamp>> stamps = new HashMap<>();
    /**
     * Per-block occlusion mask, folded across every registered state as stamps
     * arrive. Precomputed because the chunk mesher asks per face of per block:
     * see {@link #occludesFace}.
     */
    private final Map<Integer, boolean[]> occlusion = new HashMap<>();

    /** Store the default (no-state) block stamp. */
    public void put(IBlockType blockType, BlockStamp stamp) {
        put(blockType, null, stamp);
    }

    /** Store a state-specific block stamp. {@code stateName} may be {@code null}
     *  to mean "default state". */
    public void put(IBlockType blockType, String stateName, BlockStamp stamp) {
        stamps.computeIfAbsent(blockType.getId(), k -> new HashMap<>()).put(stateName, stamp);
        boolean[] mask = occlusion.computeIfAbsent(blockType.getId(),
                k -> new boolean[]{true, true, true, true, true, true});
        for (int face = 0; face < SBOFaceConventions.FACE_COUNT; face++) {
            mask[face] &= stamp.occludesFace()[face];
        }
    }

    /** Get the default (no-state) stamp for a block type. */
    public BlockStamp get(IBlockType blockType) {
        return get(blockType, null);
    }

    /** Get the stamp for the given state, falling back to the default stamp
     *  when {@code stateName} is null, unknown, or has no variant registered. */
    public BlockStamp get(IBlockType blockType, String stateName) {
        Map<String, BlockStamp> byState = stamps.get(blockType.getId());
        if (byState == null) return null;
        if (stateName != null) {
            BlockStamp exact = byState.get(stateName);
            if (exact != null) return exact;
        }
        return byState.get(null);
    }

    /** Number of state variants registered for a block type (0 when none). */
    public int variantCount(IBlockType blockType) {
        Map<String, BlockStamp> byState = stamps.get(blockType.getId());
        return byState == null ? 0 : byState.size();
    }

    /** True if any stamp (default or variant) exists for this block type. */
    public boolean has(IBlockType blockType) {
        Map<String, BlockStamp> byState = stamps.get(blockType.getId());
        return byState != null && !byState.isEmpty();
    }

    /**
     * Whether this block type hides a neighbour's facing side, for every state
     * it can be in. Answers conservatively: a face counts as occluding only
     * when <em>all</em> registered variants fill that boundary plane, so a
     * rotatable shape (stairs) never claims to cover a side that one of its
     * rotations leaves open. Types with no stamp at all (AIR, WATER, animated
     * blocks) report {@code true} — they are governed by transparency rules
     * elsewhere and must keep their existing culling behaviour.
     */
    public boolean occludesFace(IBlockType blockType, int mmsFace) {
        if (blockType == null || mmsFace < 0 || mmsFace >= SBOFaceConventions.FACE_COUNT) {
            return true;
        }
        boolean[] mask = occlusion.get(blockType.getId());
        return mask == null || mask[mmsFace];
    }

    /** Number of distinct (block, state) entries cached. */
    public int size() {
        int n = 0;
        for (Map<String, BlockStamp> m : stamps.values()) n += m.size();
        return n;
    }
}
