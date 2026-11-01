package com.openmason.samples.coreconsumer;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.openmason.engine.wayfind.AStar;
import com.openmason.engine.wayfind.SearchDomain;
import com.openmason.engine.wayfind.SearchResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Arrays;

/**
 * Uses a slice of {@code openmason.engine.core} the way an unrelated game would: Wayfind routes around a
 * wall on a small grid, the wire codec round-trips a payload, and the native noise kernels sample a grid
 * when the Cenda library is built. Nothing here needs the UI runtime, the voxel stack or Skija (#387).
 */
public final class CoreConsumer {

    /** What one run produced, so a test can check it without parsing output. */
    public record Report(int pathLength, float pathCost, boolean payloadRoundTripped, String noise) {}

    private CoreConsumer() {
    }

    public static void main(String[] args) {
        Report report = run();
        System.out.println("path " + report.pathLength() + " nodes, cost " + report.pathCost());
        System.out.println("wire payload round-trip: " + report.payloadRoundTripped());
        System.out.println("noise: " + report.noise());
    }

    public static Report run() {
        SearchResult path = new AStar().search(new WalledGrid(), WalledGrid.key(0, 0));
        return new Report(path.length(), path.cost(), payloadRoundTrips(), sampleNoise());
    }

    private static boolean payloadRoundTrips() {
        byte[] raw = new byte[4096];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i / 512);
        }
        ByteBuf buf = Unpooled.buffer();
        try {
            WireCompression.writeBlob(buf, raw, 1 << 20, WireCompression.localDecodeMask());
            return Arrays.equals(raw, WireCompression.readBlob(buf, 1 << 20)) && buf.readableBytes() == 0;
        } finally {
            buf.release();
        }
    }

    private static String sampleNoise() {
        if (!CendaKernels.isAvailable()) {
            return "native kernels not built (pure-Java consumer still works)";
        }
        long node = CendaKernels.createSimplexFbm(3, 2.0f, 0.5f, 1f / 64f);
        try {
            float[] out = new float[16 * 16];
            CendaKernels.fillGrid2D(node, out, 0f, 0f, 16, 16, 1f, 1f, 1337);
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (float v : out) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            return "16x16 simplex fbm in [" + min + ", " + max + "] (" + CendaKernels.simdLevel() + ")";
        } finally {
            CendaKernels.destroy(node);
        }
    }

    /** 8x8 four-connected grid with a wall at x = 4 except a gap at y = 7; goal (7, 0). */
    static final class WalledGrid implements SearchDomain {
        static final int SIZE = 8;
        private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        static long key(int x, int y) {
            return (long) y * SIZE + x;
        }

        private static boolean open(int x, int y) {
            return x >= 0 && y >= 0 && x < SIZE && y < SIZE && (x != 4 || y == SIZE - 1);
        }

        @Override
        public int successors(long node, long[] outNodes, float[] outCosts) {
            int x = (int) (node % SIZE);
            int y = (int) (node / SIZE);
            int n = 0;
            for (int[] s : STEPS) {
                if (open(x + s[0], y + s[1])) {
                    outNodes[n] = key(x + s[0], y + s[1]);
                    outCosts[n++] = 1f;
                }
            }
            return n;
        }

        @Override
        public float heuristic(long node) {
            return Math.abs(7 - (int) (node % SIZE)) + (int) (node / SIZE);
        }

        @Override
        public boolean isGoal(long node) {
            return node == key(7, 0);
        }

        @Override
        public int maxSuccessors() {
            return 4;
        }
    }
}
