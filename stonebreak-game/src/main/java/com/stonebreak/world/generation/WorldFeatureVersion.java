package com.stonebreak.world.generation;

/**
 * Which set of world FEATURES a DaedalusTGM-Exp (DIFFUSION) world was created with: fixed at creation and saved with
 * the world ({@code WorldData#getWorldFeatures}, world.json {@code "worldFeatures"}), like the height curve. A feature
 * that writes cells next to already generated terrain (boulders) must never switch on for a saved world, or new cubes
 * would grow boulders beside old ones that have none; so it comes with a new version and only new worlds get it.
 *
 * <p>Unlike {@link HeightCurveVersion} this never changes the TGMPipe tiles (features are native population only), so it
 * is not part of the TGMPipe world identity or the multiplayer handshake: only the world's generator (the server) needs
 * it. Standard (noise) worlds carry the value and ignore it.
 */
public enum WorldFeatureVersion {

    /** Worlds saved before 2026-11-19 (no {@code worldFeatures} in world.json): no boulders. */
    V0(0, false, 0, false, false),
    /** 2026-11-19: boulders, scree and erratics (native {@code SBW_FLAG_BOULDERS}) with rock shape bank 1. */
    V1(1, true, 1, false, false),
    /**
     * 2026-11-19 (agent L): V1 + land surface materials from the terrain model's surface-detail channels (native
     * {@code SBW_FLAG_LAND_SURFACE}: bare rock, talus, broken ground where the tiles carry rock / scree / roughness).
     */
    V2(2, true, 1, true, false),
    /**
     * 2026-11-19 (agent V): V2 + vegetation from the terrain model's vegetation channels (native
     * {@code SBW_FLAG_VEGETATION}: tree density, size and clearings, bushes, grass, bare and wet-meadow patches where
     * the tiles carry canopy / ground-cover channels; ETH canopy height + ESA WorldCover trained).
     */
    V3(3, true, 1, true, true);

    /** The feature set of worlds saved without one (older saves). */
    public static final WorldFeatureVersion LEGACY = V0;
    /** The feature set new worlds get. */
    public static final WorldFeatureVersion CURRENT = V3;

    private final int number;
    private final boolean boulders;
    private final int rockBank;
    private final boolean landSurface;
    private final boolean vegetation;

    WorldFeatureVersion(int number, boolean boulders, int rockBank, boolean landSurface, boolean vegetation) {
        this.number = number;
        this.boulders = boulders;
        this.rockBank = rockBank;
        this.landSurface = landSurface;
        this.vegetation = vegetation;
    }

    /**
     * Whether trees and ground vegetation follow the tiles' vegetation channels ({@link NativeCubeGenerator#FLAG_VEGETATION});
     * worlds created before it keep the biome-rule vegetation, so explored edges never show a forest seam.
     */
    public boolean vegetation() {
        return vegetation;
    }

    /**
     * Whether dry land's surface block follows the tiles' surface-detail channels ({@link NativeCubeGenerator#FLAG_LAND_SURFACE});
     * worlds created before it keep their surface, so explored edges never show a material seam.
     */
    public boolean landSurface() {
        return landSurface;
    }

    /** The persisted number. */
    public int number() {
        return number;
    }

    /** Whether the world places boulders ({@link NativeCubeGenerator#FLAG_BOULDERS}). */
    public boolean boulders() {
        return boulders;
    }

    /**
     * The rock shape bank id the world's boulders read ({@code worldgen/rocks/rock_bank_v<id>.sbrb}); 0 = procedural
     * shapes only. A new bank (e.g. the learned one) ships under a new id with a new version, so older worlds keep theirs.
     */
    public int rockBank() {
        return rockBank;
    }

    /** {@code version}, or {@link #CURRENT} when null. */
    public static WorldFeatureVersion orCurrent(WorldFeatureVersion version) {
        return version == null ? CURRENT : version;
    }

    /** The version numbered {@code number}; null for an unknown number. */
    public static WorldFeatureVersion of(int number) {
        for (WorldFeatureVersion v : values()) {
            if (v.number == number) {
                return v;
            }
        }
        return null;
    }

    /** A persisted number; {@code fallback} when missing (negative) or unknown. */
    public static WorldFeatureVersion of(long number, WorldFeatureVersion fallback) {
        if (number < 0 || number > Integer.MAX_VALUE) {
            return fallback;
        }
        WorldFeatureVersion v = of((int) number);
        return v != null ? v : fallback;
    }
}
