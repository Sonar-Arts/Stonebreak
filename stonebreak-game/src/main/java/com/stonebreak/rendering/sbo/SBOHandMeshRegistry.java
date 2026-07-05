package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.openmason.engine.rendering.cbr.meshing.MeshManager;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.textures.BlockTextureArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds per-block hand-rendering meshes from SBO geometry so in-hand renders
 * match the in-world SBO model shape.
 *
 * <p>Without this registry, {@code HandItemRenderer} falls back to the CBR
 * hard-coded {@code CROSS}/cube prebuilt meshes, which means a custom rose
 * SBO's two-plane geometry is visible in-world (via {@code SBORendererAPI})
 * but held-in-hand always renders as a generic symmetric cross. This class
 * closes that gap by converting each SBO's {@link ParsedMeshData} into a
 * CBR custom mesh sized to the hand viewport, with UVs remapped from the
 * source material's [0..1] space to the block's atlas region (so sampling
 * hits the integrator-overlaid SBO pixels on the atlas).
 *
 * <p>Scope: cross-plane flowers (single texture layer, shared vertices) and
 * animated non-cube blocks like the oak door (per-face texture layers derived
 * from {@code triangleToFaceId}, de-indexed so each triangle can carry its own
 * layer). Cube blocks already render as cubes in-hand with per-face layers —
 * no mismatch to fix there.
 */
public class SBOHandMeshRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SBOHandMeshRegistry.class);

    private final CBRResourceManager cbrManager;
    private final BlockTextureArray textureArray;
    private final Map<BlockType, MeshManager.MeshResource> meshesByBlock = new HashMap<>();

    public SBOHandMeshRegistry(CBRResourceManager cbrManager, BlockTextureArray textureArray) {
        this.cbrManager = cbrManager;
        this.textureArray = textureArray;
    }

    /**
     * Build hand-rendering meshes for all SBO blocks whose in-world shape is
     * not a plain cube: flowers (cross planes) and animated blocks (doors).
     *
     * @param bridge the populated SBO bridge
     * @return number of meshes built
     */
    public int buildMeshes(SBOBlockBridge bridge) {
        meshesByBlock.clear();
        if (cbrManager == null) {
            logger.warn("CBRResourceManager unavailable — SBO hand meshes will not be built");
            return 0;
        }
        MeshManager meshManager = cbrManager.getMeshManager();
        int built = 0;

        for (BlockType type : BlockType.values()) {
            boolean animated = com.stonebreak.blocks.anim.AnimatedBlockRegistry.isAnimatedType(type);
            if (!type.isFlower() && !animated) continue;
            if (!bridge.isSBOBlock(type)) continue;

            SBOParseResult sbo = bridge.getSBODefinition(type);
            ParsedMeshData mesh = sbo.meshData();
            if (mesh == null || !mesh.hasGeometry() || mesh.indices() == null) {
                logger.debug("No SBO geometry for {} — falling back to CBR cross/cube", type);
                continue;
            }

            String name = "sbo_hand_" + type.name().toLowerCase();
            MeshManager.MeshResource resource;
            if (animated && mesh.triangleToFaceId() != null
                    && mesh.triangleToFaceId().length * 3 == mesh.indices().length) {
                // Multi-face model (door): per-triangle texture layers.
                resource = meshManager.createCustomLayeredMesh(name,
                        buildInterleavedPerFace(type, mesh), sequentialIndices(mesh.indices().length));
            } else {
                // Cross-plane flowers: one layer everywhere (unchanged path).
                int layer = textureArray.getBlockFaceLayer(type, 0);
                resource = meshManager.createCustomLayeredMesh(name,
                        buildInterleaved(mesh, layer), mesh.indices());
            }
            meshesByBlock.put(type, resource);
            built++;
            logger.info("Built SBO hand mesh for {} — {} verts, {} tris",
                    type, mesh.getVertexCount(), mesh.getTriangleCount());
        }

        logger.info("SBO hand mesh registry: built {} meshes", built);
        return built;
    }

    /**
     * Get the SBO-derived hand mesh for a block, or null if none exists.
     * Callers should fall back to their default rendering path on null.
     */
    public MeshManager.MeshResource getMesh(BlockType type) {
        if (type == null) return null;
        return meshesByBlock.get(type);
    }

    /**
     * Interleave {@code x,y,z,u,v,layer} for {@link MeshManager#createCustomLayeredMesh},
     * normalizing the mesh to fit the held-item viewport (-0.5..0.5 cube). UVs
     * are the SBO model's tile-local coordinates; {@code layer} selects the
     * block texture array layer.
     */
    private float[] buildInterleaved(ParsedMeshData mesh, int layer) {
        float[] verts = mesh.vertices();
        float[] uvs = mesh.texCoords();
        int vCount = mesh.getVertexCount();

        // Bounding box — we normalize to -0.5..0.5 so arbitrarily-sized SBO
        // authoring (16-unit Minecraft-style, 1-unit local, etc.) fits the
        // hand viewport without requiring matching convention at edit time.
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vCount; i++) {
            float x = verts[i * 3];
            float y = verts[i * 3 + 1];
            float z = verts[i * 3 + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        float sizeX = maxX - minX;
        float sizeY = maxY - minY;
        float sizeZ = maxZ - minZ;
        float maxSize = Math.max(sizeX, Math.max(sizeY, sizeZ));
        if (maxSize <= 0.0001f) maxSize = 1.0f;
        float scale = 1.0f / maxSize; // uniform scale — preserves aspect
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;

        float[] out = new float[vCount * 6];
        for (int i = 0; i < vCount; i++) {
            int s = i * 3;
            int t = i * 2;
            int d = i * 6;
            out[d]     = (verts[s]     - cx) * scale;
            out[d + 1] = (verts[s + 1] - cy) * scale;
            out[d + 2] = (verts[s + 2] - cz) * scale;
            out[d + 3] = (uvs != null && t     < uvs.length) ? uvs[t]     : 0f;
            out[d + 4] = (uvs != null && t + 1 < uvs.length) ? uvs[t + 1] : 0f;
            out[d + 5] = layer;
        }
        return out;
    }

    /**
     * De-indexed variant of {@link #buildInterleaved} for multi-face models
     * (the door): each triangle's vertices carry the texture-array layer of
     * that triangle's face ({@code triangleToFaceId} → GMR→MMS →
     * {@code getBlockFaceLayer}). Vertices shared between faces are duplicated
     * because the layer is a per-vertex attribute. Geometry is normalized to
     * the same -0.5..0.5 viewport cube, preserving aspect (a 1×2×0.1 door
     * shows as a tall thin panel).
     */
    private float[] buildInterleavedPerFace(BlockType type, ParsedMeshData mesh) {
        float[] verts = mesh.vertices();
        float[] uvs = mesh.texCoords();
        int[] indices = mesh.indices();
        int[] triFace = mesh.triangleToFaceId();
        int vCount = mesh.getVertexCount();

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vCount; i++) {
            float x = verts[i * 3];
            float y = verts[i * 3 + 1];
            float z = verts[i * 3 + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        float maxSize = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (maxSize <= 0.0001f) maxSize = 1.0f;
        float scale = 1.0f / maxSize;
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;

        float[] out = new float[indices.length * 6];
        for (int tri = 0; tri < triFace.length; tri++) {
            int mmsFace = com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions
                    .gmrToMms(triFace[tri]);
            float layer = textureArray.getBlockFaceLayer(type, mmsFace);
            for (int corner = 0; corner < 3; corner++) {
                int vi = indices[tri * 3 + corner];
                int s = vi * 3;
                int t = vi * 2;
                int d = (tri * 3 + corner) * 6;
                out[d]     = (verts[s]     - cx) * scale;
                out[d + 1] = (verts[s + 1] - cy) * scale;
                out[d + 2] = (verts[s + 2] - cz) * scale;
                out[d + 3] = (uvs != null && t     < uvs.length) ? uvs[t]     : 0f;
                out[d + 4] = (uvs != null && t + 1 < uvs.length) ? uvs[t + 1] : 0f;
                out[d + 5] = layer;
            }
        }
        return out;
    }

    /** 0..n-1 index buffer for de-indexed geometry. */
    private static int[] sequentialIndices(int count) {
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = i;
        }
        return indices;
    }

    /**
     * Release GPU resources held by all registered meshes.
     */
    public void cleanup() {
        for (MeshManager.MeshResource resource : meshesByBlock.values()) {
            if (resource != null) {
                resource.cleanup();
            }
        }
        meshesByBlock.clear();
    }
}
