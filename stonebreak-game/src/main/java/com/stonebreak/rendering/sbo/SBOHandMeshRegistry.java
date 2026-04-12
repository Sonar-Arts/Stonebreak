package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.core.API.commonBlockResources.meshing.MeshManager;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.textures.TextureAtlas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
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
 * <p>Current scope: cross-plane flowers. Cube blocks already render as
 * cubes in-hand via {@code HandBlockGeometry} with per-face atlas UVs,
 * which matches the in-world cube geometry — no mismatch to fix there.
 * Can be extended to cubes if SBO-driven non-cube cube replacements appear.
 */
public class SBOHandMeshRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SBOHandMeshRegistry.class);

    private final CBRResourceManager cbrManager;
    private final TextureAtlas textureAtlas;
    private final Map<BlockType, MeshManager.MeshResource> meshesByBlock =
            new EnumMap<>(BlockType.class);

    public SBOHandMeshRegistry(CBRResourceManager cbrManager, TextureAtlas textureAtlas) {
        this.cbrManager = cbrManager;
        this.textureAtlas = textureAtlas;
    }

    /**
     * Build hand-rendering meshes for all SBO-bridged flowers.
     *
     * @param bridge the populated SBO bridge
     * @return number of meshes built
     */
    public int buildFlowerMeshes(SBOBlockBridge bridge) {
        meshesByBlock.clear();
        if (cbrManager == null) {
            logger.warn("CBRResourceManager unavailable — SBO hand meshes will not be built");
            return 0;
        }
        MeshManager meshManager = cbrManager.getMeshManager();
        int built = 0;

        for (BlockType type : BlockType.values()) {
            if (!type.isFlower()) continue;
            if (!bridge.isSBOBlock(type)) continue;

            SBOParseResult sbo = bridge.getSBODefinition(type);
            ParsedMeshData mesh = sbo.meshData();
            if (mesh == null || !mesh.hasGeometry() || mesh.indices() == null) {
                logger.debug("No SBO geometry for {} — falling back to CBR cross", type);
                continue;
            }

            float[] atlasUV = textureAtlas.getTextureCoordinatesForBlock(type);
            if (atlasUV == null || atlasUV.length < 4) {
                logger.debug("No atlas UVs for {} — skipping hand mesh build", type);
                continue;
            }

            float[] interleaved = buildInterleaved(mesh, atlasUV);
            String name = "sbo_hand_" + type.name().toLowerCase();
            MeshManager.MeshResource resource =
                    meshManager.createCustomMesh(name, interleaved, mesh.indices());
            meshesByBlock.put(type, resource);
            built++;
            logger.info("Built SBO hand mesh for {} — {} verts, {} tris",
                    type, mesh.getVertexCount(), mesh.getTriangleCount());
        }

        logger.info("SBO hand mesh registry: built {} flower meshes", built);
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
     * Interleave x,y,z,u,v into the layout {@link MeshManager#createCustomMesh}
     * expects, while normalizing the mesh to fit the held-item viewport
     * (-0.5..0.5 cube) and remapping UVs from source material space to the
     * block's atlas region.
     */
    private float[] buildInterleaved(ParsedMeshData mesh, float[] atlasUV) {
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

        float u1 = atlasUV[0], v1 = atlasUV[1];
        float u2 = atlasUV[2], v2 = atlasUV[3];
        float du = u2 - u1, dv = v2 - v1;

        float[] out = new float[vCount * 5];
        for (int i = 0; i < vCount; i++) {
            int s = i * 3;
            int t = i * 2;
            int d = i * 5;
            out[d]     = (verts[s]     - cx) * scale;
            out[d + 1] = (verts[s + 1] - cy) * scale;
            out[d + 2] = (verts[s + 2] - cz) * scale;
            float u = (uvs != null && t     < uvs.length) ? uvs[t]     : 0f;
            float v = (uvs != null && t + 1 < uvs.length) ? uvs[t + 1] : 0f;
            out[d + 3] = u1 + u * du;
            out[d + 4] = v1 + v * dv;
        }
        return out;
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
