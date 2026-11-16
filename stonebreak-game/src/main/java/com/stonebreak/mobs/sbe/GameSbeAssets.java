package com.stonebreak.mobs.sbe;

import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityModelLoader;

/**
 * The game's SBE assets: the engine {@link EntityModelLoader} reading the game's own resources
 * (JPMS keeps them from the engine, so this module supplies the lookup; #381).
 */
public final class GameSbeAssets {

    private GameSbeAssets() {
    }

    /** The (cached) entity asset at game resource {@code resourcePath} ({@code /sbe/Mobs/SB_Cow.sbe}). */
    public static EntityAsset load(String resourcePath) {
        return EntityModelLoader.load(resourcePath, GameSbeAssets.class::getResourceAsStream);
    }

    /** The (cached) attachable asset ({@code .omo}/{@code .sbe}/{@code .sbo}) at game resource {@code resourcePath}. */
    public static EntityAsset loadAttachableResource(String resourcePath) {
        return EntityModelLoader.loadAttachableResource(resourcePath, GameSbeAssets.class::getResourceAsStream);
    }
}
