package com.stonebreak.world.generation.noise;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.NoiseGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;

/**
 * Backend selection and channel construction for world-generation noise.
 *
 * Two backends:
 * <ul>
 *   <li><b>NATIVE</b> — FastNoise2 (SIMD) via the Cenda kernels library.
 *       Positions are raw block coordinates (frequency lives inside the node),
 *       so per-point and batched sampling are bit-identical at the same
 *       coordinate — the FastLOD/chunk parity requirement.</li>
 *   <li><b>JAVA</b> — the original {@link NoiseGenerator} simplex, byte-exact
 *       with historical terrain. Automatic fallback when the native library
 *       is absent.</li>
 * </ul>
 *
 * The two backends produce DIFFERENT terrain for the same seed. The backend is
 * resolved once per process; override with
 * {@code -Dstonebreak.noise.backend=java|native}.
 */
public final class TerrainNoise {

    public enum Backend { NATIVE, JAVA }

    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainNoise.class);
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Backend BACKEND = resolveBackend();

    private TerrainNoise() {
    }

    private static Backend resolveBackend() {
        String requested = System.getProperty("stonebreak.noise.backend", "auto").toLowerCase();
        Backend backend = switch (requested) {
            case "java" -> Backend.JAVA;
            case "native" -> {
                if (!CendaKernels.isAvailable()) {
                    LOGGER.warn("Noise backend 'native' requested but Cenda kernels are unavailable; using Java");
                    yield Backend.JAVA;
                }
                yield Backend.NATIVE;
            }
            default -> CendaKernels.isAvailable() ? Backend.NATIVE : Backend.JAVA;
        };
        if (backend == Backend.NATIVE) {
            LOGGER.info("World-gen noise backend: NATIVE FastNoise2 ({}) — terrain differs from the Java backend",
                CendaKernels.simdLevel());
        } else {
            LOGGER.info("World-gen noise backend: JAVA (classic simplex)");
        }
        return backend;
    }

    public static Backend backend() {
        return BACKEND;
    }

    /** FastNoise2 takes an int seed; collapse the long world seed deterministically. */
    public static int nativeSeed(long seed) {
        return Long.hashCode(seed);
    }

    /**
     * Builds a 2D channel.
     *
     * @param seed           full channel seed (world seed + channel offset)
     * @param octaves        fbm octaves
     * @param persistence    fbm gain
     * @param lacunarity     fbm lacunarity
     * @param scale          noise frequency (noise units per block)
     * @param xOffsetNoise   legacy Java-path offset in NOISE space (added after scaling)
     * @param zOffsetNoise   legacy Java-path offset in NOISE space
     * @param xOffsetBlocks  the same offset expressed in whole BLOCKS for the
     *                       native path (must equal xOffsetNoise / scale exactly)
     * @param zOffsetBlocks  block-space offset for the native path
     */
    public static NoiseChannel2D channel2D(long seed, int octaves, double persistence, double lacunarity,
                                           float scale, float xOffsetNoise, float zOffsetNoise,
                                           int xOffsetBlocks, int zOffsetBlocks) {
        if (BACKEND == Backend.NATIVE) {
            long node = CendaKernels.createSimplexFbm(octaves, (float) lacunarity, (float) persistence, scale);
            if (node != 0L) {
                return new CendaChannel(node, nativeSeed(seed), xOffsetBlocks, zOffsetBlocks);
            }
            LOGGER.warn("Native noise node creation failed; falling back to Java for this channel");
        }
        return new JavaChannel(new NoiseGenerator(seed, octaves, persistence, lacunarity),
            scale, xOffsetNoise, zOffsetNoise);
    }

    /**
     * Native 3D fbm node for {@code Density3D}, or 0 when the Java backend is
     * active (callers then use their {@link NoiseGenerator} path).
     */
    public static long native3DNode(int octaves, double persistence, double lacunarity, float scale) {
        if (BACKEND != Backend.NATIVE) {
            return 0L;
        }
        return CendaKernels.createSimplexFbm(octaves, (float) lacunarity, (float) persistence, scale);
    }

    /** Registers native-node cleanup against the owner's lifetime. */
    public static void destroyOnCollect(Object owner, long node) {
        if (node != 0L) {
            CLEANER.register(owner, () -> CendaKernels.destroy(node));
        }
    }

    /** Original formula, byte-exact with historical terrain. */
    private static final class JavaChannel implements NoiseChannel2D {
        private final NoiseGenerator generator;
        private final float scale;
        private final float xOffsetNoise;
        private final float zOffsetNoise;

        JavaChannel(NoiseGenerator generator, float scale, float xOffsetNoise, float zOffsetNoise) {
            this.generator = generator;
            this.scale = scale;
            this.xOffsetNoise = xOffsetNoise;
            this.zOffsetNoise = zOffsetNoise;
        }

        @Override
        public float sample(int x, int z) {
            return generator.noise(x * scale + xOffsetNoise, z * scale + zOffsetNoise);
        }

        @Override
        public void fill(float[] out, int baseX, int baseZ, int countX, int countZ, int stride) {
            for (int ix = 0; ix < countX; ix++) {
                int x = baseX + ix * stride;
                for (int iz = 0; iz < countZ; iz++) {
                    out[ix * countZ + iz] = sample(x, baseZ + iz * stride);
                }
            }
        }
    }

    /**
     * FastNoise2 channel. The FastNoise2 X axis carries worldZ (its fastest
     * output dimension) and its Y axis carries worldX, so one native call fills
     * the game's z-fastest {@code [x*countZ+z]} layout with no transpose.
     * Offsets are whole blocks, keeping every position an exact integer float —
     * that is what makes {@code sample} (a 1x1 grid) bit-identical to the same
     * cell of a batched {@code fill}.
     */
    private static final class CendaChannel implements NoiseChannel2D {
        private final long node;
        private final int seed;
        private final int xOffsetBlocks;
        private final int zOffsetBlocks;

        CendaChannel(long node, int seed, int xOffsetBlocks, int zOffsetBlocks) {
            this.node = node;
            this.seed = seed;
            this.xOffsetBlocks = xOffsetBlocks;
            this.zOffsetBlocks = zOffsetBlocks;
            TerrainNoise.destroyOnCollect(this, node);
        }

        @Override
        public float sample(int x, int z) {
            float[] out = new float[1];
            fillNative(out, x, z, 1, 1, 1);
            return out[0];
        }

        @Override
        public void fill(float[] out, int baseX, int baseZ, int countX, int countZ, int stride) {
            fillNative(out, baseX, baseZ, countX, countZ, stride);
        }

        private void fillNative(float[] out, int baseX, int baseZ, int countX, int countZ, int stride) {
            boolean ok = CendaKernels.fillGrid2D(node, out,
                (float) (baseZ + zOffsetBlocks), (float) (baseX + xOffsetBlocks),
                countZ, countX,
                (float) stride, (float) stride,
                seed);
            if (!ok) {
                throw new IllegalStateException("Cenda 2D noise fill failed (node=" + node + ")");
            }
        }
    }
}
