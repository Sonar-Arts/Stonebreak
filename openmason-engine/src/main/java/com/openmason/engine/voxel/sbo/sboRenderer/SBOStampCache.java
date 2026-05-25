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

    /** Store the default (no-state) block stamp. */
    public void put(IBlockType blockType, BlockStamp stamp) {
        put(blockType, null, stamp);
    }

    /** Store a state-specific block stamp. {@code stateName} may be {@code null}
     *  to mean "default state". */
    public void put(IBlockType blockType, String stateName, BlockStamp stamp) {
        stamps.computeIfAbsent(blockType.getId(), k -> new HashMap<>()).put(stateName, stamp);
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

    /** True if any stamp (default or variant) exists for this block type. */
    public boolean has(IBlockType blockType) {
        Map<String, BlockStamp> byState = stamps.get(blockType.getId());
        return byState != null && !byState.isEmpty();
    }

    /** Number of distinct (block, state) entries cached. */
    public int size() {
        int n = 0;
        for (Map<String, BlockStamp> m : stamps.values()) n += m.size();
        return n;
    }
}
