package com.openmason.engine.ui.fidelity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The fidelity gate a screen passes before its legacy path may be removed (#282, #296): for every
 * case of its matrix, the migrated (document) rendering must match the legacy rendering in
 * geometry under a {@link GeometryRule} and in pixels under a {@link PixelTolerance}.
 *
 * <p>Pair it with the committed legacy baselines ({@link GoldenStore}): those prove the legacy
 * side did not drift, this proves the candidate matches it. Interaction replays, host/action
 * tests and the runtime budgets ({@code ui.diag}) are the other gates; see
 * {@code docs/ui-program/ui-fidelity.md}.
 */
public final class MigrationGate {

    /**
     * What one renderer produced for a case: the frame, the rects of its named parts, the rects
     * its input actually responds to, and what activating each named control does.
     *
     * @param rects   painted geometry of named parts (panel, buttons, labels)
     * @param hits    where a press lands on each named control, from the renderer's own hit
     *                testing (legacy bounds checks; the document's {@code hitTest}); empty when
     *                the capture has no input
     * @param actions per named control, the action id its activation fires ({@code resume},
     *                {@code stonebreak:screen.pause.quit}); catches a control wired to a stale or
     *                wrong callback, which no pixel shows
     */
    public record Capture(FidelityImage image, Map<String, float[]> rects, Map<String, float[]> hits,
                          Map<String, String> actions) {

        public Capture {
            Objects.requireNonNull(image, "image");
            rects = Map.copyOf(rects);
            hits = Map.copyOf(hits);
            actions = Map.copyOf(actions);
        }

        /** A capture without input channels. */
        public Capture(FidelityImage image, Map<String, float[]> rects) {
            this(image, rects, Map.of(), Map.of());
        }
    }

    /** Renders one case of a screen, fully pinned (font, assets, clock, seed, backdrop). */
    @FunctionalInterface
    public interface Renderer {
        Capture render(FidelityCase c);
    }

    /**
     * One case's verdict.
     *
     * @param hits    hit regions compared like geometry (prefixed {@code hit }), plus action wiring
     *                ({@code action <name>}) problems
     */
    public record CaseResult(FidelityCase c, GeometryComparator.GeometryReport geometry, PixelReport pixels,
                             GeometryComparator.GeometryReport hits) {

        public boolean passed() {
            return geometry.passed() && pixels.passed() && hits.passed();
        }
    }

    /** Every case's verdict, in matrix order. */
    public record Report(String screen, List<CaseResult> cases) {

        public Report {
            cases = List.copyOf(cases);
        }

        public boolean passed() {
            return cases.stream().allMatch(CaseResult::passed);
        }

        /** A fixed-width table, one line per case, for test output and issue evidence. */
        public String table() {
            StringBuilder sb = new StringBuilder("fidelity gate: ").append(screen)
                .append(passed() ? " PASS" : " FAIL").append('\n');
            for (CaseResult r : cases) {
                sb.append(String.format(java.util.Locale.ROOT, "  %-4s %-44s %s | %s | %s%n",
                    r.passed() ? "ok" : "FAIL", r.c().id(), r.geometry().summary(), r.pixels().summary(),
                    r.hits().passed() ? "input ok" : r.hits().summary()));
            }
            return sb.toString();
        }

        /** Writes the diff image of every failing case to {@code dir} as {@code <id>.diff.png}. */
        public void writeDiffs(Path dir) {
            for (CaseResult r : cases) {
                if (!r.pixels().passed() && r.pixels().diff() != null) {
                    r.pixels().diff().write(dir.resolve(r.c().id() + ".diff.png"));
                }
            }
        }
    }

    private final GeometryRule geometry;
    private final Function<FidelityCase, PixelTolerance> pixels;

    /**
     * @param pixels tolerance per case, usually
     *               {@code c -> PixelTolerance.assetScale(c.viewport().uiScale(), assetRects(c))}
     */
    public MigrationGate(GeometryRule geometry, Function<FidelityCase, PixelTolerance> pixels) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.pixels = Objects.requireNonNull(pixels, "pixels");
    }

    public Report run(String screen, List<FidelityCase> cases, Renderer legacy, Renderer candidate) {
        List<CaseResult> out = new ArrayList<>();
        for (FidelityCase c : cases) {
            Capture want = legacy.render(c);
            Capture got = candidate.render(c);
            int w = c.viewport().width();
            int h = c.viewport().height();
            GeometryComparator.GeometryReport g = GeometryComparator.compare(want.rects(), got.rects(), w, h, geometry);
            if (want.rects().isEmpty() && got.rects().isEmpty()) {
                // Two blank captures agree perfectly and prove nothing: a renderer that lost its
                // rect reporting must not pass the gate (#296 review).
                g = new GeometryComparator.GeometryReport(List.of("no named rects captured on either side"), 0);
            }
            PixelReport p = PixelComparator.compare(want.image(), got.image(), pixels.apply(c));
            out.add(new CaseResult(c, g, p, input(want, got, w, h)));
        }
        return new Report(screen, out);
    }

    /** Hit regions under the same geometry rule as the paint, then the action each control fires. */
    private GeometryComparator.GeometryReport input(Capture want, Capture got, int w, int h) {
        GeometryComparator.GeometryReport hits = GeometryComparator.compare(prefixed(want.hits()),
            prefixed(got.hits()), w, h, geometry);
        List<String> problems = new ArrayList<>(hits.problems());
        java.util.TreeSet<String> names = new java.util.TreeSet<>(want.actions().keySet());
        names.addAll(got.actions().keySet());
        for (String name : names) {
            String a = want.actions().get(name);
            String b = got.actions().get(name);
            if (!Objects.equals(a, b)) {
                problems.add("action " + name + ": " + (b == null ? "none" : b) + " vs legacy " + (a == null ? "none" : a));
            }
        }
        return new GeometryComparator.GeometryReport(problems, hits.worst());
    }

    private static Map<String, float[]> prefixed(Map<String, float[]> hits) {
        Map<String, float[]> out = new java.util.HashMap<>();
        hits.forEach((k, v) -> out.put("hit " + k, v));
        return out;
    }
}
