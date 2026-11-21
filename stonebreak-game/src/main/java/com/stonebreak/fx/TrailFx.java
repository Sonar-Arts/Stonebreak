package com.stonebreak.fx;

import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.FxSystem;

/**
 * A shipped FX that follows something moving (Stonebreak#441: the fire bolt's trail): started on the first
 * {@link #follow}, moved there on every later one, its {@value #PARAM_DIRECTION} aimed where the caller says. The
 * owner {@link #end}s it when the moving thing stops or goes away; particles already emitted finish their lives.
 *
 * <p>Main thread only (FX handles live there): calls from elsewhere are ignored. A runtime swap (new world, FX
 * switched off and on) drops the old handle and starts afresh.
 */
public final class TrailFx {

    /** Vector parameter a trail FX binds to its emitters' launch direction. */
    public static final String PARAM_DIRECTION = "direction";

    private final String id;
    private FxInstance instance;
    private FxRuntime owner;

    public TrailFx(String id) {
        this.id = id;
    }

    /** Keeps the trail at {@code (x, y, z)}, launching along {@code (dirX, dirY, dirZ)}. */
    public void follow(double x, double y, double z, float dirX, float dirY, float dirZ) {
        if (!Fx.onMainThread()) {
            return;
        }
        FxRuntime runtime = Fx.runtime();
        if (runtime != owner || (instance != null && !instance.isAlive())) {
            drop();
        }
        if (runtime == null) {
            return;
        }
        if (instance == null) {
            instance = runtime.play(id, x, y, z);
            if (instance == null) {
                return;
            }
            owner = runtime;
        }
        instance.move(x, y, z);
        FxSystem system = instance.system();
        if (FxParams.find(system, PARAM_DIRECTION) != null) {
            instance.setParam(PARAM_DIRECTION, dirX, dirY, dirZ);
        }
    }

    /** Stops emitting and gives the handle back. Safe to call repeatedly, and from any thread. */
    public void end() {
        if (instance == null) {
            return;
        }
        if (!Fx.onMainThread()) {
            Fx.runOnMainThread(this::end);
            return;
        }
        drop();
    }

    /** True while a trail instance is held. */
    public boolean isActive() {
        return instance != null;
    }

    /** Stops and releases the held instance, unless its runtime is gone (closed with everything in it). */
    private void drop() {
        if (instance != null && owner != null && owner == Fx.installed()) {
            try {
                instance.stop(0f);
                instance.release();
            } catch (IllegalStateException alreadyReturned) {
                // the runtime recycled it already: nothing left to stop or release
            }
        }
        instance = null;
        owner = null;
    }
}
