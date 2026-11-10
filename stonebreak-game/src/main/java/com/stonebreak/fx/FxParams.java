package com.stonebreak.fx;

import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sets FX parameters by name only when the system declares them with a matching type: an asset binding or a game
 * convention ({@link FxRuntime#PARAM_DENSITY}) must never crash on an FX that lacks the parameter. Mismatches from
 * asset data are warned about once per system and parameter. Allocation-free on the hit path.
 */
public final class FxParams {

    private static final Logger logger = LoggerFactory.getLogger(FxParams.class);
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    private FxParams() {
    }

    /** The declared parameter {@code name}, or null. */
    public static FxParameter find(FxSystem system, String name) {
        List<FxParameter> params = system.parameters();
        for (int i = 0, n = params.size(); i < n; i++) {
            FxParameter p = params.get(i);
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** Sets float parameter {@code name} when declared; true when set. */
    public static boolean setFloat(FxInstance inst, FxSystem system, String name, float value) {
        FxParameter p = find(system, name);
        if (p == null || p.type() != FxParameter.Type.FLOAT) {
            return false;
        }
        inst.setParam(name, value);
        return true;
    }

    /** Sets colour parameter {@code name} ({@code 0xRRGGBBAA}) when declared; true when set. */
    public static boolean setColor(FxInstance inst, FxSystem system, String name, int rgba) {
        FxParameter p = find(system, name);
        if (p == null || p.type() != FxParameter.Type.COLOR) {
            return false;
        }
        inst.setParamColor(name, rgba);
        return true;
    }

    /** The default of float parameter {@code name}, or {@code fallback} when not declared as a float. */
    public static float defaultFloat(FxSystem system, String name, float fallback) {
        FxParameter p = find(system, name);
        return p == null || p.type() != FxParameter.Type.FLOAT ? fallback : p.x();
    }

    /**
     * Applies one asset-binding override ({@code fx[].params}). The value's component count must match the
     * declared type (1 = float, 3 = vector, 4 = colour); otherwise it is skipped with a one-time warning.
     */
    public static boolean apply(FxInstance inst, FxSystem system, String name, FxParamValue value) {
        FxParameter p = find(system, name);
        if (p == null) {
            warnOnce(system, name, "the FX declares no such parameter");
            return false;
        }
        switch (p.type()) {
            case FLOAT -> {
                if (!value.isScalar()) break;
                inst.setParam(name, value.get(0));
                return true;
            }
            case VECTOR -> {
                if (!value.isVector()) break;
                inst.setParam(name, value.get(0), value.get(1), value.get(2));
                return true;
            }
            case COLOR -> {
                if (!value.isColor()) break;
                inst.setParamColor(name, rgba(value.get(0), value.get(1), value.get(2), value.get(3)));
                return true;
            }
        }
        warnOnce(system, name, "value has " + value.size() + " component(s) but the parameter is " + p.type());
        return false;
    }

    /** {@code 0xRRGGBBAA} from 0..1 components. */
    public static int rgba(float r, float g, float b, float a) {
        return (channel(r) << 24) | (channel(g) << 16) | (channel(b) << 8) | channel(a);
    }

    private static int channel(float v) {
        return Math.round(Math.clamp(v, 0f, 1f) * 255f);
    }

    private static void warnOnce(FxSystem system, String name, String why) {
        if (warned.add(system.name() + "#" + name)) {
            logger.warn("FX {}: parameter override '{}' ignored: {}", system.name(), name, why);
        }
    }
}
