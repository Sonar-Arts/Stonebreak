package com.stonebreak.mobs.sbe;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.openmason.engine.entity.sbe.SbeEntityLoader;

/**
 * The game's SBE assets: the engine {@link SbeEntityLoader} reading the game's own resources
 * (JPMS keeps them from the engine, so this module supplies the lookup; #381).
 */
public final class GameSbeAssets {

    private GameSbeAssets() {
    }

    /** The (cached) entity asset at game resource {@code resourcePath} ({@code /sbe/Mobs/SB_Cow.sbe}). */
    public static SbeEntityAsset load(String resourcePath) {
        return SbeEntityLoader.load(resourcePath, GameSbeAssets.class::getResourceAsStream);
    }

    /** The (cached) attachable asset ({@code .omo}/{@code .sbe}/{@code .sbo}) at game resource {@code resourcePath}. */
    public static SbeEntityAsset loadAttachableResource(String resourcePath) {
        return SbeEntityLoader.loadAttachableResource(resourcePath, GameSbeAssets.class::getResourceAsStream);
    }
}
