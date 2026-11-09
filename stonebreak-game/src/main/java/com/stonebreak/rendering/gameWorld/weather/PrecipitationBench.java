package com.stonebreak.rendering.gameWorld.weather;

import java.util.Arrays;
import java.util.Locale;

import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33.GL_TIMESTAMP;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;
import static org.lwjgl.opengl.GL33.glQueryCounter;

/**
 * Dev A/B timer for rain (lanes vs FX particles): {@code -Dstonebreak.rainbench=<warmup s>:<seconds>[:quit]}.
 * Times the world frame and each rain-related section on the CPU ({@code System.nanoTime}) and the GPU (GL
 * timestamp queries, read back {@value #SLOTS} frames later so nothing stalls), then prints one summary line
 * ({@code [rainbench] ...}) and optionally closes the window. Inert (null instance) without the property.
 */
public final class PrecipitationBench {

    public enum Section {
        /** Particle rain: cloud/ground sampling and spawning (CPU only). */
        SPAWN(false),
        /** The FX runtime step (every FX, CPU simulation; CPU only). */
        FX_UPDATE(false),
        /** FX prepare (cull/sort/upload + GPU simulation) and the draw behind the water. */
        FX_BEHIND(true),
        /** FX drawn in front of the water. */
        FX_FRONT(true),
        /** The lane renderer (both rings, or the far ring alone). */
        LANES(true);

        final boolean gpu;

        Section(boolean gpu) {
            this.gpu = gpu;
        }
    }

    private static final int SLOTS = 4;
    private static final int SECTIONS = Section.values().length;
    /** Query pairs per slot: one per section plus the whole world frame (last). */
    private static final int PAIRS = SECTIONS + 1;

    private static PrecipitationBench instance;
    private static boolean parsed;

    /** The bench, or null when {@code stonebreak.rainbench} is not set. First call needs a GL context. */
    public static PrecipitationBench get() {
        if (!parsed) {
            parsed = true;
            String spec = System.getProperty("stonebreak.rainbench");
            if (spec != null && !spec.isBlank()) {
                String[] p = spec.split(":");
                instance = new PrecipitationBench(Float.parseFloat(p[0]), Float.parseFloat(p.length > 1 ? p[1] : "10"),
                        p.length > 2 && "quit".equalsIgnoreCase(p[2]));
            }
        }
        return instance;
    }

    private final float warmup, seconds;
    private final boolean quit;
    private final int[][] queries = new int[SLOTS][PAIRS * 2];
    private final boolean[][] issued = new boolean[SLOTS][PAIRS];
    private final long[] cpuStart = new long[SECTIONS];
    private final long[] cpuFrame = new long[SECTIONS];
    private int slot;
    private boolean inFrame;
    private long startNanos = -1, lastFrameNanos = -1;
    private boolean done;

    // ---- accumulators (measurement window only) ----
    private int frames, gpuFrames;
    private final double[] cpuSum = new double[SECTIONS];
    private final double[] gpuSum = new double[PAIRS];
    private final int[] gpuCount = new int[PAIRS];
    private double wallSum;
    private double[] wall = new double[4096];
    private double spawnedSum, aliveSum;
    private String mode = "?";

    private PrecipitationBench(float warmup, float seconds, boolean quit) {
        this.warmup = warmup;
        this.seconds = seconds;
        this.quit = quit;
        for (int s = 0; s < SLOTS; s++) {
            for (int q = 0; q < PAIRS * 2; q++) {
                queries[s][q] = glGenQueries();
            }
        }
    }

    private boolean measuring() {
        if (done || startNanos < 0) return false;
        double t = (System.nanoTime() - startNanos) / 1e9;
        return t >= warmup;
    }

    /** Start of the world frame. */
    public void beginFrame() {
        long now = System.nanoTime();
        if (startNanos < 0) startNanos = now;
        slot = (slot + 1) % SLOTS;
        harvest(slot);
        if (measuring() && lastFrameNanos >= 0) {
            double ms = (now - lastFrameNanos) / 1e6;
            if (frames == wall.length) wall = Arrays.copyOf(wall, wall.length * 2);
            wall[frames] = ms;
            wallSum += ms;
            frames++;
            for (int i = 0; i < SECTIONS; i++) cpuSum[i] += cpuFrame[i] / 1e6;
        }
        lastFrameNanos = now;
        Arrays.fill(cpuFrame, 0L);
        Arrays.fill(issued[slot], false);
        glQueryCounter(queries[slot][SECTIONS * 2], GL_TIMESTAMP);
        inFrame = true;
    }

    public void begin(Section s) {
        if (!inFrame) return;
        cpuStart[s.ordinal()] = System.nanoTime();
        if (s.gpu) glQueryCounter(queries[slot][s.ordinal() * 2], GL_TIMESTAMP);
    }

    public void end(Section s) {
        if (!inFrame) return;
        cpuFrame[s.ordinal()] += System.nanoTime() - cpuStart[s.ordinal()];
        if (s.gpu) {
            glQueryCounter(queries[slot][s.ordinal() * 2 + 1], GL_TIMESTAMP);
            issued[slot][s.ordinal()] = true;
        }
    }

    /** End of the world frame, with what the rain drew this frame. */
    public void endFrame(String mode, int spawned, int alive) {
        if (!inFrame) return;
        inFrame = false;
        glQueryCounter(queries[slot][SECTIONS * 2 + 1], GL_TIMESTAMP);
        issued[slot][SECTIONS] = true;
        this.mode = mode;
        if (measuring()) {
            spawnedSum += spawned;
            aliveSum += alive;
        }
        if (!done && startNanos >= 0 && (System.nanoTime() - startNanos) / 1e9 >= warmup + seconds) {
            done = true;
            report();
            if (quit) {
                long window = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                if (window != 0L) org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose(window, true);
            }
        }
    }

    /** Reads slot {@code s}'s results from {@value #SLOTS} frames ago (they are ready by now; skipped if not). */
    private void harvest(int s) {
        boolean any = false;
        for (int p = 0; p < PAIRS; p++) {
            if (!issued[s][p]) continue;
            int end = queries[s][p * 2 + 1];
            if (glGetQueryObjecti(end, GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long t1 = glGetQueryObjecti64(end, GL_QUERY_RESULT);
            long t0 = glGetQueryObjecti64(queries[s][p * 2], GL_QUERY_RESULT);
            if (measuring()) {
                gpuSum[p] += (t1 - t0) / 1e6;
                gpuCount[p]++;
                any |= p == SECTIONS;
            }
        }
        if (any) gpuFrames++;
    }

    private void report() {
        double[] sorted = Arrays.copyOf(wall, frames);
        Arrays.sort(sorted);
        double p95 = frames == 0 ? 0 : sorted[Math.min(frames - 1, (int) (frames * 0.95))];
        double p99 = frames == 0 ? 0 : sorted[Math.min(frames - 1, (int) (frames * 0.99))];
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "[rainbench] mode=%s frames=%d wall=%.3fms p95=%.3f p99=%.3f fps=%.1f",
                mode, frames, frames == 0 ? 0 : wallSum / frames, p95, p99,
                frames == 0 ? 0 : 1000.0 * frames / wallSum));
        b.append(String.format(Locale.ROOT, " worldGpu=%.3fms", avgGpu(SECTIONS)));
        for (Section sec : Section.values()) {
            b.append(String.format(Locale.ROOT, " %s[cpu=%.3f", sec.name().toLowerCase(Locale.ROOT),
                    frames == 0 ? 0 : cpuSum[sec.ordinal()] / frames));
            if (sec.gpu) {
                // Per frame (sections a frame skipped count as 0).
                b.append(String.format(Locale.ROOT, " gpu=%.3f", gpuFrames == 0 ? 0 : gpuSum[sec.ordinal()] / gpuFrames));
            }
            b.append(']');
        }
        b.append(String.format(Locale.ROOT, " spawned/frame=%.1f alive=%.0f",
                frames == 0 ? 0 : spawnedSum / frames, frames == 0 ? 0 : aliveSum / frames));
        System.out.println(b);
    }

    private double avgGpu(int p) {
        return gpuCount[p] == 0 ? 0 : gpuSum[p] / gpuCount[p];
    }

    public void cleanup() {
        for (int[] slotQueries : queries) glDeleteQueries(slotQueries);
    }
}
