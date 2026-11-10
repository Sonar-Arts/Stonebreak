package com.stonebreak.fx;

import com.openmason.engine.rendering.particles.FxInstance;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;

/**
 * The game's entry point for FX (Stonebreak#436): play a shipped FX system somewhere in the world.
 *
 * <pre>{@code
 * FxInstance fire = Fx.play("stonebreak:campfire", x, y, z);   // main thread; null = not playing
 * if (fire != null) { fire.setParam("intensity", 0.5f); ... fire.stop(1f); fire.release(); }
 * Fx.fire("stonebreak:block_debris", x, y, z);                  // one-shot, any thread
 * }</pre>
 *
 * Asset-driven FX (a block's {@code break}, a mob's {@code hurt}) go through
 * {@link com.stonebreak.fx.bindings.BlockFx} / {@link com.stonebreak.fx.bindings.EntityFx} instead, which read the
 * asset's {@code fx[]} bindings — gameplay code never names effects for assets.
 *
 * <p>Everything is a no-op while no world renders (no runtime installed) or the player switched particles off.
 * Handles live on the main thread: {@link #play} returns null elsewhere.
 */
public final class Fx {

    private static volatile FxRuntime runtime;

    private Fx() {
    }

    /** Installed by the world renderer's particle pass once its runtime exists. */
    public static void install(FxRuntime next) {
        runtime = next;
    }

    /** Removes {@code current} if it is still the installed runtime. */
    public static void uninstall(FxRuntime current) {
        if (runtime == current) {
            runtime = null;
        }
    }

    /** The installed runtime whether or not FX are switched on (diagnostics), or null. */
    public static FxRuntime installed() {
        return runtime;
    }

    /** The runtime to play into, or null while FX are unavailable or switched off. */
    public static FxRuntime runtime() {
        FxRuntime rt = runtime;
        return rt != null && enabled() ? rt : null;
    }

    private static volatile Boolean enabledOverride;

    /** The player's particle setting (or the test override). */
    public static boolean enabled() {
        Boolean forced = enabledOverride;
        if (forced != null) {
            return forced;
        }
        Settings settings = Settings.getInstance();
        return settings == null || settings.getParticlesEnabled();
    }

    /** Test seam: forces {@link #enabled()} (null = follow the setting again). */
    public static void overrideEnabled(Boolean enabled) {
        enabledOverride = enabled;
    }

    /**
     * Starts the shipped FX {@code id} at world {@code (x, y, z)} and returns its retained handle (release it when
     * done). Null when FX are off, the id is not shipped, the budget is full, or this is not the main thread.
     */
    public static FxInstance play(String id, double x, double y, double z) {
        FxRuntime rt = runtime();
        if (rt == null || !onMainThread()) {
            return null;
        }
        return rt.play(id, x, y, z);
    }

    /** Fire and forget from any thread (handed to the main thread when called elsewhere). */
    public static void fire(String id, double x, double y, double z) {
        if (runtime() == null) {
            return;
        }
        runOnMainThread(() -> {
            FxRuntime rt = runtime();
            if (rt != null) {
                rt.fire(id, x, y, z);
            }
        });
    }

    /** True on the main (render) thread, which owns the FX runtime. Without a game (tests) everything is main. */
    public static boolean onMainThread() {
        Game game = Game.getInstance();
        return game == null || game.isMainThread();
    }

    /** Runs {@code task} now on the main thread, or hands it over to the main thread from elsewhere. */
    public static void runOnMainThread(Runnable task) {
        Game game = Game.getInstance();
        if (game == null) {
            task.run();
        } else {
            game.runOnMainThread(task);
        }
    }
}
