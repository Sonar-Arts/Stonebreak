package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.LoadProgressTracker;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.LoadingRecord;

import java.nio.file.Path;
import java.util.List;

/**
 * The candidate side of the loading screen's fidelity gate (#299; Daedalus: the load's phases): the shipped
 * {@code ui/documents/loading.sbui} on a {@link DocumentStage} whose host publishes the progress of a {@link #screen}
 * in the case's state. Variants are the phase reported last, on a tracker with a stopped clock and no stored timings:
 * {@code first} (shown, no phase yet), {@code services} (starting TGMPipe, half done, a detail line), {@code pregen}
 * (the spawn area, "Chunks 37/81") and {@code stream} (entering the world, no detail). Not a test class.
 */
public final class DocumentLoadingCapture implements MigrationGate.Renderer {

    /** A loading screen showing the variant's progress (never enters LOADING: no game here). */
    public static LoadingScreen screen(String variant) {
        Timeline t = new Timeline();
        LoadingScreen screen = new LoadingScreen(t.tracker);
        screen.showProgress();
        switch (variant) {
            case "first" -> {
            }
            case "services" -> {
                t.phase(LoadProgressTracker.Phase.SERVICES, 15, 0.5);
                t.tracker.setPhaseDetail("Loading model");
            }
            case "pregen" -> {
                t.phase(LoadProgressTracker.Phase.SERVICES, 25, 1.0);
                t.phase(LoadProgressTracker.Phase.SPAWN_SEARCH, 3, 1.0);
                t.phase(LoadProgressTracker.Phase.PREGEN, 8, 37 / 81.0);
                t.tracker.setPhaseDetail("Chunks 37/81");
            }
            case "stream" -> stream(t);
            default -> throw new IllegalArgumentException("loading variant: first|services|pregen|stream, not "
                + variant);
        }
        screen.settleProgress();
        return screen;
    }

    /** A whole load up to the world streaming in, its last phase done: a full bar. */
    public static void stream(Timeline t) {
        t.phase(LoadProgressTracker.Phase.SERVICES, 25, 1.0);
        t.phase(LoadProgressTracker.Phase.SPAWN_SEARCH, 3, 1.0);
        t.phase(LoadProgressTracker.Phase.PREGEN, 18, 1.0);
        t.phase(LoadProgressTracker.Phase.STREAM, 1, 1.0);
    }

    /** A tracker on a hand-moved clock with no stored timings: each phase starts, runs its seconds, reports. */
    public static final class Timeline {
        private long nanos;
        public final LoadProgressTracker tracker = new LoadProgressTracker(
            Path.of("target", "fixture-no-loading-timings.properties"), () -> nanos);

        public void phase(LoadProgressTracker.Phase phase, double seconds, double fraction) {
            tracker.beginPhase(phase);
            nanos += (long) (seconds * 1_000_000_000L);
            tracker.setPhaseFraction(fraction);
        }
    }

    /** The legacy layout's rects (device px at every UI scale): the logo centred 100 px above the middle, the bar. */
    public static java.util.Map<String, float[]> rects(int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        float logoW = 120f * (1641f / 419f);
        return java.util.Map.of("logo", new float[]{cx - logoW / 2f, cy - 100f - 60f, logoW, 120f},
            "bar", new float[]{cx - 200f, cy + 55f, 400f, 30f});
    }

    private final SbuiArchive shipped;
    private final boolean oracleRects;

    public DocumentLoadingCapture(SbuiArchive shipped) {
        this(shipped, false);
    }

    private DocumentLoadingCapture(SbuiArchive shipped, boolean oracleRects) {
        this.shipped = shipped;
        this.oracleRects = oracleRects;
    }

    /** Re-freezing the reference after an intended change: the document's pixels, {@link #rects}' geometry. */
    public static DocumentLoadingCapture reference() throws java.io.IOException {
        return new DocumentLoadingCapture(GameUiDocuments.readScreen(LoadingScreen.DOCUMENT_ID), true);
    }

    public static DocumentLoadingCapture shipped() throws java.io.IOException {
        return new DocumentLoadingCapture(GameUiDocuments.readScreen(LoadingScreen.DOCUMENT_ID));
    }

    static DocumentStage stage(SbuiArchive sbui, FidelityCase.Viewport vp, String variant) throws Exception {
        RecordingServices services = new RecordingServices();
        services.loading = LoadingRecord.of(screen(variant));
        return new DocumentStage(sbui, vp.width(), vp.height(), vp.uiScale(), services,
            MultiplayerSession.Mode.MENU);
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(shipped, c.viewport(), c.variant())) {
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), oracleRects
                ? rects(c.viewport().width(), c.viewport().height()) : stage.rects(List.of("logo", "bar")));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the loading document for " + c.id(), e);
        }
    }
}
