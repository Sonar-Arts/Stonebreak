package com.stonebreak.mobs.sbe;

import com.stonebreak.items.ItemType;

/**
 * What hangs on a socket: an authored model (hats, bandanas, earrings) or a held
 * item (weapons). Items are texture-only SBOs with no mesh of their own, so they
 * render through the voxelized-sprite path rather than the SBE model path.
 */
public sealed interface AttachmentVisual {

    /** An SBE/OMO model, drawn in the given appearance variant (e.g. a bandana colour). */
    record Model(SbeEntityAsset asset, String variant) implements AttachmentVisual {
        public Model(SbeEntityAsset asset) {
            this(asset, SbeEntityAsset.DEFAULT_VARIANT);
        }
    }

    /** A voxelized item; {@code state} selects an SBO state (the bow's draw frames), null for default. */
    record Item(ItemType type, String state) implements AttachmentVisual {
        public Item(ItemType type) {
            this(type, null);
        }
    }
}
