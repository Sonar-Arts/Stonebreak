package com.stonebreak.world.chunk.api.mightyMesh;

import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.World;
import com.stonebreak.world.voxel.CubeRenderAssets;

/**
 * What is left of the Java Mighty Mesh System facade after the cubic voxel runtime (#447): meshing is native
 * ({@code cv_mesh_cube} inside the world's stream, GPU-ready records via {@code CubeEvents.MeshConsumer}), so
 * this only keeps the bootstrap seam the renderer and bootstrap still call - the block texture array and the
 * SBO stamp emitter, which it forwards to {@link CubeRenderAssets} (per-face texture layers and host models
 * of every display world). The Java mesh pipeline, CCO adapter, native column mesher binding and water mesher
 * are deleted.
 */
public final class MmsAPI {

    private static volatile MmsAPI instance;

    private final BlockTextureArray blockTextureArray;

    private MmsAPI(BlockTextureArray blockTextureArray) {
        this.blockTextureArray = blockTextureArray;
    }

    /** Registers the block texture array (render thread, before any display world). */
    public static synchronized MmsAPI initialize(BlockTextureArray blockTextureArray, World world) {
        if (instance == null) {
            instance = new MmsAPI(blockTextureArray);
        }
        return instance;
    }

    public static MmsAPI getInstance() {
        MmsAPI a = instance;
        if (a == null) {
            throw new IllegalStateException("MmsAPI not initialized");
        }
        return a;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public BlockTextureArray getBlockTextureArray() {
        return blockTextureArray;
    }

    /** No-op: worlds no longer bind to the mesher (each world's stream meshes its own cubes). */
    public void setWorld(World world) {
    }

    /** Hands the SBO stamp emitter to the native world configuration ({@link CubeRenderAssets}). */
    public void setSBOStampEmitter(com.openmason.engine.voxel.stamp.render.StampEmitter emitter) {
        CubeRenderAssets.setEmitter(emitter);
    }

    /** No-op: face culling is native (block table + face rules + model coverage). */
    public void setSBOCullingService(com.openmason.engine.voxel.mms.mmsIntegration.MmsFaceCullingService cullingService) {
    }

    public static synchronized void shutdown() {
        instance = null;
    }
}
