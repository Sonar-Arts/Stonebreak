package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import io.github.humbleui.skija.Canvas;

import java.util.Map;
import java.util.function.Function;

/**
 * What the painter needs from its host: textures for asset references and the host's
 * immediate-mode draw providers. The game resolves through the SBUI dependency table and the
 * shared {@code MTextureCache}; the editor through the project; tests through maps.
 */
public interface UiPaintHost {

    /** Texture for an asset reference ({@code stonebreak:ui/textures/panel}), or null. Borrowed, never closed. */
    MTexture texture(String assetRef);

    /**
     * What {@code assetRef} draws: a whole texture, or a sprite region/skin for a
     * {@code <sheet>#<name>} reference (#294). Null when unresolvable or invalid.
     */
    default UiImage image(String assetRef) {
        MTexture t = texture(assetRef);
        return t == null ? null : UiImage.whole(t);
    }

    /** Draw provider registered under {@code id}, or null. */
    UiDrawProvider drawProvider(String id);

    /** Host immediate drawing inside an element (item icons, 3D previews, the crucible). */
    @FunctionalInterface
    interface UiDrawProvider {
        /**
         * @param rect  the element's device-pixel rect
         * @param scale device pixels per logical pixel
         */
        void draw(Canvas canvas, UiElement element, UiRect rect, float scale);
    }

    UiPaintHost NONE = of(id -> null, Map.of());

    static UiPaintHost of(Function<String, MTexture> textures, Map<String, UiDrawProvider> providers) {
        return of(textures, null, providers);
    }

    /** @param images sprite-aware resolution; null = whole textures from {@code textures} */
    static UiPaintHost of(Function<String, MTexture> textures, Function<String, UiImage> images,
                          Map<String, UiDrawProvider> providers) {
        Map<String, UiDrawProvider> p = Map.copyOf(providers);
        return new UiPaintHost() {
            @Override
            public MTexture texture(String assetRef) {
                return assetRef == null ? null : textures.apply(assetRef);
            }

            @Override
            public UiImage image(String assetRef) {
                if (assetRef == null) {
                    return null;
                }
                if (images != null) {
                    return images.apply(assetRef);
                }
                MTexture t = textures.apply(assetRef);
                return t == null ? null : UiImage.whole(t);
            }

            @Override
            public UiDrawProvider drawProvider(String id) {
                return id == null ? null : p.get(id);
            }
        };
    }
}
