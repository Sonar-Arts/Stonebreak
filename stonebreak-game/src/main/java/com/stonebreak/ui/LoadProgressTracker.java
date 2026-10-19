package com.stonebreak.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.function.LongSupplier;

/**
 * Measures a world load as a sequence of real {@link Phase}s and turns that into a progress
 * fraction and a time-remaining estimate.
 *
 * <h2>How the estimate works</h2>
 *
 * <p>Every phase has an expected duration: what it took last time for this load <em>profile</em>
 * (generator + new/existing world), persisted to a small properties file, or a first-run default.
 * The running phase's remaining time blends that expectation with its live rate
 * ({@code elapsed / fraction}) once it has reported enough progress to trust one; phases still to
 * come contribute their expected durations. Progress is then elapsed time over
 * elapsed-plus-remaining, so the bar moves at the same pace the ETA counts down.</p>
 *
 * <p>Phases run in order but any may be skipped (a saved spawn needs no search; services already
 * pinned to the seed need no restart). Skipping is discovered when a later phase begins, and a
 * skipped phase simply drops out of the estimate. Progress is clamped to never move backwards.</p>
 *
 * <p>Thread-safe: phases are reported from the server boot and client build threads while the
 * render thread takes {@link #snapshot()}s.</p>
 */
public final class LoadProgressTracker {

    public enum Phase {
        SERVICES("Starting TGMPipe", 30.0),
        SPAWN_SEARCH("Finding a spawn point", 5.0),
        PREGEN("Generating spawn area", 20.0),
        STREAM("Entering world", 2.0);

        private final String displayName;
        private final double defaultSeconds;

        Phase(String displayName, double defaultSeconds) {
            this.displayName = displayName;
            this.defaultSeconds = defaultSeconds;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** What the loading screen draws for one frame. {@code phase} is null before the first phase. */
    public record Snapshot(Phase phase, String detail, double fraction, double etaSeconds) {}

    /** Below this the running phase's own fraction is too early to extrapolate a rate from. */
    private static final double LIVE_RATE_MIN_FRACTION = 0.1;
    /** Fraction at which the live rate has fully replaced the stored expectation. */
    private static final double LIVE_RATE_FULL_FRACTION = 0.4;
    /** An overrunning phase with no progress signal still claims this share of its expectation. */
    private static final double OVERRUN_FLOOR = 0.2;
    /** Stored durations below this are "was skipped last time", not a real expectation. */
    private static final double MIN_TRUSTED_SECONDS = 0.5;
    /** Time constant of the displayed-ETA smoothing. */
    private static final double ETA_SMOOTHING_SECONDS = 1.0;
    /** Weight of the newest measurement when folding it into the stored duration. */
    private static final double STORE_BLEND = 0.5;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final Path store;
    private final LongSupplier clock;

    private boolean running;
    private String profile;
    private final Map<Phase, Double> expected = new EnumMap<>(Phase.class);
    private final Map<Phase, Double> measured = new EnumMap<>(Phase.class);
    private final EnumSet<Phase> skipped = EnumSet.noneOf(Phase.class);
    private Phase current;
    private long phaseStartNanos;
    private double phaseFraction;
    private String detail;

    private double shownFraction;
    private double smoothedEta = Double.NaN;
    private long lastSnapshotNanos;

    /**
     * @param store where per-profile phase durations persist, or {@code null} to keep none
     * @param clock nanosecond clock ({@code System::nanoTime} in production)
     */
    public LoadProgressTracker(Path store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Resets for a new load under the {@code "default"} profile. */
    public synchronized void start() {
        running = true;
        measured.clear();
        skipped.clear();
        current = null;
        phaseFraction = 0.0;
        detail = null;
        shownFraction = 0.0;
        smoothedEta = Double.NaN;
        lastSnapshotNanos = clock.getAsLong();
        loadProfile("default");
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * Switches the durations the estimate is based on, e.g. {@code "DIFFUSION/new"}. Loads differ
     * enough between generators and between new and existing worlds that one shared history
     * would mispredict all of them.
     */
    public synchronized void setProfile(String profile) {
        loadProfile(profile);
    }

    /**
     * Starts {@code phase}, closing the running one and marking any phases in between as skipped.
     * A phase earlier than (or equal to) the running one is ignored — phases only move forward.
     */
    public synchronized void beginPhase(Phase phase) {
        if (!running || (current != null && phase.ordinal() <= current.ordinal())) {
            return;
        }
        long now = clock.getAsLong();
        int firstSkipped = 0;
        if (current != null) {
            measured.put(current, (now - phaseStartNanos) / NANOS_PER_SECOND);
            firstSkipped = current.ordinal() + 1;
        }
        for (Phase p : Phase.values()) {
            if (p.ordinal() >= firstSkipped && p.ordinal() < phase.ordinal()) {
                skipped.add(p);
            }
        }
        current = phase;
        phaseStartNanos = now;
        phaseFraction = 0.0;
        detail = null;
    }

    /** Reports how far the running phase is, 0..1. Values below the current one are ignored. */
    public synchronized void setPhaseFraction(double fraction) {
        phaseFraction = Math.max(phaseFraction, Math.clamp(fraction, 0.0, 1.0));
    }

    /** A short line under the phase name, e.g. {@code "Chunks 37/81"}; null clears it. */
    public synchronized void setPhaseDetail(String detail) {
        this.detail = detail;
    }

    /** Ends the load and folds this run's phase durations into the stored profile. */
    public synchronized void finish() {
        if (!running) {
            return;
        }
        if (current != null) {
            measured.put(current, (clock.getAsLong() - phaseStartNanos) / NANOS_PER_SECOND);
        }
        running = false;
        saveProfile();
    }

    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        double dt = Math.max(0.0, (now - lastSnapshotNanos) / NANOS_PER_SECOND);
        lastSnapshotNanos = now;

        if (current == null) {
            double eta = 0.0;
            for (Phase p : Phase.values()) {
                eta += expected.get(p);
            }
            return new Snapshot(null, detail, shownFraction, smooth(eta, dt));
        }

        double elapsedInPhase = (now - phaseStartNanos) / NANOS_PER_SECOND;
        double eta = currentPhaseRemaining(elapsedInPhase);
        for (Phase p : Phase.values()) {
            if (p.ordinal() > current.ordinal()) {
                eta += expected.get(p);
            }
        }

        double done = elapsedInPhase;
        for (double seconds : measured.values()) {
            done += seconds;
        }
        double total = done + eta;
        double fraction = total > 0.0 ? done / total : 0.0;
        shownFraction = Math.max(shownFraction, Math.min(fraction, 1.0));

        return new Snapshot(current, detail, shownFraction, smooth(eta, dt));
    }

    private double currentPhaseRemaining(double elapsed) {
        double stored = expected.get(current);
        // A phase that was skipped last time has no usable history once it actually runs.
        double expectation = stored >= MIN_TRUSTED_SECONDS ? stored : current.defaultSeconds;
        double prior = Math.max(expectation - elapsed, OVERRUN_FLOOR * expectation) * (1.0 - phaseFraction);
        if (phaseFraction < LIVE_RATE_MIN_FRACTION) {
            return prior;
        }
        double live = elapsed * (1.0 - phaseFraction) / phaseFraction;
        double trust = Math.min(1.0, (phaseFraction - LIVE_RATE_MIN_FRACTION)
                / (LIVE_RATE_FULL_FRACTION - LIVE_RATE_MIN_FRACTION));
        return trust * live + (1.0 - trust) * prior;
    }

    private double smooth(double eta, double dt) {
        if (Double.isNaN(smoothedEta)) {
            smoothedEta = eta;
        } else {
            double alpha = 1.0 - Math.exp(-dt / ETA_SMOOTHING_SECONDS);
            smoothedEta += alpha * (eta - smoothedEta);
        }
        return smoothedEta;
    }

    // ─── Persistence ────────────────────────────────────────────────────────

    private void loadProfile(String name) {
        profile = name;
        Properties props = readStore();
        for (Phase p : Phase.values()) {
            expected.put(p, parseSeconds(props.getProperty(key(name, p)), p.defaultSeconds));
        }
    }

    /**
     * Blends each phase that ran into its stored duration; a skipped phase blends toward zero so a
     * profile that never needs it (e.g. Standard never starts services) stops predicting it.
     * Phases the load never reached keep their history.
     */
    private void saveProfile() {
        if (store == null) {
            return;
        }
        Properties props = readStore();
        for (Phase p : Phase.values()) {
            Double seconds = skipped.contains(p) ? Double.valueOf(0.0) : measured.get(p);
            if (seconds == null) {
                continue;
            }
            String k = key(profile, p);
            String previous = props.getProperty(k);
            double blended = previous == null
                    ? seconds
                    : STORE_BLEND * seconds + (1.0 - STORE_BLEND) * parseSeconds(previous, seconds);
            props.setProperty(k, String.format(java.util.Locale.ROOT, "%.3f", blended));
        }
        try (OutputStream out = Files.newOutputStream(store)) {
            props.store(out, "Stonebreak loading-screen phase durations (seconds), per load profile");
        } catch (IOException e) {
            System.err.println("[LOADING] Could not save load timings to " + store + ": " + e.getMessage());
        }
    }

    private Properties readStore() {
        Properties props = new Properties();
        if (store != null && Files.isRegularFile(store)) {
            try (InputStream in = Files.newInputStream(store)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[LOADING] Could not read load timings from " + store + ": " + e.getMessage());
            }
        }
        return props;
    }

    private static String key(String profile, Phase phase) {
        return profile + "." + phase.name();
    }

    private static double parseSeconds(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed >= 0.0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
