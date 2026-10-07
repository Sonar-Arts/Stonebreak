package com.stonebreak.ui.runtime;

/**
 * The one time source for UI documents in the game window (#295/#288 review). Each frame
 * {@link #beginFrame()} samples the wall clock once; the routers' clocks (controller repeat,
 * tooltips, caret blink), the documents' {@code ui} clock and their scripts all advance by that
 * same {@link #uiDt()}. The {@code game} clock advances by {@link #gameDt()}: the simulation time
 * the world actually stepped since the previous frame ({@link #simulated}), so it stops under
 * the pause menu and follows the game's own step clamp instead of the render frame.
 *
 * <p>Main thread only.
 */
public final class UiFrameClock {

    /** Longest step a frame may advance UI time by: a hitch never fast-forwards animations. */
    public static final double MAX_STEP = 0.1;

    private static final UiFrameClock INSTANCE = new UiFrameClock();

    private final java.util.function.LongSupplier nanos;
    private long lastFrame;
    private double uiDt;
    private double pendingGame;
    private double gameDt;

    private UiFrameClock() {
        this(System::nanoTime);
    }

    UiFrameClock(java.util.function.LongSupplier nanos) {
        this.nanos = nanos;
    }

    public static UiFrameClock get() {
        return INSTANCE;
    }

    /** Called once per frame before any document is ticked (by {@code GameUiInput.frame}). */
    public void beginFrame() {
        long now = nanos.getAsLong();
        uiDt = lastFrame == 0 ? 0 : Math.min(MAX_STEP, Math.max(0, (now - lastFrame) / 1e9));
        lastFrame = now;
        gameDt = Math.min(MAX_STEP, pendingGame);
        pendingGame = 0;
    }

    /** The world stepped by {@code dt} seconds of simulation this frame. */
    public void simulated(double dt) {
        if (dt > 0 && Double.isFinite(dt)) {
            pendingGame += dt;
        }
    }

    /** Unscaled frame time for router clocks and the {@code ui} clock. */
    public double uiDt() {
        return uiDt;
    }

    /** Simulation time stepped this frame (0 while paused or out of a world). */
    public double gameDt() {
        return gameDt;
    }
}
