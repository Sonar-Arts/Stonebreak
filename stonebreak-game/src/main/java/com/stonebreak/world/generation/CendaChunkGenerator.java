package com.stonebreak.world.generation;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.palette.CcoPaletteSection;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeSurfaceConfig;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.heightmap.Density3D;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.lighting.BlockOpacity;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Front-end for the fused native chunk generator ({@code ck_generate_chunk}):
 * assembles the create-time context from the SAME sources the Java path uses
 * ({@link NoiseRouter} channel packing, {@link HeightMapGenerator} splines,
 * {@link Density3D} node params, {@link TerrainGenerationSystem}'s biome block
 * switches, {@link BiomeSurfaceConfig}, {@link BlockOpacity}), then turns
 * kernel output into paletted CCO storage via the bulk section-install path —
 * no 65k per-cell {@code storage.set} calls.
 *
 * <p>Gated by {@link TerrainGenerationSystem}'s constructor on the native
 * noise backend; any kernel failure falls back to the legacy path there.
 */
public final class CendaChunkGenerator {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SECTION_VOLUME = 4096;
    private static final int SECTION_COUNT = WORLD_HEIGHT / 16;

    /** Kernel biome-flag bits (lockstep with cenda/kernels.h). */
    private static final byte FLAG_MAGMA = 1;
    private static final byte FLAG_DRY_BELOW_SEA = 2;

    private static final ThreadLocal<short[]> BLOCKS_SCRATCH =
        ThreadLocal.withInitial(() -> new short[CHUNK_SIZE * CHUNK_SIZE * WORLD_HEIGHT]);
    private static final ThreadLocal<int[]> ORDINALS_SCRATCH =
        ThreadLocal.withInitial(() -> new int[CHUNK_SIZE * CHUNK_SIZE]);

    /** Kernel output for one chunk: bulk-built storage + the sky heightmap. */
    public record Result(CcoBlockStorage storage, int[] heightmap) {}

    private CendaChunkGenerator() {
    }

    /**
     * Creates the fused generation context for a world seed. Returns 0 when the
     * native kernels are unavailable or context creation fails; the caller then
     * stays on the legacy path. Register cleanup via
     * {@code TerrainNoise.destroyChunkGenOnCollect}.
     */
    public static long createContext(long seed) {
        if (!CendaKernels.isAvailable()) {
            return 0L;
        }
        NoiseRouter.ShapeChannelParams ch = NoiseRouter.shapeChannelParams(seed);
        Density3D.NodeParams density = Density3D.nodeParams(seed);

        BiomeType[] biomes = BiomeType.values();
        short[] surfaceIds = new short[biomes.length];
        short[] subsurfaceIds = new short[biomes.length];
        float[] caveIntensity = new float[biomes.length];
        float[] overhangIntensity = new float[biomes.length];
        byte[] flags = new byte[biomes.length];
        List<BlockType> emitted = new ArrayList<>(List.of(
            BlockType.AIR, BlockType.WATER, BlockType.STONE, BlockType.BEDROCK, BlockType.MAGMA));
        for (int i = 0; i < biomes.length; i++) {
            BlockType surface = TerrainGenerationSystem.surfaceBlock(biomes[i]);
            BlockType subsurface = TerrainGenerationSystem.subsurfaceBlock(biomes[i]);
            surfaceIds[i] = (short) surface.getId();
            subsurfaceIds[i] = (short) subsurface.getId();
            emitted.add(surface);
            emitted.add(subsurface);
            BiomeSurfaceConfig.Entry cfg = BiomeSurfaceConfig.get(biomes[i]);
            caveIntensity[i] = cfg.caveIntensity;
            overhangIntensity[i] = cfg.overhangIntensity;
            if (biomes[i] == BiomeType.RED_SAND_DESERT) {
                flags[i] = FLAG_MAGMA | FLAG_DRY_BELOW_SEA;
            }
        }

        int maxId = 0;
        for (BlockType type : emitted) {
            maxId = Math.max(maxId, type.getId());
        }
        byte[] opacity = new byte[maxId + 1];
        for (BlockType type : emitted) {
            opacity[type.getId()] = BlockOpacity.isOpaque(type) ? (byte) 1 : 0;
        }

        int[] blockIds = {
            BlockType.AIR.getId(), BlockType.WATER.getId(), BlockType.STONE.getId(),
            BlockType.BEDROCK.getId(), BlockType.MAGMA.getId(),
        };

        return CendaKernels.chunkGenCreate(seed,
            ch.seeds(), ch.octaves(), ch.gain(), ch.lacunarity(), ch.freq(), ch.xOff(), ch.zOff(),
            HeightMapGenerator.splineXs(), HeightMapGenerator.splineYs(),
            HeightMapGenerator.splineSizes(), HeightMapGenerator.DETAIL_AMPLITUDE,
            density.seed(), density.octaves(),
            density.gain(), density.lacunarity(), density.frequency(),
            blockIds,
            surfaceIds, subsurfaceIds, caveIntensity, overhangIntensity, flags,
            TerrainGenerationSystem.MAGMA_FEATURE.hashCode(),
            TerrainGenerationSystem.MAGMA_CHANCE,
            opacity);
    }

    /**
     * Generates one chunk through the fused kernel. Heights and biomes are the
     * Java-computed column profile ({@code [x*16+z]}). Returns null on any
     * kernel failure (caller falls back to the legacy path).
     */
    public static Result generate(long ctx, int chunkX, int chunkZ,
                                  int[] heights, BiomeType[] biomes) {
        int[] ordinals = ORDINALS_SCRATCH.get();
        for (int i = 0; i < ordinals.length; i++) {
            BiomeType biome = biomes[i];
            if (biome == null) {
                return null;
            }
            ordinals[i] = biome.ordinal();
        }
        short[] blocks = BLOCKS_SCRATCH.get();
        int[] heightmap = new int[CHUNK_SIZE * CHUNK_SIZE];
        long nonAir = CendaKernels.generateChunk(ctx, chunkX, chunkZ, heights, ordinals,
            blocks, heightmap);
        if (nonAir < 0) {
            return null;
        }
        return new Result(buildStorage(blocks), heightmap);
    }

    /**
     * Bulk-installs the kernel's flat block volume into paletted storage.
     * The kernel's {@code y*256 + z*16 + x} layout is exactly 16 concatenated
     * CCO sections, so each 4096-slice installs wholesale: uniform sections
     * stay in the ~32-byte tier (all-air slices keep the createEmpty fill),
     * mixed sections go through {@link CcoPaletteSection#fromPaletteData}.
     */
    private static CcoBlockStorage buildStorage(short[] blocks) {
        CcoPalettedChunkStorage storage = CcoPalettedChunkStorage.createEmpty(
            CHUNK_SIZE, WORLD_HEIGHT, CHUNK_SIZE, BlockType.AIR);
        short airId = (short) BlockType.AIR.getId();
        short[] paletteIds = new short[16];
        for (int section = 0; section < SECTION_COUNT; section++) {
            int base = section * SECTION_VOLUME;
            short first = blocks[base];
            boolean uniform = true;
            for (int i = 1; i < SECTION_VOLUME; i++) {
                if (blocks[base + i] != first) {
                    uniform = false;
                    break;
                }
            }
            if (uniform) {
                if (first != airId) {
                    storage.replaceSection(section,
                        new CcoPaletteSection(CHUNK_SIZE * CHUNK_SIZE, BlockType.getById(first)));
                }
                continue;
            }
            // Terrain emits at most ~13 distinct ids per chunk — linear palette
            // scan beats any map here.
            int paletteSize = 0;
            byte[] indices = new byte[SECTION_VOLUME];
            for (int i = 0; i < SECTION_VOLUME; i++) {
                short id = blocks[base + i];
                int idx = -1;
                for (int p = 0; p < paletteSize; p++) {
                    if (paletteIds[p] == id) {
                        idx = p;
                        break;
                    }
                }
                if (idx < 0) {
                    if (paletteSize == paletteIds.length) {
                        short[] grown = new short[paletteIds.length * 2];
                        System.arraycopy(paletteIds, 0, grown, 0, paletteIds.length);
                        paletteIds = grown;
                    }
                    paletteIds[paletteSize] = id;
                    idx = paletteSize++;
                }
                indices[i] = (byte) idx;
            }
            IBlockType[] palette = new IBlockType[paletteSize];
            for (int p = 0; p < paletteSize; p++) {
                palette[p] = BlockType.getById(paletteIds[p]);
            }
            storage.replaceSection(section,
                CcoPaletteSection.fromPaletteData(CHUNK_SIZE * CHUNK_SIZE, palette, indices));
        }
        return storage;
    }
}
