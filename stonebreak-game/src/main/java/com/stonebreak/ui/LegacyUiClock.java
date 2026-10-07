package com.stonebreak.ui;

import java.util.Random;

/**
 * The one time and randomness seam of the legacy (hand-drawn) screens (#296). They used a zoo of
 * {@code System.nanoTime}/{@code currentTimeMillis} reads and unseeded {@link Random}s for their
 * presentation (furnace crucible, caret blinks, chat fade, GIF emoji, world-select card, splash
 * text, screen droplets), so no two captures of a screen matched. Each now asks this class, which
 * passes the system clock through unchanged until it is pinned: then time stands still at the
 * pinned instant and every {@link #random()} is seeded, so fidelity baselines and screenshot runs
 * are reproducible. Pinned, presentation timers freeze with it on purpose: chat lines never fade
 * or expire, carets never blink. A pinned run is for captures, not play.
 *
 * <p>Pin it in tests with {@link #pin}/{@link #release}, or for a whole live run with
 * {@code -Dstonebreak.ui.pinclock=<seconds>[:<seed>]}. Only presentation reads go through here;
 * input and safety timing (double-click windows, search debounce, hover-card delays, the UI-scale
 * auto-revert), save/backup timestamps and gameplay keep the real clock, since a frozen clock
 * would stop them working, and frame-delta driven animation ({@code Game.getTotalTimeElapsed}) is outside
 * its reach (#296 ledger notes).
 */
public final class LegacyUiClock {

    public static final String PROPERTY = "stonebreak.ui.pinclock";

    /** Wall-clock origin of a pinned clock: 2026-01-01T00:00:00Z. */
    public static final long PINNED_EPOCH_MILLIS = 1_767_225_600_000L;

    /** Seed used when a pin does not name one. */
    public static final long DEFAULT_SEED = 296L;

    private static final long START = System.nanoTime();

    private record Pin(double seconds, long seed) {
    }

    private static volatile Pin pin = initial();

    private LegacyUiClock() {
    }

    /** Freezes UI time at {@code seconds} and seeds every later {@link #random()} with {@code seed}. */
    public static void pin(double seconds, long seed) {
        if (!(seconds >= 0) || Double.isInfinite(seconds)) {
            throw new IllegalArgumentException("pinned time must be a finite non-negative number of seconds");
        }
        pin = new Pin(seconds, seed);
    }

    /** Back to the system clock and unseeded randomness. */
    public static void release() {
        pin = null;
    }

    /** Puts back the clock state a {@link #pinScoped} replaced. */
    @FunctionalInterface
    public interface Restore extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Pins like {@link #pin} and returns a handle that restores whatever was in force before:
     * a live {@code -Dstonebreak.ui.pinclock} pin or an outer test's pin survives a nested
     * capture, where {@link #release} would have wiped it (#296 review).
     */
    public static Restore pinScoped(double seconds, long seed) {
        Pin previous = pin;
        pin(seconds, seed);
        return () -> pin = previous;
    }

    public static boolean isPinned() {
        return pin != null;
    }

    /** Seconds of UI time: since process start, or the pinned instant. For animation phases. */
    public static double seconds() {
        Pin p = pin;
        return p != null ? p.seconds() : (System.nanoTime() - START) / 1e9;
    }

    /** Monotonic nanoseconds, for elapsed-time comparisons; constant while pinned. */
    public static long nanos() {
        Pin p = pin;
        return p != null ? (long) (p.seconds() * 1e9) : System.nanoTime();
    }

    /** Wall-clock milliseconds; {@link #PINNED_EPOCH_MILLIS} plus the pinned seconds while pinned. */
    public static long millis() {
        Pin p = pin;
        return p != null ? PINNED_EPOCH_MILLIS + (long) (p.seconds() * 1000) : System.currentTimeMillis();
    }

    /** A new random source for presentation: seeded while pinned, otherwise unseeded. */
    public static Random random() {
        Pin p = pin;
        return p != null ? new Random(p.seed()) : new Random();
    }

    private static Pin initial() {
        try {
            Pin p = parse(System.getProperty(PROPERTY));
            if (p != null) {
                // stdout like the other dev hooks: the game's root logger is WARN
                System.out.println("[pinclock] legacy UI clock pinned at " + p.seconds() + " s, seed " + p.seed());
            }
            return p;
        } catch (IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger(LegacyUiClock.class).warn("[ui] {}; clock not pinned", e.getMessage());
            return null;
        }
    }

    /** {@code <seconds>[:<seed>]}; null or blank means not pinned. */
    static Pin parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] parts = spec.trim().split(":", 2);
        try {
            double s = Double.parseDouble(parts[0]);
            long seed = parts.length > 1 ? Long.parseLong(parts[1].trim()) : DEFAULT_SEED;
            if (!(s >= 0) || Double.isInfinite(s)) {
                throw new NumberFormatException("negative or infinite");
            }
            return new Pin(s, seed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("-D" + PROPERTY + " expects <seconds>[:<seed>], got '" + spec + "'", e);
        }
    }

    /** For tests of {@link #parse}: the pinned seconds of a spec, or NaN when not pinned. */
    static double parsedSeconds(String spec) {
        Pin p = parse(spec);
        return p == null ? Double.NaN : p.seconds();
    }
}
