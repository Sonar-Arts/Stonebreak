package com.stonebreak.blocks;

import java.util.Objects;

/**
 * Single source of truth for the part of a per-block state string that the
 * chunk mesher cares about.
 *
 * <p>A block's state selects which mesh variant gets baked — a lit furnace, a
 * stair's facing — and {@link #meshVariantKey} names that variant. It is the
 * key the SBO stamp cache is populated under, so the projection here and the
 * registration in the renderer have to agree exactly or a block silently falls
 * back to its default, un-rotated mesh.
 *
 * <p>Most state strings <em>are</em> their own variant key. The exception is a
 * string that carries volatile payload alongside the variant: a furnace's
 * string also holds its inventory and cook progress, which change every tick,
 * so only its {@code state=} value names the mesh.
 *
 * <p>{@link #affectsMesh} follows from the same projection, and both the local
 * edit path ({@code World.setBlockStateAt}) and the authoritative network echo
 * consult it, so a facing written by the server and one predicted by the
 * placing client rebuild identically.
 */
public final class BlockRenderState {

    /** Marks the variant-naming key inside a payload-carrying state string. */
    private static final String STATE_KEY = "state=";

    private BlockRenderState() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The mesh variant a raw state string selects, or {@code null} for none.
     *
     * <p>A {@code prefix:...state=<value>...} string projects down to just
     * {@code <value>}; anything else is returned whole.
     */
    public static String meshVariantKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return raw;
        }
        int stateKey = raw.indexOf(STATE_KEY, colon + 1);
        if (stateKey < 0) {
            return raw; // no volatile payload to strip — the string is the key
        }
        int valueStart = stateKey + STATE_KEY.length();
        int semi = raw.indexOf(';', valueStart);
        return semi < 0 ? raw.substring(valueStart) : raw.substring(valueStart, semi);
    }

    /** True when moving from {@code previous} to {@code next} changes what is drawn. */
    public static boolean affectsMesh(String previous, String next) {
        return !Objects.equals(meshVariantKey(previous), meshVariantKey(next));
    }
}
