package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;

import java.util.function.Predicate;

/**
 * Emits pre-computed SBO stamp geometry into a chunk mesh builder.
 *
 * <p>For each SBO block instance, looks up the pre-baked {@link BlockStamp},
 * iterates its 6 faces, applies face culling via an {@link SBOCullingPolicy},
 * and copies the stamp vertex/index data into the {@link MmsMeshBuilder}
 * with a world-space position offset.
 *
 * <p>No per-vertex UV computation happens here — atlas UVs are already
 * baked into the stamp at initialization time by
 * {@link com.openmason.engine.voxel.sbo.SBOMeshProcessor}.
 */
public class SBOStampEmitter {

    private final SBOStampCache cache;
    private final SBOCullingPolicy cullingPolicy;
    private final Predicate<IBlockType> translucencyPolicy;
    private volatile SBOFaceLightSampler lightSampler = SBOFaceLightSampler.FULLY_LIT;
    private volatile SBOInstanceTranslucencyOverride translucencyOverride = null;
    private volatile SBOInstanceFaceCullPolicy instanceFaceCullPolicy = null;

    /**
     * Creates an emitter with the given stamp cache and culling policy.
     * Translucency policy defaults to always-false (no blocks are translucent).
     *
     * @param cache         cache of pre-computed block stamps
     * @param cullingPolicy strategy for deciding which faces to render
     */
    public SBOStampEmitter(SBOStampCache cache, SBOCullingPolicy cullingPolicy) {
        this(cache, cullingPolicy, block -> false);
    }

    /**
     * Creates an emitter with an explicit translucency policy.
     * Blocks matched by the policy are emitted into the transparent render
     * pass with their sampled texture alpha preserved (alpha blending),
     * rather than being treated as cutout (alpha testing).
     *
     * @param cache              cache of pre-computed block stamps
     * @param cullingPolicy      strategy for deciding which faces to render
     * @param translucencyPolicy returns true when a block should use
     *                           translucent alpha-blended rendering
     */
    public SBOStampEmitter(SBOStampCache cache, SBOCullingPolicy cullingPolicy,
                           Predicate<IBlockType> translucencyPolicy) {
        this.cache = cache;
        this.cullingPolicy = cullingPolicy;
        this.translucencyPolicy = translucencyPolicy != null ? translucencyPolicy : block -> false;
    }

    /**
     * Installs a per-face light sampler. Emission will call this for every visible
     * face and bake the result into each vertex's light attribute. Passing null
     * reverts to {@link SBOFaceLightSampler#FULLY_LIT}.
     */
    public void setLightSampler(SBOFaceLightSampler sampler) {
        this.lightSampler = sampler != null ? sampler : SBOFaceLightSampler.FULLY_LIT;
    }

    /**
     * Installs a per-instance translucency override. When set and a block's
     * base classification is translucent, the override is consulted to
     * decide whether this specific instance should be emitted as fully
     * opaque (e.g. ice submerged in water). Pass null to disable.
     */
    public void setInstanceTranslucencyOverride(SBOInstanceTranslucencyOverride override) {
        this.translucencyOverride = override;
    }

    /**
     * Installs a per-instance face cull policy. When set, this policy is
     * consulted after the engine's standard neighbor culling and may
     * additionally suppress individual faces (e.g. cull an ice block's top
     * face when snow rests on it). Pass null to disable.
     */
    public void setInstanceFaceCullPolicy(SBOInstanceFaceCullPolicy policy) {
        this.instanceFaceCullPolicy = policy;
    }

    /**
     * Emit an SBO block's geometry into the mesh builder at full block height.
     *
     * <p>Looks up the block's pre-computed stamp, then for each of the 6 faces:
     * checks the culling policy, and if visible, copies the stamp's position,
     * normal, and UV data with a world-space offset into the builder.
     *
     * @param builder   the chunk mesh builder to emit vertices/indices into
     * @param blockType the SBO block type
     * @param lx        local X coordinate within the chunk (for culling)
     * @param ly        local Y coordinate within the chunk (for culling)
     * @param lz        local Z coordinate within the chunk (for culling)
     * @param worldX    world-space X position for this block
     * @param worldY    world-space Y position for this block
     * @param worldZ    world-space Z position for this block
     * @param chunkData chunk data for face culling neighbor lookups
     */
    public void emitBlock(MmsMeshBuilder builder, IBlockType blockType,
                          int lx, int ly, int lz,
                          float worldX, float worldY, float worldZ,
                          CcoChunkData chunkData) {
        emitBlock(builder, blockType, lx, ly, lz, worldX, worldY, worldZ, chunkData, 1.0f);
    }

    /**
     * Emit an SBO block's geometry into the mesh builder with a specified block height.
     *
     * <p>Supports partial-height blocks (e.g. snow layers). When {@code blockHeight}
     * is less than 1.0, vertex Y positions are scaled so the block bottom stays at
     * the block origin and the top is at {@code blockHeight} fraction of full height.
     *
     * @param builder     the chunk mesh builder to emit vertices/indices into
     * @param blockType   the SBO block type
     * @param lx          local X coordinate within the chunk (for culling)
     * @param ly          local Y coordinate within the chunk (for culling)
     * @param lz          local Z coordinate within the chunk (for culling)
     * @param worldX      world-space X position for this block
     * @param worldY      world-space Y position for this block
     * @param worldZ      world-space Z position for this block
     * @param chunkData   chunk data for face culling neighbor lookups
     * @param blockHeight fraction of full block height (0.0-1.0), 1.0 = full cube
     */
    public void emitBlock(MmsMeshBuilder builder, IBlockType blockType,
                          int lx, int ly, int lz,
                          float worldX, float worldY, float worldZ,
                          CcoChunkData chunkData, float blockHeight) {

        BlockStamp stamp = cache.get(blockType);
        if (stamp == null) return;

        boolean translucent = translucencyPolicy.test(blockType);
        // Translucent blocks use alpha-blended rendering (sampled alpha), not
        // alpha testing — so we must not set the cutout/alpha-test flag for them
        // or the fragment shader will force them into the opaque pass and lose
        // partial transparency.
        float baseAlphaFlag = (!translucent && blockType.isTransparent() && !blockType.isAir()) ? 1.0f : 0.0f;
        float baseTranslucentFlag = translucent ? 1.0f : 0.0f;

        for (int face = 0; face < SBOFaceConventions.FACE_COUNT; face++) {
            if (!cullingPolicy.shouldRenderFace(blockType, lx, ly, lz, face, chunkData)) {
                continue;
            }

            // Per-instance additional cull (e.g. cull ice top face when snow
            // sits on it) — applied after the engine's standard culling.
            if (instanceFaceCullPolicy != null
                    && instanceFaceCullPolicy.shouldCullFace(blockType, lx, ly, lz, face, chunkData)) {
                continue;
            }

            FaceStamp faceStamp = stamp.faces()[face];
            if (faceStamp.vertexCount() == 0) continue;

            // Per-face translucency override: a translucent block can opt
            // individual faces into fully-opaque rendering (e.g. only the
            // ice faces that touch water). Forces both translucent and
            // alpha-test flags off so the fragment shader's regular opaque
            // path is taken for that face.
            float alphaFlag = baseAlphaFlag;
            float translucentFlag = baseTranslucentFlag;
            if (translucent && translucencyOverride != null
                    && translucencyOverride.shouldRenderFaceAsOpaque(blockType, lx, ly, lz, face, chunkData)) {
                alphaFlag = 0.0f;
                translucentFlag = 0.0f;
            }

            emitFaceStamp(builder, faceStamp, worldX, worldY, worldZ, alphaFlag, translucentFlag, blockHeight, face, chunkData);
        }
    }

    /**
     * Emit a single face stamp's geometry into the builder.
     *
     * <p>When {@code blockHeight} is less than 1.0, Y positions are scaled so the
     * block bottom stays anchored and the top compresses to the specified height.
     * The formula maps model-space Y from [-0.5, 0.5] to [-0.5, -0.5 + blockHeight].
     */
    private void emitFaceStamp(MmsMeshBuilder builder, FaceStamp faceStamp,
                               float worldX, float worldY, float worldZ,
                               float alphaFlag, float translucentFlag, float blockHeight,
                               int face, CcoChunkData chunkData) {
        float[] pos = faceStamp.positions();
        float[] nrm = faceStamp.normals();
        float[] uv = faceStamp.atlasUVs();
        int triCount = faceStamp.vertexCount() / 3;
        boolean scaleY = blockHeight < 1.0f;

        for (int tri = 0; tri < triCount; tri++) {
            int baseVertex = builder.getVertexCount();

            for (int v = 0; v < 3; v++) {
                int vi = tri * 3 + v;
                int pOff = vi * 3;
                int tOff = vi * 2;

                float vy = pos[pOff + 1];
                if (scaleY) {
                    // Scale Y: map [-0.5, 0.5] → [-0.5, -0.5 + blockHeight]
                    vy = -0.5f + (vy + 0.5f) * blockHeight;
                }

                float wx = pos[pOff] + worldX;
                float wyAbs = vy + worldY;
                float wz = pos[pOff + 2] + worldZ;
                float vertexLight = lightSampler.sampleVertexLight(face, wx, wyAbs, wz, chunkData);
                builder.addVertex(
                        wx, wyAbs, wz,
                        uv[tOff], uv[tOff + 1],
                        nrm[pOff], nrm[pOff + 1], nrm[pOff + 2],
                        0.0f, alphaFlag, translucentFlag, vertexLight
                );
            }

            builder.addIndex(baseVertex);
            builder.addIndex(baseVertex + 1);
            builder.addIndex(baseVertex + 2);
        }
    }

    /**
     * Check if a block type has SBO geometry in this emitter's cache.
     *
     * @param blockType the block type to check
     * @return true if the cache contains a stamp for this type
     */
    public boolean hasBlock(IBlockType blockType) {
        return cache.has(blockType);
    }

    /**
     * Get the stamp cache backing this emitter.
     */
    public SBOStampCache getCache() {
        return cache;
    }
}
