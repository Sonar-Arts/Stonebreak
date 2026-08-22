package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoChunkState;
import com.openmason.engine.voxel.cco.state.CcoAtomicStateManager;
import com.openmason.engine.voxel.cco.data.CcoDirtyTracker;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadMeshBuilder;
import com.openmason.engine.voxel.mms.mmsCore.MmsWaterQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilderPool;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsIntegration.MmsBlockGeometryDispatcher;
import com.openmason.engine.voxel.mms.mmsIntegration.MmsSBOBlockProvider;
import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;
import com.openmason.engine.voxel.mms.mmsGeometry.MmsCrossGenerator;
import com.openmason.engine.voxel.mms.mmsGeometry.MmsGreedyMesher;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsWaterGenerator;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampEmitter;
import com.stonebreak.world.operations.WorldConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mighty Mesh System - CCO Integration Adapter.
 *
 * Bridges the MMS mesh generation system with the CCO (Common Chunk Operations) API.
 * Handles state management, dirty tracking, and mesh generation coordination.
 *
 * Design Philosophy:
 * - Adapter Pattern: Connects two independent systems
 * - Single Responsibility: Only handles CCO integration
 * - KISS: Simple delegation and state management
 *
 * @since MMS 1.0
 */
public class MmsCcoAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MmsCcoAdapter.class);

    private final MmsCuboidGenerator cuboidGenerator;
    private final MmsCrossGenerator crossGenerator;
    private final MmsTextureMapper textureMapper;
    private MmsWaterGenerator waterGenerator; // Created when world is set
    private World world; // Not final - can be set after construction
    private com.stonebreak.world.lighting.WorldLightingContext shadowContext; // Built when world is set
    /**
     * The pulled-quad builder for the build in progress on THIS thread (null
     * when not pulling). Thread-local because one adapter serves every mesh
     * worker.
     */
    private static final ThreadLocal<MmsQuadMeshBuilder> ACTIVE_QUAD_BUILDER = new ThreadLocal<>();
    /** Per-thread pulled water-quad builder (WATERQUAD16) and its per-build activation. */
    private static final ThreadLocal<MmsQuadMeshBuilder> WATER_QUAD_BUILDER =
        ThreadLocal.withInitial(() -> new MmsQuadMeshBuilder(512, MmsVertexFormat.WATERQUAD16));
    private static final ThreadLocal<MmsQuadMeshBuilder> ACTIVE_WATER_QUADS = new ThreadLocal<>();
    /** SBO blocks that are exact unit cubes (cube path) — null until an emitter is wired. */
    private volatile SboCubeFaces sboCubes;
    private static final boolean MESH_DEBUG = Boolean.getBoolean("stonebreak.mesh.debug");
    private static final java.util.concurrent.atomic.AtomicInteger debugLogged =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger debugFallbacks =
        new java.util.concurrent.atomic.AtomicInteger();
    private SBOStampEmitter sboStampEmitter; // SBO block stamp emission via SBORendererAPI

    /**
     * Greedy merging of coplanar same-block/same-light cube faces (both the
     * native quad stream and the classic Java path run through it). Default on;
     * {@code -Dstonebreak.mesher.greedy=off} or
     * {@code MmsAPI.setGreedyMeshingEnabled(false)} reverts to per-face quads.
     */
    private volatile boolean greedyMeshingEnabled =
        !"off".equalsIgnoreCase(System.getProperty("stonebreak.mesher.greedy", "on"));

    /**
     * Per-face corner indices at in-plane (width, height) positions (0,0),
     * (1,0) and (0,1) — the three reference corners the affine UV scaling
     * needs. Derived from the cuboid generator's winding table so texture
     * orientation can never drift from geometry.
     */
    private static final int[] UV_C00 = new int[6];
    private static final int[] UV_C10 = new int[6];
    private static final int[] UV_C01 = new int[6];

    static {
        for (int face = 0; face < 6; face++) {
            int uAxis = MmsCuboidGenerator.uAxis(face);
            int vAxis = MmsCuboidGenerator.vAxis(face);
            for (int corner = 0; corner < 4; corner++) {
                int a = (int) MmsCuboidGenerator.cornerOffset(face, corner, uAxis);
                int b = (int) MmsCuboidGenerator.cornerOffset(face, corner, vAxis);
                if (a == 0 && b == 0) {
                    UV_C00[face] = corner;
                } else if (a == 1 && b == 0) {
                    UV_C10[face] = corner;
                } else if (a == 0 && b == 1) {
                    UV_C01[face] = corner;
                }
            }
        }
    }

    /**
     * Growable per-thread collector for the classic (non-native) cube path,
     * building the same 9-float quad records the Cenda kernel emits so both
     * backends share one merge + emission pipeline.
     */
    private static final class QuadSink {
        float[] quads = new float[MmsGreedyMesher.IN_STRIDE * 4096];
        int count;

        void reset() {
            count = 0;
        }

        void add(int lx, int ly, int lz, int face, int id,
                 float l0, float l1, float l2, float l3) {
            int base = count * MmsGreedyMesher.IN_STRIDE;
            if (base + MmsGreedyMesher.IN_STRIDE > quads.length) {
                quads = java.util.Arrays.copyOf(quads, quads.length + (quads.length >> 1));
            }
            quads[base] = lx;
            quads[base + 1] = ly;
            quads[base + 2] = lz;
            quads[base + 3] = face;
            quads[base + 4] = id;
            quads[base + 5] = l0;
            quads[base + 6] = l1;
            quads[base + 7] = l2;
            quads[base + 8] = l3;
            count++;
        }
    }

    private static final ThreadLocal<QuadSink> CLASSIC_SINK = ThreadLocal.withInitial(QuadSink::new);
    /** Per-thread pulled-quad builder (QUAD16): cube faces bypass the per-vertex builder entirely. */
    private static final ThreadLocal<MmsQuadMeshBuilder> QUAD_BUILDER =
        ThreadLocal.withInitial(() -> new MmsQuadMeshBuilder(4096));
    private static final ThreadLocal<float[][]> MERGE_HOLDER = ThreadLocal.withInitial(() -> new float[1][]);
    private static final ThreadLocal<float[][]> SBO_CUBE_SCRATCH =
        ThreadLocal.withInitial(() -> new float[][]{new float[4], new float[4]});

    /**
     * Creates a CCO adapter with the specified services.
     *
     * @param textureMapper Texture coordinate mapper
     * @param world World instance for neighbor lookups (can be null initially, set later via setWorld)
     */
    public MmsCcoAdapter(MmsTextureMapper textureMapper, World world) {
        if (textureMapper == null) {
            throw new IllegalArgumentException("Texture mapper cannot be null");
        }
        // World can be null during initialization - it will be set later when world is created

        this.cuboidGenerator = new MmsCuboidGenerator();
        this.crossGenerator = new MmsCrossGenerator();
        this.textureMapper = textureMapper;
        this.world = world;

        if (world != null) {
            this.waterGenerator = new MmsWaterGenerator(world);
            this.shadowContext = new com.stonebreak.world.lighting.WorldLightingContext(world);
            logger.debug("[MmsCcoAdapter] Water generator initialized with provided world instance");
        }
    }

    /**
     * Sets the world instance after construction.
     * Used when MMS is initialized before World is created.
     *
     * @param world World instance
     */
    public void setWorld(World world) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null when setting");
        }
        this.world = world;
        this.waterGenerator = new MmsWaterGenerator(world);
        this.shadowContext = new com.stonebreak.world.lighting.WorldLightingContext(world);
        logger.debug("[MmsCcoAdapter] World instance set successfully (water generator initialized)");
    }

    /**
     * Sets the SBO stamp emitter for block geometry emission.
     * SBO blocks are emitted via the emitter during the block iteration loop.
     *
     * @param emitter the SBO stamp emitter from SBORendererAPI
     */
    public void setSBOStampEmitter(SBOStampEmitter emitter) {
        this.sboStampEmitter = emitter;
        // Per-vertex shadow sampling — heightmap sky occlusion + classic AO.
        // Deterministic at first mesh build; no seed races, no stale data.
        // Geometry-aware: stamp vertices may sit at fractional cell coordinates
        // (stair risers/treads), and the block's own cell must not shade them.
        emitter.setLightSampler((face, vx, vy, vz, bx, by, bz, data) ->
            com.openmason.engine.voxel.lighting.VertexLightSampler.sampleCombined(
                shadowContext, vx, vy, vz, face, bx, by, bz));
        // SBO blocks that are exact unit cubes take the cube path (kernel, greedy
        // merge, pulled quads) with their stamp's textures; only shaped stamps
        // stay on the per-triangle emitter. The native mesher's per-id class
        // table must know which ids the emitter keeps so it leaves those cells
        // to the Java pass, and which shaped ids can't occlude a cube face.
        SboCubeFaces cubes = new SboCubeFaces(emitter);
        this.sboCubes = cubes;
        CendaMesher.rebuildClassTable(type -> emitter.hasBlock(type) && !cubes.isCube(type),
            cubes::isShaped);
        logger.debug("[MmsCcoAdapter] SBO stamp emitter set ({} stamp types, {} as cubes)",
            emitter.getCache().size(), cubes.cubeCount());
    }

    /** Enables or disables greedy cube-face merging for subsequent mesh builds. */
    public void setGreedyMeshingEnabled(boolean enabled) {
        this.greedyMeshingEnabled = enabled;
    }

    /** Whether greedy cube-face merging is active. */
    public boolean isGreedyMeshingEnabled() {
        return greedyMeshingEnabled;
    }

    /**
     * Sets the SBO block geometry dispatcher (legacy compatibility).
     * Extracts the mesh processor from the provider for stamp-based emission.
     *
     * @param dispatcher the SBO geometry dispatcher
     * @param provider   the SBO block provider
     */
    public void setSBODispatcher(MmsBlockGeometryDispatcher dispatcher, MmsSBOBlockProvider provider) {
        // Legacy path: if no stamp emitter is set, this method is still called
        // The Renderer will set the stamp emitter directly via setSBOStampEmitter
        logger.debug("[MmsCcoAdapter] SBO dispatcher set (legacy path)");
    }

    /**
     * Generates mesh data for a chunk using CCO data.
     *
     * @param chunkData CCO chunk data
     * @param stateManager CCO state manager
     * @param dirtyTracker CCO dirty tracker
     * @return Generated mesh data
     */
    public ChunkMeshResult generateChunkMesh(CcoChunkData chunkData,
                                         CcoAtomicStateManager stateManager,
                                         CcoDirtyTracker dirtyTracker) {

        // Mark as generating
        stateManager.addState(CcoChunkState.MESH_GENERATING);

        // Pooled builder — reused across chunks to avoid the ~640 KB
        // float[]/int[] allocation per build. The build() call below packs its
        // data into the immutable MmsMeshData's interleaved GPU bytes, so the
        // builder is safe to release back to the pool as soon as that returns.
        MmsMeshBuilderPool builderPool = MmsMeshBuilderPool.getInstance();
        MmsMeshBuilder atlasBuilder = builderPool.acquire(
            WorldConfiguration.CHUNK_SIZE * WorldConfiguration.CHUNK_SIZE * 64
        );
        // Water geometry builds into its own mesh, drawn by the dedicated
        // WaterRenderer (own shader) instead of riding the atlas mesh through
        // the world shader's per-fragment pass discards. Water is sparse, so
        // the estimate is deliberately small.
        MmsMeshBuilder waterBuilder = builderPool.acquire(
            WorldConfiguration.CHUNK_SIZE * WorldConfiguration.CHUNK_SIZE * 8
        );

        try {
            // Compact vertex formats store positions relative to the 8×8-chunk REGION
            // origin so every mesh in a region arena shares one origin attribute
            // (ChunkRegionRenderer groups by MmsChunkRegion.REGION_SHIFT). Legacy
            // per-chunk handles carry the same origin in their own VAO, so the value
            // is correct on both draw paths. Ignored by absolute-position formats.
            float regionOriginX = (float) (((chunkData.getChunkX() >> MmsChunkRegion.REGION_SHIFT)
                << MmsChunkRegion.REGION_SHIFT) * WorldConfiguration.CHUNK_SIZE);
            float regionOriginZ = (float) (((chunkData.getChunkZ() >> MmsChunkRegion.REGION_SHIFT)
                << MmsChunkRegion.REGION_SHIFT) * WorldConfiguration.CHUNK_SIZE);
            atlasBuilder.setOrigin(regionOriginX, 0f, regionOriginZ);
            waterBuilder.setOrigin(regionOriginX, 0f, regionOriginZ);
            int chunkX = chunkData.getChunkX();
            int chunkZ = chunkData.getChunkZ();
            // Vertex pulling: greedy cube faces go to the 16-byte quad builder;
            // atlasBuilder then only receives the non-quad "stamp" geometry
            // (SBO stamps, crosses), which becomes ChunkMeshResult.stampMesh.
            MmsQuadMeshBuilder quadBuilder = MmsVertexFormat.active().pulled()
                ? QUAD_BUILDER.get().reset().setOrigin(regionOriginX, 0f, regionOriginZ) : null;
            ACTIVE_QUAD_BUILDER.set(quadBuilder);
            MmsQuadMeshBuilder waterQuads = MmsVertexFormat.active().pulled()
                ? WATER_QUAD_BUILDER.get().reset().setOrigin(regionOriginX, 0f, regionOriginZ) : null;
            ACTIVE_WATER_QUADS.set(waterQuads);

            // Skip the empty air space above the terrain — paletted storage
            // knows the highest non-air Y cheaply (uniform-air sections skip
            // 16 levels at a time). For a sea-level chunk this avoids ~70% of
            // the 65k-cell iteration.
            int maxY = Math.min(chunkData.getHighestNonAirY(), WorldConfiguration.WORLD_HEIGHT - 1);

            // Native fast path: cube culling + lighting in one Cenda kernel
            // call over a flat snapshot; the Java loop below only runs for the
            // snapshot's special cells (SBO/cross/water). Falls back to the
            // classic full loop whenever the kernel or snapshot is unavailable.
            boolean nativeDone = false;
            if (CendaMesher.enabled() && world != null && shadowContext != null) {
                CendaMesher.Snapshot snap = CendaMesher.snapshot(
                    chunkData, world, shadowContext, CendaMesher.classTable(), maxY);
                if (snap != null) {
                    float[][] quadHolder = new float[1][];
                    int quadCount = CendaMesher.mesh(snap,
                        com.openmason.engine.voxel.lighting.VertexLightSampler.isSmoothLightingEnabled(),
                        quadHolder);
                    if (quadCount >= 0) {
                        emitCubeQuadStream(atlasBuilder, quadHolder[0], quadCount, chunkX, chunkZ);
                        emitSpecialCells(atlasBuilder, waterBuilder, snap, chunkData, chunkX, chunkZ);
                        nativeDone = true;
                    }
                }
            }

            // Classic path: cube faces are collected as kernel-format quad
            // records so they run through the same greedy merge + emission as
            // the native stream; special blocks keep their per-cell emitters.
            QuadSink classicSink = null;
            if (!nativeDone) {
                classicSink = CLASSIC_SINK.get();
                classicSink.reset();
            }

            // Iterate through all blocks in the chunk
            for (int lx = 0; !nativeDone && lx < WorldConfiguration.CHUNK_SIZE; lx++) {
                for (int ly = 0; ly <= maxY; ly++) {
                    for (int lz = 0; lz < WorldConfiguration.CHUNK_SIZE; lz++) {
                        BlockType blockType = (BlockType) chunkData.getBlock(lx, ly, lz);

                        // Skip air blocks
                        if (blockType == BlockType.AIR) {
                            continue;
                        }

                        // Skip animated blocks (doors): drawn per-frame by
                        // AnimatedBlockRenderer. They have no SBO stamp, and
                        // without this guard the legacy cube fallback below
                        // would bake a bogus full cube into their cell.
                        if (com.stonebreak.blocks.anim.AnimatedBlockRegistry.isAnimatedType(blockType)) {
                            continue;
                        }

                        // Handle SBO blocks via stamp emitter
                        if (isStampBlock(blockType)) {
                            float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE + 0.5f;
                            float worldY = ly + 0.5f;
                            float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE + 0.5f;

                            // Compute block height for stackable blocks (e.g. snow layers)
                            float blockHeight = 1.0f;
                            if (blockType == BlockType.SNOW && world != null) {
                                int wx = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
                                int wz = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;
                                int layers = world.getSnowLayers(wx, ly, wz);
                                blockHeight = Math.min(1.0f, Math.max(0.125f, layers * 0.125f));
                            }

                            if (emitPerCellCube(blockType, lx, ly, lz, chunkX, chunkZ, chunkData, blockHeight)) {
                                continue;
                            }
                            String stateName = chunkData.getBlockState(lx, ly, lz);
                            sboStampEmitter.emitBlock(atlasBuilder, blockType, lx, ly, lz,
                                    worldX, worldY, worldZ, chunkData, blockHeight, stateName);
                            continue;
                        }

                        // Handle cross-section blocks (flowers)
                        if (isCrossBlock(blockType)) {
                            addCrossBlock(atlasBuilder, blockType, lx, ly, lz, chunkX, chunkZ);
                            continue;
                        }

                        // Handle water blocks with special geometry (own mesh)
                        if (blockType == BlockType.WATER) {
                            addWaterBlockWithCulling(waterBuilder, lx, ly, lz, chunkX, chunkZ, chunkData);
                            continue;
                        }

                        // Handle standard cube blocks with face culling
                        collectCubeQuads(classicSink, blockType, lx, ly, lz, chunkX, chunkZ, chunkData);
                    }
                }
            }

            if (classicSink != null && classicSink.count > 0) {
                emitCubeQuadStream(atlasBuilder, classicSink.quads, classicSink.count, chunkX, chunkZ);
            }

            // Build final meshes (solids in the atlas mesh, water in its own)
            MmsMeshData waterMesh = waterQuads != null ? waterQuads.build() : waterBuilder.build();
            MmsMeshData atlasMesh;
            MmsMeshData stampMesh = null;
            if (quadBuilder != null) {
                atlasMesh = quadBuilder.build();
                stampMesh = atlasBuilder.build();
                if (MESH_DEBUG && debugLogged.incrementAndGet() <= 8) {
                    System.out.println("[MmsCcoAdapter] chunk (" + chunkX + "," + chunkZ + ") pulled quads="
                        + quadBuilder.getQuadCount() + " stampVerts=" + stampMesh.getVertexCount()
                        + " origin=(" + quadBuilder.originX() + "," + quadBuilder.originZ() + ")");
                }
            } else {
                atlasMesh = atlasBuilder.build();
            }
            ChunkMeshResult meshResult = new ChunkMeshResult(atlasMesh, waterMesh, null, stampMesh);

            // Update CCO state
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            stateManager.addState(CcoChunkState.MESH_CPU_READY);

            return meshResult;

        } catch (Exception e) {
            // Handle errors
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            dirtyTracker.markMeshDirtyOnly();
            throw new RuntimeException("Mesh generation failed for chunk (" +
                chunkData.getChunkX() + ", " + chunkData.getChunkZ() + ")", e);
        } finally {
            // Builders' data has been copied out by build(); safe to recycle.
            ACTIVE_QUAD_BUILDER.remove();
            ACTIVE_WATER_QUADS.remove();
            builderPool.release(atlasBuilder);
            builderPool.release(waterBuilder);
        }
    }


    /**
     * Emits a cube-face quad stream (the kernel's 9-float records — the
     * classic path collects the identical format) into the atlas builder,
     * greedily merging coplanar same-block/same-light runs first when enabled.
     * Culling and per-corner lights were already computed upstream.
     */
    private void emitCubeQuadStream(MmsMeshBuilder builder, float[] quads, int quadCount,
                                    int chunkX, int chunkZ) {
        if (greedyMeshingEnabled) {
            float[][] holder = MERGE_HOLDER.get();
            int mergedCount = MmsGreedyMesher.merge(quads, quadCount, holder);
            float[] merged = holder[0];
            for (int q = 0; q < mergedCount; q++) {
                int base = q * MmsGreedyMesher.OUT_STRIDE;
                emitCubeQuad(builder,
                    (int) merged[base], (int) merged[base + 1], (int) merged[base + 2],
                    (int) merged[base + 3], (int) merged[base + 4],
                    (int) merged[base + 5], (int) merged[base + 6],
                    merged[base + 7], merged[base + 8], merged[base + 9], merged[base + 10],
                    chunkX, chunkZ);
            }
        } else {
            for (int q = 0; q < quadCount; q++) {
                int base = q * MmsGreedyMesher.IN_STRIDE;
                emitCubeQuad(builder,
                    (int) quads[base], (int) quads[base + 1], (int) quads[base + 2],
                    (int) quads[base + 3], (int) quads[base + 4], 1, 1,
                    quads[base + 5], quads[base + 6], quads[base + 7], quads[base + 8],
                    chunkX, chunkZ);
            }
        }
    }

    /**
     * Emits one cube-face rectangle spanning {@code w}×{@code h} blocks
     * (1×1 = the classic unit face, bit-identical to the pre-greedy output).
     * Texture coordinates scale affinely from the mapper's base corners, so a
     * merged face tiles its array layer 0..w / 0..h — this is what requires
     * {@code GL_REPEAT} on the block texture array.
     */
    private void emitCubeQuad(MmsMeshBuilder builder, int lx, int ly, int lz,
                              int face, int id, int w, int h,
                              float l0, float l1, float l2, float l3,
                              int chunkX, int chunkZ) {
        BlockType blockType = BlockType.getById(id);
        if (blockType == null) {
            return;
        }
        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;

        float[] vertices = cuboidGenerator.generateScaledFaceVertices(face, worldX, worldY, worldZ, w, h);
        float[] normals = cuboidGenerator.generateFaceNormals(face);
        float[] texCoords;
        float[] alphaFlags;
        float[] layers;
        SboCubeFaces cubes = sboCubes;
        if (cubes != null && cubes.isCube(blockType)) {
            // SBO unit cube: texture frame + layer from its stamp; alpha test for
            // cutout cubes (leaves) as the stamp emitter would set it.
            texCoords = cubes.texCoords(blockType, face);
            float layer = cubes.layer(blockType, face);
            float alpha = blockType.isTransparent() ? 1f : 0f;
            float[][] scratch = SBO_CUBE_SCRATCH.get();
            layers = scratch[0];
            alphaFlags = scratch[1];
            java.util.Arrays.fill(layers, layer);
            java.util.Arrays.fill(alphaFlags, alpha);
        } else {
            texCoords = textureMapper.generateFaceTextureCoordinates(blockType, face);
            alphaFlags = textureMapper.generateAlphaFlags(blockType);
            layers = textureMapper.generateFaceLayers(blockType, face);
        }

        // Affine UV frame from the mapper's unit-square corners: any authored
        // rotation/flip is preserved, unit rectangles reproduce the base
        // coordinates exactly.
        int uAxis = MmsCuboidGenerator.uAxis(face);
        int vAxis = MmsCuboidGenerator.vAxis(face);
        float u00 = texCoords[UV_C00[face] * 2];
        float v00 = texCoords[UV_C00[face] * 2 + 1];
        float duU = texCoords[UV_C10[face] * 2] - u00;
        float duV = texCoords[UV_C10[face] * 2 + 1] - v00;
        float dvU = texCoords[UV_C01[face] * 2] - u00;
        float dvV = texCoords[UV_C01[face] * 2 + 1] - v00;

        MmsQuadMeshBuilder quads = ACTIVE_QUAD_BUILDER.get();
        if (quads != null) {
            int orient = MmsQuadCodec.orientation(u00, v00, duU, duV, dvU, dvV);
            int layer = Math.round(layers[0]);
            int qx = lx + chunkX * WorldConfiguration.CHUNK_SIZE - (int) quads.originX();
            int qz = lz + chunkZ * WorldConfiguration.CHUNK_SIZE - (int) quads.originZ();
            if (orient >= 0 && layer >= 0 && layer <= 65535
                    && qx >= 0 && qx <= 255 && qz >= 0 && qz <= 255 && ly >= 0 && ly <= 511
                    && w >= 1 && w <= 16 && h >= 1 && h <= 16
                    && quads.addQuad(qx, ly, qz, face, w, h, orient, alphaFlags[0] != 0f, false, layer,
                        l0, l1, l2, l3)) {
                return;
            }
            // Not expressible as a pulled quad (exotic UV frame / oversize) —
            // fall through to the per-vertex stamp mesh so nothing disappears.
            if (MESH_DEBUG && debugFallbacks.incrementAndGet() <= 8) {
                System.out.printf("[MmsCcoAdapter] quad fallback: orient=%d layer=%d qx=%d qz=%d ly=%d w=%d h=%d "
                    + "uv00=(%.3f,%.3f) du=(%.3f,%.3f) dv=(%.3f,%.3f)%n", orient, layer, qx, qz, ly, w, h,
                    u00, v00, duU, duV, dvU, dvV);
            }
        }

        float[] lights = {l0, l1, l2, l3};

        builder.beginFace();
        for (int i = 0; i < 4; i++) {
            int vIdx = i * 3;
            float a = MmsCuboidGenerator.cornerOffset(face, i, uAxis) * w;
            float b = MmsCuboidGenerator.cornerOffset(face, i, vAxis) * h;
            builder.addVertex(
                vertices[vIdx], vertices[vIdx + 1], vertices[vIdx + 2],
                u00 + a * duU + b * dvU, v00 + a * duV + b * dvV,
                normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                0.0f, alphaFlags[i], 0.0f, lights[i], layers[i]
            );
        }
        builder.endFace();
    }

    /**
     * Classic-path cube handler: applies the same face culling as before and
     * collects surviving faces as kernel-format quad records (per-corner
     * lights sampled at the face's unit corners) for the shared merge +
     * emission pipeline.
     */
    private void collectCubeQuads(QuadSink sink, BlockType blockType,
                                  int lx, int ly, int lz, int chunkX, int chunkZ,
                                  CcoChunkData chunkData) {
        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;
        int id = blockType.getId();

        for (int face = 0; face < 6; face++) {
            if (!shouldRenderFace(blockType, lx, ly, lz, face, chunkData)) {
                continue;
            }
            float[] corners = new float[4];
            for (int i = 0; i < 4; i++) {
                float vx = worldX + MmsCuboidGenerator.cornerOffset(face, i, 0);
                float vy = worldY + MmsCuboidGenerator.cornerOffset(face, i, 1);
                float vz = worldZ + MmsCuboidGenerator.cornerOffset(face, i, 2);
                corners[i] = sampleVertexLight(vx, vy, vz, face);
            }
            sink.add(lx, ly, lz, face, id, corners[0], corners[1], corners[2], corners[3]);
        }
    }

    /**
     * Java pass over the snapshot's non-cube cells: SBO stamps, cross blocks
     * and water keep their existing per-cell emission paths. Mirrors the
     * branch order of the classic full loop.
     */
    private void emitSpecialCells(MmsMeshBuilder atlasBuilder, MmsMeshBuilder waterBuilder,
                                  CendaMesher.Snapshot snap, CcoChunkData chunkData,
                                  int chunkX, int chunkZ) {
        for (int i = 0; i < snap.specialCount(); i++) {
            int idx = snap.specialCell(i);
            int lx = idx & 15;
            int lz = (idx >> 4) & 15;
            int ly = idx >> 8;
            BlockType blockType = (BlockType) chunkData.getBlock(lx, ly, lz);
            if (blockType == null || blockType == BlockType.AIR) {
                continue;
            }
            if (com.stonebreak.blocks.anim.AnimatedBlockRegistry.isAnimatedType(blockType)) {
                continue;
            }
            if (isStampBlock(blockType)) {
                float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE + 0.5f;
                float worldY = ly + 0.5f;
                float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE + 0.5f;
                float blockHeight = 1.0f;
                if (blockType == BlockType.SNOW && world != null) {
                    int wx = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
                    int wz = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;
                    int layers = world.getSnowLayers(wx, ly, wz);
                    blockHeight = Math.min(1.0f, Math.max(0.125f, layers * 0.125f));
                }
                if (emitPerCellCube(blockType, lx, ly, lz, chunkX, chunkZ, chunkData, blockHeight)) {
                    continue;
                }
                String stateName = chunkData.getBlockState(lx, ly, lz);
                sboStampEmitter.emitBlock(atlasBuilder, blockType, lx, ly, lz,
                        worldX, worldY, worldZ, chunkData, blockHeight, stateName);
                continue;
            }
            if (isCrossBlock(blockType)) {
                addCrossBlock(atlasBuilder, blockType, lx, ly, lz, chunkX, chunkZ);
                continue;
            }
            if (blockType == BlockType.WATER) {
                addWaterBlockWithCulling(waterBuilder, lx, ly, lz, chunkX, chunkZ, chunkData);
                continue;
            }
            // A cube-class id shouldn't appear here; emit via the classic path
            // as a safety net so nothing silently disappears.
            addCubeBlockWithCulling(atlasBuilder, blockType, lx, ly, lz, chunkX, chunkZ, chunkData);
        }
    }

    /**
     * Adds a cross-section block to the mesh builder.
     */
    private void addCrossBlock(MmsMeshBuilder builder, BlockType blockType,
                              int lx, int ly, int lz, int chunkX, int chunkZ) {

        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;

        // Generate geometry (8 vertices = 2 planes × 4 vertices, double-sided via index winding)
        float[] vertices = crossGenerator.generateCrossVertices(worldX, worldY, worldZ);
        float[] normals = crossGenerator.generateCrossNormals();

        // Generate texture coordinates (8 vertices)
        float[] texCoords = textureMapper.generateCrossTextureCoordinates(blockType);

        // Generate texture-array layer indices (8 vertices)
        float[] crossLayers = textureMapper.generateCrossLayers(blockType);

        // Generate alpha flags (cross blocks always use alpha testing)
        float[] alphaFlags = new float[MmsBufferLayout.VERTICES_PER_CROSS];
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            alphaFlags[i] = 1.0f; // Cross blocks need alpha testing
        }

        // Add vertices to builder (8 vertices)
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            int vIdx = i * 3;
            int tIdx = i * 2;

            builder.addVertex(
                vertices[vIdx], vertices[vIdx + 1], vertices[vIdx + 2],
                texCoords[tIdx], texCoords[tIdx + 1],
                normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                0.0f, alphaFlags[i], 0.0f, 1.0f, crossLayers[i] // No water flags needed
            );
        }

        // Add indices for cross (2 planes with double-sided rendering via index winding = 24 indices)
        int baseVertex = builder.getVertexCount() - MmsBufferLayout.VERTICES_PER_CROSS;
        int[] crossIndices = crossGenerator.generateCrossIndices(baseVertex);

        // Add all 24 indices to the builder
        for (int index : crossIndices) {
            builder.addIndex(index);
        }
    }

    /**
     * Face-local texture coordinates for water faces. The dedicated water
     * shader generates its surface pattern procedurally and derives its flow
     * coordinates in WORLD space (seamless across neighboring columns), so
     * these UVs are currently unread by the fragment stage — they stay in the
     * layout as the face-local parameterization (U across, V downward, v=0 at
     * the top edge) for debugging/future use. Vertex winding in
     * {@link MmsWaterGenerator#generateFaceVertices}: top face and all side
     * faces share the (v0,v1,v2,v3) = (·,·,top,top) order below; the bottom
     * face winds the other way.
     */
    private static final float[] WATER_UVS_TOP_AND_SIDES = {0, 1, 1, 1, 1, 0, 0, 0};
    private static final float[] WATER_UVS_BOTTOM = {0, 0, 1, 0, 1, 1, 0, 1};

    /**
     * Adds a water block to the WATER mesh builder with face culling and
     * variable height geometry. Water-mesh vertex semantics (consumed by the
     * dedicated water shader, not the world shader): tex = face-local UV,
     * flags.x = surface-height fraction, flags.y = falling flag,
     * flags.z = source flag, flags.w = light, layer = unused (0).
     */
    private void addWaterBlockWithCulling(MmsMeshBuilder builder,
                                         int lx, int ly, int lz, int chunkX, int chunkZ,
                                         CcoChunkData chunkData) {
        if (waterGenerator == null) {
            // Fallback to standard cube if water generator not initialized
            addCubeBlockWithCulling(builder, BlockType.WATER, lx, ly, lz, chunkX, chunkZ, chunkData);
            return;
        }

        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;
        int blockX = (int) Math.floor(worldX);
        int blockY = (int) Math.floor(worldY);
        int blockZ = (int) Math.floor(worldZ);

        // Per-cell flow state from the chunk-owned water layer (the sim SOT).
        int flowValue = world != null ? world.getWaterLevelAt(blockX, blockY, blockZ)
                                      : com.stonebreak.world.chunk.ChunkWaterLayer.SOURCE;
        float fallingFlag = flowValue == com.stonebreak.world.chunk.ChunkWaterLayer.FALLING ? 1.0f : 0.0f;
        float sourceFlag = flowValue == com.stonebreak.world.chunk.ChunkWaterLayer.SOURCE ? 1.0f : 0.0f;

        // Check each face for culling (water has special culling rules)
        for (int face = 0; face < 6; face++) {
            if (!shouldRenderWaterFace(lx, ly, lz, face, chunkData)) {
                continue; // Face is culled
            }

            // Generate water-specific geometry with variable heights
            float[] vertices = waterGenerator.generateFaceVertices(face, worldX, worldY, worldZ);
            float[] normals = waterGenerator.generateFaceNormals(face);
            float[] texCoords = face == 1 ? WATER_UVS_BOTTOM : WATER_UVS_TOP_AND_SIDES;

            // generateWaterFlags ignores its blockHeight parameter; one call is sufficient.
            // Returns a per-thread scratch array — read it before the next call.
            float[] waterFlags = waterGenerator.generateWaterFlags(face, blockX, blockY, blockZ, 0.0f);

            MmsQuadMeshBuilder waterQuads = ACTIVE_WATER_QUADS.get();
            if (waterQuads != null) {
                // Pulled water: one 16-byte record per face. The generator's vertex
                // order is the cuboid corner order, so corner i = vertex i.
                int qx = blockX - (int) waterQuads.originX();
                int qz = blockZ - (int) waterQuads.originZ();
                if (qx >= 0 && qx <= 255 && qz >= 0 && qz <= 255 && blockY >= 0 && blockY <= 511
                        && waterQuads.addWords(
                            MmsWaterQuadCodec.word0(qx, blockY, qz, face, fallingFlag > 0.5f, sourceFlag > 0.5f),
                            MmsWaterQuadCodec.word1(blockY, vertices[1], vertices[4], vertices[7], vertices[10]),
                            MmsWaterQuadCodec.word2(waterFlags[0], waterFlags[1], waterFlags[2], waterFlags[3]),
                            MmsWaterQuadCodec.word3(1, 1))) {
                    continue;
                }
                // Out of range / full: fall back to the per-vertex water mesh below.
            }

            // Add face to builder
            builder.beginFace();
            for (int i = 0; i < 4; i++) {
                int vIdx = i * 3;
                int tIdx = i * 2;

                builder.addVertex(
                    vertices[vIdx], vertices[vIdx + 1], vertices[vIdx + 2],
                    texCoords[tIdx], texCoords[tIdx + 1],
                    normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                    waterFlags[i], fallingFlag, sourceFlag, 1.0f, 0.0f
                );
            }
            builder.endFace();
        }
    }

    /**
     * Adds a cube block with face culling to the mesh builder.
     */
    private void addCubeBlockWithCulling(MmsMeshBuilder builder, BlockType blockType,
                                        int lx, int ly, int lz, int chunkX, int chunkZ,
                                        CcoChunkData chunkData) {

        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;

        // Check each face for culling
        for (int face = 0; face < 6; face++) {
            if (!shouldRenderFace(blockType, lx, ly, lz, face, chunkData)) {
                continue; // Face is culled
            }

            // Generate face geometry (standard cuboid)
            float[] vertices = cuboidGenerator.generateFaceVertices(face, worldX, worldY, worldZ);
            float[] normals = cuboidGenerator.generateFaceNormals(face);

            // Generate texture coordinates
            float[] texCoords = (sboCubes != null && sboCubes.isCube(blockType))
                ? sboCubes.texCoords(blockType, face)
                : textureMapper.generateFaceTextureCoordinates(blockType, face);

            // Generate alpha flags
            float[] alphaFlags = textureMapper.generateAlphaFlags(blockType);

            // Generate texture-array layer indices
            float[] layers = textureMapper.generateFaceLayers(blockType, face);
            if (sboCubes != null && sboCubes.isCube(blockType)) {
                layers = layers.clone();
                java.util.Arrays.fill(layers, sboCubes.layer(blockType, face));
            }

            // Per-vertex smooth lighting — each vertex averages the 4 air-side
            // cells it touches. Gives gradient shadow transitions across faces.
            builder.beginFace();
            for (int i = 0; i < 4; i++) {
                int vIdx = i * 3;
                int tIdx = i * 2;
                float vx = vertices[vIdx];
                float vy = vertices[vIdx + 1];
                float vz = vertices[vIdx + 2];
                float vertexLight = sampleVertexLight(vx, vy, vz, face);

                builder.addVertex(
                    vx, vy, vz,
                    texCoords[tIdx], texCoords[tIdx + 1],
                    normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                    0.0f, alphaFlags[i], 0.0f, vertexLight, layers[i]
                );
            }
            builder.endFace();
        }
    }

    /**
     * Per-vertex shadow sample: sky occlusion (heightmap) × classic AO.
     */
    private float sampleVertexLight(float vx, float vy, float vz, int face) {
        return com.openmason.engine.voxel.lighting.VertexLightSampler.sampleCombined(shadowContext, vx, vy, vz, face);
    }

    /**
     * Determines if a water face should be rendered based on adjacent blocks.
     * Water has special culling rules to prevent water-to-water culling.
     */
    private boolean shouldRenderWaterFace(int lx, int ly, int lz, int face, CcoChunkData chunkData) {
        // Get adjacent block coordinates
        int adjX = lx + getFaceOffsetX(face);
        int adjY = ly + getFaceOffsetY(face);
        int adjZ = lz + getFaceOffsetZ(face);

        // For horizontal side faces (N/S/E/W) crossing into an unloaded neighbor chunk,
        // assume the neighbor is water to prevent visible chunk-border water seams.
        // The neighbor chunk will trigger a remesh of this chunk when it loads.
        if (face >= 2 && face <= 5 && adjY >= 0 && adjY < WorldConfiguration.WORLD_HEIGHT) {
            boolean outOfChunk = adjX < 0 || adjX >= WorldConfiguration.CHUNK_SIZE
                              || adjZ < 0 || adjZ >= WorldConfiguration.CHUNK_SIZE;
            if (outOfChunk && world != null) {
                int worldX = adjX + chunkData.getChunkX() * WorldConfiguration.CHUNK_SIZE;
                int worldZ = adjZ + chunkData.getChunkZ() * WorldConfiguration.CHUNK_SIZE;
                int ncx = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
                int ncz = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);
                if (!world.hasChunkAt(ncx, ncz)) {
                    return false;
                }
            }
        }

        // Get adjacent block (handles chunk boundaries via world)
        BlockType adjacentBlock = getAdjacentBlock(adjX, adjY, adjZ, chunkData);

        // Water face culling rules (from old FaceRenderingService):
        if (adjacentBlock == BlockType.WATER) {
            // Never render faces between water blocks - they blend seamlessly
            return false;
        } else if (adjacentBlock == BlockType.ICE) {
            // Treat ice as opaque from water's POV: submerged ice forces an
            // opaque render path (see SBOStampEmitter override), so the water
            // face touching it would only z-fight with the ice surface.
            return false;
        } else {
            // Water vs non-water: render top face when adjacent to opaque blocks,
            // other faces when transparent (but not water)
            if (face == 0) { // Top face
                return !adjacentBlock.isTransparent() || adjacentBlock == BlockType.AIR;
            }
            // Side faces vs air/transparent always render. There is deliberately
            // NO submerged-continuous heuristic here: any per-cell rule that
            // culls a side face because water sits below the neighbor punches
            // holes at waterfall junctions, pool rims and source walls (the
            // cases are not distinguishable at cull time). Instead the geometry
            // itself is honest — side faces only ever span their cell's actual
            // water extent, and MmsWaterGenerator seals their bottom edge
            // against the neighbor column's water surface (wave-proof overlap)
            // so junctions can't open slits. Surface continuity across cells is
            // guaranteed by the sewn corner heights (getSewnCornerHeights).
            return adjacentBlock.isTransparent() && adjacentBlock != BlockType.WATER;
        }
    }

    /**
     * Determines if a face should be rendered based on adjacent blocks.
     */
    private boolean shouldRenderFace(BlockType blockType, int lx, int ly, int lz,
                                     int face, CcoChunkData chunkData) {

        // Get adjacent block coordinates
        int adjX = lx + getFaceOffsetX(face);
        int adjY = ly + getFaceOffsetY(face);
        int adjZ = lz + getFaceOffsetZ(face);

        // Get adjacent block (handles chunk boundaries via world)
        BlockType adjacentBlock = getAdjacentBlock(adjX, adjY, adjZ, chunkData);

        // Face culling logic
        return shouldRenderAgainst(blockType, adjacentBlock);
    }

    /**
     * Gets adjacent block, handling chunk boundaries by querying world.
     *
     * @param adjX Adjacent block X coordinate (local chunk space)
     * @param adjY Adjacent block Y coordinate (world space)
     * @param adjZ Adjacent block Z coordinate (local chunk space)
     * @param chunkData Current chunk data
     * @return Block type at adjacent position
     */
    private BlockType getAdjacentBlock(int adjX, int adjY, int adjZ, CcoChunkData chunkData) {
        // Check if within current chunk bounds
        if (adjX >= 0 && adjX < WorldConfiguration.CHUNK_SIZE &&
            adjY >= 0 && adjY < WorldConfiguration.WORLD_HEIGHT &&
            adjZ >= 0 && adjZ < WorldConfiguration.CHUNK_SIZE) {
            return (BlockType) chunkData.getBlock(adjX, adjY, adjZ);
        }

        // Out of bounds - query neighboring chunk via world
        // CRITICAL FIX: Only query if neighbor chunk exists to prevent recursive chunk generation
        if (world != null && adjY >= 0 && adjY < WorldConfiguration.WORLD_HEIGHT) {
            // Convert to world coordinates
            int worldX = adjX + chunkData.getChunkX() * WorldConfiguration.CHUNK_SIZE;
            int worldZ = adjZ + chunkData.getChunkZ() * WorldConfiguration.CHUNK_SIZE;

            // Calculate neighbor chunk coordinates
            int neighborChunkX = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
            int neighborChunkZ = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);

            // Only query if neighbor chunk already exists (don't trigger generation)
            if (world.hasChunkAt(neighborChunkX, neighborChunkZ)) {
                BlockType adjacentBlock = world.getBlockAt(worldX, adjY, worldZ);
                return adjacentBlock != null ? adjacentBlock : BlockType.AIR;
            }
        }

        // Out of world bounds or no world reference - assume air
        return BlockType.AIR;
    }

    /**
     * Determines if a face should render against an adjacent block.
     *
     * Culling Rules:
     * 1. Always render against AIR
     * 2. Transparent blocks render against different block types (e.g., water doesn't cull against water)
     * 3. Opaque blocks cull against other opaque blocks
     * 4. Opaque blocks render against transparent blocks
     *
     * @param blockType The block being rendered
     * @param adjacentBlock The neighboring block
     * @return true if face should be rendered
     */
    private boolean shouldRenderAgainst(BlockType blockType, BlockType adjacentBlock) {
        // Always render if adjacent is air
        if (adjacentBlock == BlockType.AIR) {
            return true;
        }

        // Transparent cubes (leaves with leaf transparency on) render against
        // different TRANSPARENT neighbours only. Against an opaque neighbour the
        // face is fully covered, and since the opaque block emits its own face
        // toward the transparent cell, drawing the hidden one just z-fights it.
        // Same-type pairs cull too (leaf|leaf interiors). Only cube blocks reach
        // this method — water and cross geometry have their own paths. Mirrors
        // the Cenda mesher's renderFace — keep in lockstep.
        if (isTransparent(blockType)) {
            return blockType != adjacentBlock && isTransparent(adjacentBlock);
        }

        // Opaque blocks don't render against other opaque blocks (standard culling)
        // But DO render against transparent blocks (e.g., grass underwater should be visible)
        return isTransparent(adjacentBlock);
    }

    /**
     * Checks if a block type is transparent and requires special culling.
     * Delegates to {@link BlockType#isTransparent()} to stay consistent
     * with the SBO culling path and avoid missing new transparent types.
     *
     * @param blockType Block type to check
     * @return true if block is transparent
     */
    private boolean isTransparent(BlockType blockType) {
        return blockType.isTransparent();
    }

    /**
     * Checks if a block type is a cross-section block.
     */
    /**
     * Unit-cube SBO blocks that need per-cell decisions (snow layers: height;
     * translucent blocks: per-face opacity overrides) as individual pulled
     * quads — the stamp emitter's own culling, translucency and light rules,
     * 16 bytes per face instead of six 20-byte vertices. Returns false when
     * not applicable (no pulling, not such a block, quad cap hit before the
     * first face) so the caller falls back to the stamp emitter.
     */
    private boolean emitPerCellCube(BlockType blockType, int lx, int ly, int lz, int chunkX, int chunkZ,
                                    CcoChunkData chunkData, float blockHeight) {
        MmsQuadMeshBuilder quads = ACTIVE_QUAD_BUILDER.get();
        SboCubeFaces cubes = sboCubes;
        SBOStampEmitter emitter = sboStampEmitter;
        if (quads == null || cubes == null || emitter == null || !cubes.isPerCellCube(blockType)
                || quads.isFull()) {
            return false;
        }
        int qx = lx + chunkX * WorldConfiguration.CHUNK_SIZE - (int) quads.originX();
        int qz = lz + chunkZ * WorldConfiguration.CHUNK_SIZE - (int) quads.originZ();
        if (qx < 0 || qx > 255 || qz < 0 || qz > 255 || ly < 0 || ly > 511) {
            return false;
        }
        boolean translucent = emitter.isTranslucent(blockType);
        boolean baseAlpha = !translucent && blockType.isTransparent();
        int heightEighths = Math.clamp(Math.round(blockHeight * 8f), 1, 8);
        float height = heightEighths / 8f;
        float wx0 = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float wz0 = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;
        for (int face = 0; face < 6; face++) {
            if (!emitter.isFaceVisible(blockType, lx, ly, lz, face, chunkData)) {
                continue;
            }
            boolean forcedOpaque = translucent && emitter.isFaceForcedOpaque(blockType, lx, ly, lz, face, chunkData);
            boolean alpha = forcedOpaque ? false : baseAlpha;
            boolean transl = forcedOpaque ? false : translucent;
            float[] tc = cubes.texCoords(blockType, face);
            float u00 = tc[UV_C00[face] * 2], v00 = tc[UV_C00[face] * 2 + 1];
            int orient = MmsQuadCodec.orientation(u00, v00,
                tc[UV_C10[face] * 2] - u00, tc[UV_C10[face] * 2 + 1] - v00,
                tc[UV_C01[face] * 2] - u00, tc[UV_C01[face] * 2 + 1] - v00);
            if (orient < 0) {
                return false; // exotic frame: whole block goes to the stamp emitter
            }
            int layer = Math.round(cubes.layer(blockType, face));
            float[] l = SBO_CUBE_SCRATCH.get()[0];
            for (int c = 0; c < 4; c++) {
                float cx = MmsCuboidGenerator.cornerOffset(face, c, 0);
                float cy = MmsCuboidGenerator.cornerOffset(face, c, 1) * height;
                float cz = MmsCuboidGenerator.cornerOffset(face, c, 2);
                l[c] = emitter.sampleLight(face, wx0 + cx, ly + cy, wz0 + cz, (int) wx0, ly, (int) wz0, chunkData);
            }
            if (!quads.addQuad(qx, ly, qz, face, 1, 1, orient, alpha, transl, layer,
                    l[0], l[1], l[2], l[3], heightEighths)) {
                return face > 0; // cap mid-block: keep what was emitted (never in practice)
            }
        }
        return true;
    }

    /** True when the block must be emitted by the SBO stamp emitter (shaped SBO geometry). */
    private boolean isStampBlock(BlockType blockType) {
        if (sboStampEmitter == null || !sboStampEmitter.hasBlock(blockType)) {
            return false;
        }
        SboCubeFaces cubes = sboCubes;
        return cubes == null || !cubes.isCube(blockType);
    }

    private boolean isCrossBlock(BlockType blockType) {
        return blockType == BlockType.ROSE || blockType == BlockType.DANDELION || blockType == BlockType.WILDGRASS;
    }

    // Face offset helpers
    private int getFaceOffsetX(int face) {
        return switch (face) {
            case 4 -> 1;  // East
            case 5 -> -1; // West
            default -> 0;
        };
    }

    private int getFaceOffsetY(int face) {
        return switch (face) {
            case 0 -> 1;  // Top
            case 1 -> -1; // Bottom
            default -> 0;
        };
    }

    private int getFaceOffsetZ(int face) {
        return switch (face) {
            case 2 -> -1; // North
            case 3 -> 1;  // South
            default -> 0;
        };
    }
}
