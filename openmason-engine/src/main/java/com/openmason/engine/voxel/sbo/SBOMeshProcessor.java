package com.openmason.engine.voxel.sbo;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.IBlockType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Processes and caches SBO mesh data for use in chunk mesh generation.
 *
 * <p>For each SBO block type, pre-computes flat normals, de-indexes
 * the mesh, and loads per-face GPU textures from the SBO materials.
 *
 * <p>Cached by block ID so processing happens once at startup, not per-chunk.
 */
public class SBOMeshProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SBOMeshProcessor.class);

    /**
     * Static mapping from GMR face ID to MMS face ID.
     * GMR: 0=FRONT(+Z), 1=BACK(-Z), 2=LEFT(-X), 3=RIGHT(+X), 4=TOP(+Y), 5=BOTTOM(-Y)
     * MMS: 0=TOP(+Y), 1=BOTTOM(-Y), 2=NORTH(-Z), 3=SOUTH(+Z), 4=EAST(+X), 5=WEST(-X)
     */
    private static final int[] GMR_TO_MMS_FACE = {
            3, // GMR 0 (FRONT +Z)  → MMS 3 (SOUTH +Z)
            2, // GMR 1 (BACK -Z)   → MMS 2 (NORTH -Z)
            5, // GMR 2 (LEFT -X)   → MMS 5 (WEST -X)
            4, // GMR 3 (RIGHT +X)  → MMS 4 (EAST +X)
            0, // GMR 4 (TOP +Y)    → MMS 0 (TOP +Y)
            1  // GMR 5 (BOTTOM -Y) → MMS 1 (BOTTOM -Y)
    };

    /** Cached processed meshes keyed by block type ID. */
    private final Map<Integer, SBONormalComputer.ProcessedMesh> cache = new HashMap<>();

    /** Cached face-to-triangle mappings keyed by block type ID. */
    private final Map<Integer, int[]> faceIdCache = new HashMap<>();

    /** Per-material GPU texture IDs keyed by block type ID. Maps materialId -> GPU textureId. */
    private final Map<Integer, Map<Integer, Integer>> materialTextureCache = new HashMap<>();

    /** SBO faceId → MMS faceId axis mapping keyed by block type ID. */
    private final Map<Integer, int[]> faceIdMappingCache = new HashMap<>();

    /** Per-face resolved GPU texture IDs keyed by block type ID. Maps faceId -> GPU textureId. */
    private final Map<Integer, Map<Integer, Integer>> faceTextureCache = new HashMap<>();

    /**
     * Process and cache an SBO block's mesh data and textures.
     *
     * @param blockType the block type this SBO defines
     * @param sbo       the parsed SBO result containing mesh data and materials
     * @return true if successfully processed
     */
    public boolean process(IBlockType blockType, SBOParseResult sbo) {
        ParsedMeshData meshData = sbo.meshData();
        if (meshData == null || !meshData.hasGeometry()) {
            logger.warn("SBO for {} has no mesh data", blockType.getName());
            return false;
        }

        // Compute flat normals and de-index
        SBONormalComputer.ProcessedMesh processed = SBONormalComputer.compute(
                meshData.vertices(),
                meshData.texCoords(),
                meshData.indices()
        );

        cache.put(blockType.getId(), processed);

        // Cache the triangle-to-face mapping, remapped from GMR convention to MMS convention.
        // GMR: 0=FRONT(+Z), 1=BACK(-Z), 2=LEFT(-X), 3=RIGHT(+X), 4=TOP(+Y), 5=BOTTOM(-Y)
        // MMS: 0=TOP(+Y), 1=BOTTOM(-Y), 2=NORTH(-Z), 3=SOUTH(+Z), 4=EAST(+X), 5=WEST(-X)
        if (meshData.triangleToFaceId() != null) {
            int[] originalFaceIds = meshData.triangleToFaceId();

            // Remap face IDs from GMR to MMS convention
            int[] remappedFaceIds = new int[originalFaceIds.length];
            for (int i = 0; i < originalFaceIds.length; i++) {
                remappedFaceIds[i] = GMR_TO_MMS_FACE[Math.clamp(originalFaceIds[i], 0, 5)];
            }
            faceIdCache.put(blockType.getId(), remappedFaceIds);
            faceIdMappingCache.put(blockType.getId(), GMR_TO_MMS_FACE);

            logger.info("SBO {} face IDs remapped GMR→MMS", blockType.getName());
        }

        // Load per-face textures from SBO materials
        loadFaceTextures(blockType, sbo);

        // Log mesh bounds
        float[] verts = processed.vertices();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < verts.length; i += 3) {
            minX = Math.min(minX, verts[i]); maxX = Math.max(maxX, verts[i]);
            minY = Math.min(minY, verts[i+1]); maxY = Math.max(maxY, verts[i+1]);
            minZ = Math.min(minZ, verts[i+2]); maxZ = Math.max(maxZ, verts[i+2]);
        }
        logger.info("Processed SBO mesh for {}: {} vertices, {} triangles, bounds X[{},{}] Y[{},{}] Z[{},{}], {} face textures",
                blockType.getName(), processed.vertexCount(), processed.triangleCount(),
                minX, maxX, minY, maxY, minZ, maxZ,
                faceTextureCache.getOrDefault(blockType.getId(), Map.of()).size());

        return true;
    }

    /**
     * Load per-face textures from SBO materials and upload to GPU.
     * Resolves faceId → materialId → GPU texture via the face mappings.
     */
    private void loadFaceTextures(IBlockType blockType, SBOParseResult sbo) {
        List<ParsedMaterialData> materials = sbo.materials();
        if (materials == null || materials.isEmpty()) {
            logger.warn("No materials in SBO for {}", blockType.getName());
            return;
        }

        // Upload each material's texture to GPU, keyed by materialId
        Map<Integer, Integer> matTextures = new HashMap<>();
        for (ParsedMaterialData material : materials) {
            if (material.texturePng() == null || material.texturePng().length == 0) continue;

            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(material.texturePng()));
                if (image == null) continue;

                int gpuTexId = uploadTexture(image);
                if (gpuTexId > 0) {
                    matTextures.put(material.materialId(), gpuTexId);
                    logger.debug("Uploaded SBO material texture for {} matId={}: {}x{} -> texId={}",
                            blockType.getName(), material.materialId(), image.getWidth(), image.getHeight(), gpuTexId);
                }
            } catch (Exception e) {
                logger.error("Failed to load SBO texture for {} material {}", blockType.getName(), material.materialId(), e);
            }
        }
        materialTextureCache.put(blockType.getId(), matTextures);

        // Build faceId → GPU textureId using the face mappings (faceId → materialId)
        Map<Integer, Integer> faceTextures = new HashMap<>();
        List<ParsedFaceMapping> faceMappings = sbo.faceMappings();
        if (faceMappings != null) {
            for (ParsedFaceMapping fm : faceMappings) {
                Integer gpuTex = matTextures.get(fm.materialId());
                if (gpuTex != null) {
                    faceTextures.put(fm.faceId(), gpuTex);
                }
            }
        }

        // Fallback: if no face mappings, assume materialId == faceId
        if (faceTextures.isEmpty() && !matTextures.isEmpty()) {
            faceTextures.putAll(matTextures);
            logger.debug("No face mappings for {}, using materialId as faceId", blockType.getName());
        }

        // Remap face textures from SBO faceId to MMS faceId using the axis mapping
        int[] faceIdMapping = faceIdMappingCache.get(blockType.getId());
        if (faceIdMapping != null && !faceTextures.isEmpty()) {
            Map<Integer, Integer> remappedTextures = new HashMap<>();
            for (int sboFace = 0; sboFace < Math.min(6, faceIdMapping.length); sboFace++) {
                Integer tex = faceTextures.get(sboFace);
                if (tex != null) {
                    remappedTextures.put(faceIdMapping[sboFace], tex);
                }
            }
            faceTextures = remappedTextures;
        }

        if (!faceTextures.isEmpty()) {
            faceTextureCache.put(blockType.getId(), faceTextures);
            logger.info("Resolved {} face textures for {} (remapped to MMS axes)", faceTextures.size(), blockType.getName());
        }
    }

    /**
     * Upload a BufferedImage to an OpenGL texture.
     */
    private int uploadTexture(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int argb = image.getRGB(px, py);
                buffer.put((byte) ((argb >> 16) & 0xFF));
                buffer.put((byte) ((argb >> 8) & 0xFF));
                buffer.put((byte) (argb & 0xFF));
                buffer.put((byte) ((argb >> 24) & 0xFF));
            }
        }
        buffer.flip();

        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        return texId;
    }

    public SBONormalComputer.ProcessedMesh getMesh(IBlockType blockType) {
        return cache.get(blockType.getId());
    }

    public boolean hasMesh(IBlockType blockType) {
        return cache.containsKey(blockType.getId());
    }

    public int[] getTriangleToFaceId(IBlockType blockType) {
        return faceIdCache.get(blockType.getId());
    }

    /**
     * Get the GPU texture ID for a specific face of a block type.
     *
     * @param blockType the block type
     * @param faceId    the face/material ID
     * @return GPU texture ID, or 0 if not found
     */
    public int getFaceTextureId(IBlockType blockType, int faceId) {
        Map<Integer, Integer> faceTextures = faceTextureCache.get(blockType.getId());
        if (faceTextures == null) return 0;
        return faceTextures.getOrDefault(faceId, 0);
    }

    /**
     * Get all face texture IDs for a block type.
     *
     * @return map of faceId -> GPU texture ID, or empty map
     */
    public Map<Integer, Integer> getFaceTextures(IBlockType blockType) {
        return faceTextureCache.getOrDefault(blockType.getId(), Map.of());
    }

    public int size() {
        return cache.size();
    }
}
