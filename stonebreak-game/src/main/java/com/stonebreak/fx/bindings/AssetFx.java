package com.stonebreak.fx.bindings;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxDef;
import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.FxSystem;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.FxParams;
import com.stonebreak.fx.FxRuntime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays an asset's FX bindings ({@code fx[]}, SBO 1.12+ / SBE 1.6+) for one event (Stonebreak#437): every binding
 * of the event, at the event position plus the binding's offset, with the binding's parameter overrides, the
 * asset's texture tint when the binding asks for it, and the event strength. The single place binding semantics
 * live; {@link BlockFx}, {@link EntityFx} and {@link AmbientFx} only find the bindings and the position.
 *
 * <p>Main thread (the runtime's). Unknown FX ids are skipped (the runtime warns once).
 */
public final class AssetFx {

    /** Event strength that leaves the FX's own {@value FxRuntime#PARAM_INTENSITY} default untouched. */
    public static final float NORMAL = 1f;

    /**
     * A binding's parameter overrides as arrays, so playing it walks no map iterator (zero allocation per play).
     * Keyed by identity: bindings come from cached asset data; {@link #clearCache} when that data is dropped.
     */
    private record Overrides(String[] names, FxParamValue[] values, FxParamValue intensity) {
    }

    private static final Map<FxDef, Overrides> OVERRIDES = new IdentityHashMap<>();

    private AssetFx() {
    }

    /**
     * Fire-and-forget: plays every binding of {@code event} and releases the instances. Returns how many played.
     *
     * @param tint      {@code 0xRRGGBBAA} for bindings with {@code tintFromTexture}, or 0 for none
     * @param intensity event strength, multiplied into the FX's {@value FxRuntime#PARAM_INTENSITY}
     */
    public static int fire(FxRuntime runtime, FxData data, String event, double x, double y, double z,
                           int tint, float intensity) {
        if (runtime == null || data == null || data.isEmpty()) {
            return 0;
        }
        int played = 0;
        List<FxDef> defs = data.fx();
        for (int i = 0, n = defs.size(); i < n; i++) {
            FxDef def = defs.get(i);
            if (!def.event().equals(event)) {
                continue;
            }
            FxInstance inst = start(runtime, def, x, y, z, tint, intensity);
            if (inst != null) {
                inst.release();
                played++;
            }
        }
        return played;
    }

    /**
     * Starts one binding and returns its retained instance (null when the FX is not shipped or the budget is
     * full): the caller owns it — {@link AmbientFx} keeps ambient ones running and stops them later.
     */
    public static FxInstance start(FxRuntime runtime, FxDef def, double x, double y, double z,
                                   int tint, float intensity) {
        FxSystem system = runtime.library().find(def.fx());
        if (system == null) {
            runtime.play(def.fx(), x, y, z); // not shipped: the runtime's one-time warning
            return null;
        }
        FxInstance inst = runtime.play(system, x + def.offsetX(), y + def.offsetY(), z + def.offsetZ());
        if (inst == null) {
            return null;
        }
        Overrides overrides = overrides(def);
        for (int i = 0; i < overrides.names.length; i++) {
            FxParams.apply(inst, system, overrides.names[i], overrides.values[i]);
        }
        if (def.tintFromTexture() && tint != 0) {
            FxParams.setColor(inst, system, FxRuntime.PARAM_TINT, tint);
        }
        if (intensity != NORMAL && FxParams.find(system, FxRuntime.PARAM_INTENSITY) != null) {
            FxParamValue override = overrides.intensity;
            float base = override != null && override.isScalar() ? override.get(0)
                    : FxParams.defaultFloat(system, FxRuntime.PARAM_INTENSITY, 1f);
            FxParams.setFloat(inst, system, FxRuntime.PARAM_INTENSITY, base * intensity);
        }
        return inst;
    }

    private static Overrides overrides(FxDef def) {
        Overrides o = OVERRIDES.get(def);
        if (o == null) {
            Map<String, FxParamValue> params = def.params();
            String[] names = params.keySet().toArray(new String[0]);
            FxParamValue[] values = new FxParamValue[names.length];
            for (int i = 0; i < names.length; i++) {
                values[i] = params.get(names[i]);
            }
            o = new Overrides(names, values, params.get(FxRuntime.PARAM_INTENSITY));
            OVERRIDES.put(def, o);
        }
        return o;
    }

    /** Forgets cached overrides (the asset data they belong to was reloaded). Main thread. */
    public static void clearCache() {
        Fx.runOnMainThread(OVERRIDES::clear);
    }

    /** True when {@code data} binds anything to {@code event}. */
    public static boolean binds(FxData data, String event) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        List<FxDef> defs = data.fx();
        for (int i = 0, n = defs.size(); i < n; i++) {
            if (defs.get(i).event().equals(event)) {
                return true;
            }
        }
        return false;
    }
}
