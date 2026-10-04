package com.openmason.engine.ui.layoutspike;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;

import static com.openmason.engine.ui.layoutspike.FlexTree.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #283 layout spike: Yoga-in-Cenda (FFM) vs a pure-Java flexbox, judged on
 * (1) fidelity to today's pause and furnace layouts across resolutions,
 * UI scales and the online-only Resync button, (2) agreement with each other
 * on seeded random trees, and (3) cost. {@code -Dcenda.bench=true} adds
 * timings and writes {@code target/flex-spike.md}.
 */
@Tag("regression")
class FlexLayoutSpikeTest {

    private static final boolean FULL = Boolean.getBoolean("cenda.bench");
    private static final int[][] RESOLUTIONS = {{800, 600}, {1280, 720}, {1366, 768}, {1920, 1080},
        {1921, 1081}, {2560, 1440}, {3840, 2160}};
    private static final float[] SCALES = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final float TOLERANCE = 1e-3f;

    private final StringBuilder report = new StringBuilder();

    private interface Engine {
        float[] layout(FlexTree t, float w, float h, float pointScale, FlexMeasure m);
    }

    private static final Engine YOGA = YogaFlex::layout;
    private static final Engine JAVA = JavaFlex::layout;

    @Test
    void spike() throws IOException {
        assumeTrue(YogaFlex.isAvailable(), "Cenda library with Yoga not built");
        line("# Flexbox spike — Yoga in Cenda vs Java");
        line("");
        line("- JDK " + System.getProperty("java.vm.version") + ", " + System.getProperty("os.name") + " "
            + System.getProperty("os.arch") + ", Yoga v3.2.1 via `cf_layout` (ABI 1)");
        line("- Fixtures: " + RESOLUTIONS.length + " framebuffer sizes x " + SCALES.length + " UI scales"
            + " (pause: x online/offline). Legacy oracle pinned to 2b4bcc91.");
        line("");

        Fidelity pauseYoga = fidelity("pause", YOGA, 0f);
        Fidelity pauseJava = fidelity("pause", JAVA, 0f);
        Fidelity furnaceYoga0 = fidelity("furnace", YOGA, 0f);
        Fidelity furnaceYoga1 = fidelity("furnace", YOGA, 1f);
        Fidelity furnaceJava0 = fidelity("furnace", JAVA, 0f);
        Fidelity furnaceJava1 = fidelity("furnace", JAVA, 1f);
        line("## Fidelity to the current layouts");
        line("");
        line("| Screen | Engine | Pixel grid | Rects compared | Exact (≤0.001 px) | Max error | Worst case |");
        line("| --- | --- | --- | --- | --- | --- | --- |");
        line(pauseYoga.row("pause", "Yoga", "off"));
        line(pauseJava.row("pause", "Java", "off"));
        line(furnaceYoga0.row("furnace", "Yoga", "off"));
        line(furnaceYoga1.row("furnace", "Yoga", "1 px"));
        line(furnaceJava0.row("furnace", "Java", "off"));
        line(furnaceJava1.row("furnace", "Java", "1 px"));
        line("");

        // The pause menu is pure float math: both engines must reproduce it
        // exactly, including the online-only Resync collapse.
        assertEquals(0, pauseYoga.mismatches, pauseYoga.worst);
        assertEquals(0, pauseJava.mismatches, pauseJava.worst);
        // Furnace rounding semantics differ (truncating int centring vs a
        // round-half-up pixel grid); hold the gap to one pixel.
        assertTrue(furnaceYoga1.maxError <= 1f + TOLERANCE, furnaceYoga1.worst);

        fuzz();
        if (FULL) {
            timings();
            Path out = Path.of("target", "flex-spike.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report);
            System.out.println(report);
        }
    }

    // ───────────────────────────── fidelity ─────────────────────────────

    private static final class Fidelity {
        int compared;
        int mismatches;
        float maxError;
        String worst = "none";

        String row(String screen, String engine, String grid) {
            return String.format(Locale.ROOT, "| %s | %s | %s | %,d | %.2f%% | %.3f px | %s |", screen, engine, grid,
                compared, 100.0 * (compared - mismatches) / compared, maxError, worst);
        }
    }

    private Fidelity fidelity(String screen, Engine engine, float pointScale) {
        Fidelity f = new Fidelity();
        for (int[] r : RESOLUTIONS) {
            for (float s : SCALES) {
                for (boolean online : screen.equals("pause") ? new boolean[]{false, true} : new boolean[]{false}) {
                    ScreenFixtures.Screen sc = screen.equals("pause")
                        ? ScreenFixtures.pause(r[0], r[1], s, online) : ScreenFixtures.furnace(r[0], r[1], s);
                    float[] out = engine.layout(sc.tree(), r[0], r[1], pointScale, null);
                    for (Map.Entry<String, float[]> e : sc.legacy().entrySet()) {
                        int node = sc.nodes().get(e.getKey());
                        float[] want = e.getValue();
                        f.compared++;
                        float err = 0;
                        for (int k = 0; k < 4; k++) {
                            err = Math.max(err, Math.abs(out[node * 4 + k] - want[k]));
                        }
                        if (err > TOLERANCE) {
                            f.mismatches++;
                        }
                        if (err > f.maxError) {
                            f.maxError = err;
                            f.worst = String.format(Locale.ROOT, "%dx%d s=%.2f%s `%s`: got (%.1f, %.1f, %.1f, %.1f) want (%.1f, %.1f, %.1f, %.1f)",
                                r[0], r[1], s, online ? " online" : "", e.getKey(),
                                out[node * 4], out[node * 4 + 1], out[node * 4 + 2], out[node * 4 + 3],
                                want[0], want[1], want[2], want[3]);
                        }
                    }
                }
            }
        }
        return f;
    }

    // ───────────────────────────── fuzz ─────────────────────────────

    /** Monospace fake text: 8 px per char, 10 px lines, wraps under an at-most width. */
    private static final FlexMeasure TEXT = (id, width, wMode, height, hMode, out) -> {
        int chars = 3 + id % 17;
        float natural = chars * 8f;
        float wDone = natural;
        int lines = 1;
        if (wMode != FlexMeasure.UNDEFINED && width < natural) {
            int perLine = Math.max(1, (int) (width / 8f));
            lines = (chars + perLine - 1) / perLine;
            wDone = wMode == FlexMeasure.EXACTLY ? width : perLine * 8f;
        } else if (wMode == FlexMeasure.EXACTLY) {
            wDone = width;
        }
        out[0] = wDone;
        out[1] = hMode == FlexMeasure.EXACTLY ? height : lines * 10f;
    };

    private static final String[] FEATURES = {"basic", "wrap", "grow/shrink", "absolute", "text", "min/max",
        "margin/padding", "display:none", "justify/align"};

    private void fuzz() {
        int trees = FULL ? 5000 : 500;
        Random rnd = new Random(283);
        int agree = 0;
        Map<String, int[]> byFeature = new TreeMap<>();
        for (String feature : FEATURES) {
            byFeature.put(feature, new int[2]);
        }
        String firstDiff = null;
        for (int i = 0; i < trees; i++) {
            boolean[] used = new boolean[FEATURES.length];
            FlexTree t = randomTree(rnd, used);
            float[] a = YogaFlex.layout(t, 800, 600, 0f, TEXT);
            float[] b = JavaFlex.layout(t, 800, 600, 0f, TEXT);
            boolean same = true;
            for (int k = 0; k < a.length && same; k++) {
                same = Math.abs(a[k] - b[k]) <= 0.01f;
            }
            if (same) {
                agree++;
            } else if (firstDiff == null) {
                firstDiff = "tree #" + i;
            }
            for (int f = 0; f < FEATURES.length; f++) {
                if (used[f]) {
                    byFeature.get(FEATURES[f])[0]++;
                    if (same) {
                        byFeature.get(FEATURES[f])[1]++;
                    }
                }
            }
        }
        line("## Java vs Yoga on seeded random trees (seed 283, ≤12 nodes, 800x600, no pixel grid)");
        line("");
        line(String.format(Locale.ROOT, "- **%,d / %,d trees identical (%.1f%%)** within 0.01 px.", agree, trees,
            100.0 * agree / trees));
        line("");
        line("| Feature present | Trees | Identical |");
        line("| --- | --- | --- |");
        byFeature.forEach((k, v) -> line(String.format(Locale.ROOT, "| %s | %,d | %.1f%% |", k, v[0],
            v[0] == 0 ? 0.0 : 100.0 * v[1] / v[0])));
        line("");
    }

    private static FlexTree randomTree(Random r, boolean[] used) {
        FlexTree t = new FlexTree();
        int root = t.add(-1);
        t.size(root, 800, 600);
        style(t, root, r, used, true);
        List<Integer> containers = new ArrayList<>(List.of(root));
        int nodes = 2 + r.nextInt(11);
        for (int i = 1; i < nodes; i++) {
            int parent = containers.get(r.nextInt(containers.size()));
            int n = t.add(parent);
            if (r.nextFloat() < 0.25f) {
                t.measure(n, r.nextInt(1000));
                used[4] = true;
            } else {
                containers.add(n);
            }
            style(t, n, r, used, false);
            if (r.nextFloat() < 0.6f) {
                t.set(n, WIDTH, 20 + r.nextInt(200));
            }
            if (r.nextFloat() < 0.6f) {
                t.set(n, HEIGHT, 20 + r.nextInt(150));
            }
            if (r.nextFloat() < 0.12f) {
                used[3] = true;
                t.set(n, POSITION_TYPE, 1);
                for (int side = 0; side < 4; side++) {
                    if (r.nextFloat() < 0.4f) {
                        t.set(n, POS + side, r.nextInt(60));
                    }
                }
            }
            if (r.nextFloat() < 0.06f) {
                used[7] = true;
                t.hidden(n, true);
            }
            if (r.nextFloat() < 0.15f) {
                used[5] = true;
                t.set(n, r.nextBoolean() ? MIN_W : MAX_W, 30 + r.nextInt(120));
            }
            if (r.nextFloat() < 0.3f) {
                used[2] = true;
                t.set(n, GROW, r.nextInt(3)).set(n, SHRINK, r.nextInt(2));
            }
            if (r.nextFloat() < 0.3f) {
                used[6] = true;
                t.edges(n, MARGIN, r.nextInt(10), r.nextInt(10), r.nextInt(10), r.nextInt(10));
            }
        }
        used[0] = true;
        return t;
    }

    private static void style(FlexTree t, int n, Random r, boolean[] used, boolean root) {
        t.set(n, DIRECTION, r.nextBoolean() ? ROW : COLUMN);
        if (r.nextFloat() < 0.25f) {
            used[1] = true;
            t.set(n, WRAP, WRAP_ON);
        }
        if (r.nextFloat() < 0.5f) {
            used[8] = true;
            t.justify(n, r.nextInt(6));
            t.alignItems(n, 1 + r.nextInt(4));
        }
        if (r.nextFloat() < 0.3f) {
            used[6] = true;
            t.edges(n, PADDING, r.nextInt(12), r.nextInt(12), r.nextInt(12), r.nextInt(12));
        }
        if (r.nextFloat() < 0.3f) {
            t.gap(n, r.nextInt(10), r.nextInt(10));
        }
    }

    // ───────────────────────────── timings ─────────────────────────────

    private void timings() {
        line("## Cost per full layout (tree rebuilt from records every call; best of 5 after warm-up)");
        line("");
        line("| Tree | Nodes | Yoga (FFM) | Java | Yoga Java-garbage | Java garbage |");
        line("| --- | --- | --- | --- | --- | --- |");
        timeRow("pause (online)", s -> ScreenFixtures.pause(1920, 1080, 1f, true).tree());
        timeRow("furnace", s -> ScreenFixtures.furnace(1920, 1080, 1f).tree());
        timeRow("400 text cells (recipe-book scale)", s -> textGrid(400));
        line("");
        line("Text leaves cost one measure upcall each per measure pass on the Yoga side.");
        line("");
    }

    private void timeRow(String label, Function<Void, FlexTree> make) {
        FlexTree tree = make.apply(null);
        float[] out = new float[tree.count() * 4];
        double yoga = time(() -> YogaFlex.layoutInto(tree, 1920, 1080, 1f, TEXT, out));
        double yogaGarbage = garbage;
        double java = time(() -> JavaFlex.layout(tree, 1920, 1080, 1f, TEXT));
        double javaGarbage = garbage;
        line(String.format(Locale.ROOT, "| %s | %,d | %.1f µs | %.1f µs | %,.0f B | %,.0f B |", label, tree.count(),
            yoga, java, yogaGarbage, javaGarbage));
    }

    private static FlexTree textGrid(int cells) {
        FlexTree t = new FlexTree();
        int root = t.add(-1);
        t.size(root, 1920, 1080).row(root).set(root, WRAP, WRAP_ON).gap(root, 4, 4).edges(root, PADDING, 8, 8, 8, 8);
        for (int i = 0; i < cells; i++) {
            int cell = t.add(root);
            t.set(cell, WIDTH, 120).column(cell).edges(cell, PADDING, 2, 2, 2, 2);
            int label = t.add(cell);
            t.measure(label, i);
        }
        return t;
    }

    private double garbage;

    private double time(Runnable op) {
        int n = 2000;
        for (int i = 0; i < n; i++) {
            op.run();
        }
        double best = Double.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long a0 = allocated();
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                op.run();
            }
            double us = (System.nanoTime() - t0) / 1e3 / n;
            if (us < best) {
                best = us;
                garbage = (allocated() - a0) / (double) n;
            }
        }
        return best;
    }

    private static long allocated() {
        try {
            Method m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
            return (long) m.invoke(ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private void line(String s) {
        report.append(s).append('\n');
    }
}
