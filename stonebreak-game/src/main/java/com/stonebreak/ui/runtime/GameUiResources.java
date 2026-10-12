package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.masonry.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stonebreak UI resources for hosts outside the game window (the Open Mason preview), read
 * through this module because JPMS hides game resources from other modules. Previews built on
 * these draw with exactly the font and textures the game uses.
 */
public final class GameUiResources {

    /** The game's UI font. */
    public static final String TYPEFACE_RESOURCE = "/fonts/Minecraft.ttf";

    private GameUiResources() {
    }

    /** A new typeface owned by the caller (close it when the host shuts down). */
    public static Typeface loadTypeface() throws IOException {
        try (InputStream in = GameUiResources.class.getResourceAsStream(TYPEFACE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing game font " + TYPEFACE_RESOURCE);
            }
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        }
    }

    /**
     * A game SBT texture from the shared cache, which owns it: never close the result.
     *
     * @return {@code null} when the resource is missing or cannot be decoded
     */
    public static MTexture texture(String classpathResource) {
        return MTextureRegistry.get(classpathResource);
    }
}
