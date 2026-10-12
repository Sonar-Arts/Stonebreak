package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiElement;

import java.util.Map;

/**
 * What an asset reference draws (#294): a whole texture, one sprite region, or a skin that
 * picks a region per interaction state. Resolved by the {@link UiPaintHost}; the texture is
 * borrowed from the shared cache and never closed here.
 */
public sealed interface UiImage {

    /** The region to draw for {@code el}'s current state. */
    Region region(UiElement el);

    /** The region used for layout (a skin's normal state). */
    Region still();

    /** A whole texture as a plain region with every default. */
    static Region whole(MTexture texture) {
        return new Region(texture, Sprite.of("", 0, 0, texture.width(), texture.height()), true);
    }

    /**
     * @param sliceUsable false when the sheet's slice no longer fits (drawn stretched, reported)
     */
    record Region(MTexture texture, Sprite sprite, boolean sliceUsable) implements UiImage {
        @Override
        public Region region(UiElement el) {
            return this;
        }

        @Override
        public Region still() {
            return this;
        }
    }

    /**
     * A skin: disabled beats pressed beats hover beats keyboard focus beats normal, matching the
     * stone button's own state order; a state without a region falls back to normal.
     *
     * @param states skin state name ({@link Skin#STATES}) → resolved region; {@code normal} is present
     */
    record Skinned(Skin skin, Map<String, Region> states) implements UiImage {
        public Skinned {
            states = Map.copyOf(states);
        }

        @Override
        public Region region(UiElement el) {
            String state = !el.isEnabledInHierarchy() ? "disabled"
                : el.hasState(UiElement.ACTIVE) ? "pressed"
                : el.hasState(UiElement.HOVER) ? "hover"
                : el.hasState(UiElement.FOCUS_VISIBLE) ? "focused" : "normal";
            Region r = states.get(state);
            return r != null ? r : states.get("normal");
        }

        @Override
        public Region still() {
            return states.get("normal");
        }
    }
}
