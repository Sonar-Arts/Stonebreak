package com.stonebreak.world.generation;

/**
 * Which DaedalusTGM terrain MODEL a DaedalusTGM-Exp (DIFFUSION) world was created with: fixed at creation and saved with
 * the world ({@code WorldData#getTerrainModel}, world.json {@code "terrainModel"} + {@code "terrainModelId"}), part of
 * its identity like the seed, the height curve and the climate layout (review-python HIGH-1, 2026-11-19). Unexplored
 * cubes of a saved world must come from the model that made the explored ones, or the two meet at a wall; so a world
 * keeps its model when the game's default changes, and runs it whenever that model is installed
 * ({@code TerrainModels#resolve}).
 *
 * <ul>
 *   <li>{@link #path}: the model folder as the TGMPipe service is given it, relative to {@code Models/DaedalusTGM-Exp}
 *       ({@code checkpoints/exp2-r1}) or absolute for a folder outside it. Always set.</li>
 *   <li>{@link #contentId}: a release folder's {@code release.json} {@code content_id} (scripts/make_release.py: a
 *       hash of every weight file) - the model's real identity, so a release found under another folder name is still
 *       recognised, and a different release rebuilt at the same path is not mistaken for it. Empty for a dev folder
 *       (training symlinks, no manifest) and for {@link #LEGACY}: the path is then the identity.</li>
 * </ul>
 *
 * <p>Like {@link WorldFeatureVersion} this is not a TGMPipe world (the handshake): the whole service runs one model, so
 * the game picks the service's model per world ({@code TGMPipe#useWorldModel}). Standard (noise) worlds carry the value
 * and ignore it.
 */
public record TerrainModelRef(String path, String contentId) {

    /**
     * The model of worlds saved without one (every Daedalus world before 2026-11-19): DaedalusTGM-Exp v4 (EXP-1). v4 was
     * removed from the game on 2026-11-19, so such a world runs the default model with a notice
     * ({@code TerrainModels#resolve}); the value stays the world's honest record of what made its explored area.
     */
    public static final TerrainModelRef LEGACY = new TerrainModelRef("checkpoints/v4", "");

    /** Longest path / content id accepted from a save or the network (a path, a sha256 hex). */
    public static final int MAX_CHARS = 512;

    public TerrainModelRef {
        path = path == null ? "" : path.trim().replace('\\', '/');
        contentId = contentId == null ? "" : contentId.trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("a terrain model needs a path");
        }
    }

    /** A persisted value: {@code path} (null/blank = {@link #LEGACY}, a world saved before models were recorded). */
    public static TerrainModelRef parse(String path, String contentId) {
        if (path == null || path.isBlank()) {
            return LEGACY;
        }
        return new TerrainModelRef(path, contentId);
    }

    /** {@code ref}, or {@link #LEGACY} when null. */
    public static TerrainModelRef orLegacy(TerrainModelRef ref) {
        return ref == null ? LEGACY : ref;
    }

    /** True for a release (it has a content id). */
    public boolean isRelease() {
        return !contentId.isEmpty();
    }

    /** The folder's last name ({@code exp2-r3}, {@code world_exp2f}): what the player is told. */
    public String displayName() {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        return contentId.isEmpty() ? name : name + " (" + contentId.substring(0, Math.min(12, contentId.length())) + ")";
    }
}
