package com.stonebreak.world.generation;

/**
 * Which set of world FEATURES a DaedalusTGM-Exp (DIFFUSION) world was created with: fixed at creation and saved with
 * the world ({@code WorldData#getWorldFeatures}, world.json {@code "worldFeatures"}), like the height curve. A feature
 * that writes cells next to already generated terrain (boulders) must never switch on for a saved world, or new cubes
 * would grow boulders beside old ones that have none; so it comes with a new version and only new worlds get it.
 *
 * <p>Unlike {@link HeightCurveVersion} this never changes the TGMPipe tiles (features are native population only), so it
 * is not part of the TGMPipe world identity. Standard (noise) worlds carry the value and ignore it, except for The
 * Shade ({@link #shade()}, V5), which both kinds use. The Shade changes biomes a client reads too (night fog, LOD), so
 * the handshake carries the number ({@code WelcomeS2C}).
 */
public enum WorldFeatureVersion {

    /** Worlds saved before 2026-11-19 (no {@code worldFeatures} in world.json): no boulders. */
    V0(0, false, 0, false, false, false, false),
    /** 2026-11-19: boulders, scree and erratics (native {@code SBW_FLAG_BOULDERS}) with rock shape bank 1. */
    V1(1, true, 1, false, false, false, false),
    /**
     * 2026-11-19 (agent L): V1 + land surface materials from the terrain model's surface-detail channels (native
     * {@code SBW_FLAG_LAND_SURFACE}: bare rock, talus, broken ground where the tiles carry rock / scree / roughness).
     */
    V2(2, true, 1, true, false, false, false),
    /**
     * 2026-11-19 (agent V): V2 + vegetation from the terrain model's vegetation channels (native
     * {@code SBW_FLAG_VEGETATION}: tree density, size and clearings, bushes, grass, bare and wet-meadow patches where
     * the tiles carry canopy / ground-cover channels; ETH canopy height + ESA WorldCover trained).
     */
    V3(3, true, 1, true, true, false, false),
    /**
     * 2026-11-20: V3 + open land (native {@code SBW_FLAG_VEG_OPEN_LAND}): V3 temperate worlds came out ~90 % closed
     * forest (the node predicts natural vegetation and its canopy cover saturates in forest), so V4 adds big meadow
     * patches per biome and a steeper cover-to-density curve; closed forest stays closed.
     */
    V4(4, true, 1, true, true, true, false),
    /**
     * 2026-11-21 (Project-Heracles): V4 + The Shade, dry temperate woodland of dry oaks under a night ground fog (native
     * {@code SBW_FLAG_SHADE}; DaedalusTGM-Exp: the chance-based {@code ShadeOverlay} on the model's plains / sparse
     * forest). Unlike the features above it is used by Standard worlds too.
     */
    V5(5, true, 1, true, true, true, true);

    /** The feature set of worlds saved without one (older saves). */
    public static final WorldFeatureVersion LEGACY = V0;
    /** The feature set new worlds get. */
    public static final WorldFeatureVersion CURRENT = V5;

    private final int number;
    private final boolean boulders;
    private final int rockBank;
    private final boolean landSurface;
    private final boolean vegetation;
    private final boolean openLand;
    private final boolean shade;

    WorldFeatureVersion(int number, boolean boulders, int rockBank, boolean landSurface, boolean vegetation,
                        boolean openLand, boolean shade) {
        this.number = number;
        this.boulders = boulders;
        this.rockBank = rockBank;
        this.landSurface = landSurface;
        this.vegetation = vegetation;
        this.openLand = openLand;
        this.shade = shade;
    }

    /**
     * Whether the world has The Shade ({@link NativeCubeGenerator#FLAG_SHADE}) - Standard and DaedalusTGM-Exp worlds
     * alike; worlds created before it keep their biomes, so explored edges never show a Shade seam.
     */
    public boolean shade() {
        return shade;
    }

    /**
     * Whether trees and ground vegetation follow the tiles' vegetation channels ({@link NativeCubeGenerator#FLAG_VEGETATION});
     * worlds created before it keep the biome-rule vegetation, so explored edges never show a forest seam.
     */
    public boolean vegetation() {
        return vegetation;
    }

    /**
     * Whether vegetation opens the land up ({@link NativeCubeGenerator#FLAG_VEG_OPEN_LAND}: meadows, steeper density
     * curve); only with {@link #vegetation()}. V3 worlds keep their denser forests at the explored edge.
     */
    public boolean openLand() {
        return openLand;
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
