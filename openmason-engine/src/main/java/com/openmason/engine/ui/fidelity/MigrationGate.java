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

    /** What one renderer produced for a case: the frame and the rects of its named parts. */
    public record Capture(FidelityImage image, Map<String, float[]> rects) {

        public Capture {
            Objects.requireNonNull(image, "image");
            rects = Map.copyOf(rects);
        }
    }

    /** Renders one case of a screen, fully pinned (font, assets, clock, seed, backdrop). */
    @FunctionalInterface
    public interface Renderer {
        Capture render(FidelityCase c);
    }

    /** One case's verdict. */
    public record CaseResult(FidelityCase c, GeometryComparator.GeometryReport geometry, PixelReport pixels) {

        public boolean passed() {
            return geometry.passed() && pixels.passed();
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
                sb.append(String.format(java.util.Locale.ROOT, "  %-4s %-44s %s | %s%n",
                    r.passed() ? "ok" : "FAIL", r.c().id(), r.geometry().summary(), r.pixels().summary()));
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
            GeometryComparator.GeometryReport g = GeometryComparator.compare(want.rects(), got.rects(),
                c.viewport().width(), c.viewport().height(), geometry);
            PixelReport p = PixelComparator.compare(want.image(), got.image(), pixels.apply(c));
            out.add(new CaseResult(c, g, p));
        }
        return new Report(screen, out);
    }
}
