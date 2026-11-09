package com.stonebreak.fx.bindings;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxDef;
import com.openmason.engine.rendering.particles.FxInstance;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.fx.FxParams;
import com.stonebreak.fx.FxRuntime;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Keeps the {@code ambient} FX of nearby sources running (Stonebreak#437): a torch's flame and embers, a lit
 * furnace's smoke. Each frame {@link #sync} starts the FX of sources that appeared, moves those that move (a torch
 * another player carries) and fades out those that left the selection (out of range, unlit, broken). Ambient
 * bindings play at the source's emitter position plus their offset.
 *
 * <p>Fixed pools sized to the light budget: allocation-free per frame. Main thread.
 */
public final class AmbientFx {

    /** Sources tracked at once (≥ the light selection, {@code PointLightGlsl.MAX_LIGHTS}). */
    static final int SLOTS = 32;
    /** Ambient bindings started per source; more are ignored. */
    static final int PER_SOURCE = 4;
    /** Fade when a source leaves the selection, seconds. */
    static final float FADE = 0.35f;
    /** Ambient FX only run within this many blocks of the camera. */
    public static final float RANGE = 32f;

    private final long[] keys = new long[SLOTS];
    private final BlockType[] types = new BlockType[SLOTS];
    private final boolean[] used = new boolean[SLOTS];
    private final boolean[] seen = new boolean[SLOTS];
    private final int[] counts = new int[SLOTS];
    private final FxInstance[][] instances = new FxInstance[SLOTS][PER_SOURCE];
    private final float[][] offsets = new float[SLOTS][PER_SOURCE * 3];
    private final Vector3f scratch = new Vector3f();
    private float density = Float.NaN;

    /**
     * Reconciles the running ambient FX with this frame's {@code sources}.
     *
     * @param bindings a block's FX bindings (pass a method reference held in a field: allocation-free)
     * @param tints    a block's texture colour for {@code tintFromTexture} bindings
     */
    public void sync(FxRuntime runtime, AmbientSources sources, Function<BlockType, FxData> bindings,
                     ToIntFunction<BlockType> tints, double camX, double camY, double camZ) {
        for (int s = 0; s < SLOTS; s++) {
            seen[s] = false;
        }
        int n = sources.count();
        for (int i = 0; i < n; i++) {
            BlockType type = sources.block(i);
            if (type == null) {
                continue;
            }
            Vector3f p = sources.position(i, scratch);
            double dx = p.x - camX, dy = p.y - camY, dz = p.z - camZ;
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) {
                continue;
            }
            long key = sources.key(i);
            int slot = find(key);
            if (slot >= 0 && types[slot] != type) {
                retire(slot); // the block at this place changed: start over with the new one's FX
                slot = -1;
            }
            if (slot < 0) {
                slot = start(runtime, key, type, bindings.apply(type), tints, p);
                if (slot < 0) {
                    continue;
                }
            } else {
                move(slot, p);
            }
            seen[slot] = true;
        }
        boolean densityChanged = runtime.density() != density;
        density = runtime.density();
        for (int s = 0; s < SLOTS; s++) {
            if (!used[s]) {
                continue;
            }
            if (!seen[s]) {
                retire(s);
            } else if (densityChanged) {
                for (int k = 0; k < counts[s]; k++) {
                    FxParams.setFloat(instances[s][k], instances[s][k].system(), FxRuntime.PARAM_DENSITY, density);
                }
            }
        }
    }

    /** Sources currently running ambient FX. */
    public int activeSources() {
        int n = 0;
        for (int s = 0; s < SLOTS; s++) {
            if (used[s]) n++;
        }
        return n;
    }

    /** Stops and releases everything (a world change, FX switched off). */
    public void clear() {
        for (int s = 0; s < SLOTS; s++) {
            if (used[s]) {
                retire(s, 0f);
            }
        }
    }

    private int find(long key) {
        for (int s = 0; s < SLOTS; s++) {
            if (used[s] && keys[s] == key) {
                return s;
            }
        }
        return -1;
    }

    /** Starts the source's ambient bindings in a free slot; -1 when it has none (or no slot is free). */
    private int start(FxRuntime runtime, long key, BlockType type, FxData data, ToIntFunction<BlockType> tints,
                      Vector3f p) {
        if (data == null || !AssetFx.binds(data, FxData.EVENT_AMBIENT)) {
            return -1;
        }
        int slot = -1;
        for (int s = 0; s < SLOTS && slot < 0; s++) {
            if (!used[s]) slot = s;
        }
        if (slot < 0) {
            return -1;
        }
        int count = 0;
        List<FxDef> defs = data.fx();
        for (int d = 0, n = defs.size(); d < n && count < PER_SOURCE; d++) {
            FxDef def = defs.get(d);
            if (!def.event().equals(FxData.EVENT_AMBIENT)) {
                continue;
            }
            int tint = def.tintFromTexture() ? tints.applyAsInt(type) : 0;
            FxInstance inst = AssetFx.start(runtime, def, p.x, p.y, p.z, tint, AssetFx.NORMAL);
            if (inst == null) {
                continue;
            }
            instances[slot][count] = inst;
            offsets[slot][count * 3] = def.offsetX();
            offsets[slot][count * 3 + 1] = def.offsetY();
            offsets[slot][count * 3 + 2] = def.offsetZ();
            count++;
        }
        // A source whose FX did not play still takes the slot (count 0): it is not retried every frame.
        used[slot] = true;
        keys[slot] = key;
        types[slot] = type;
        counts[slot] = count;
        return slot;
    }

    private void move(int slot, Vector3f p) {
        float[] off = offsets[slot];
        for (int k = 0; k < counts[slot]; k++) {
            FxInstance inst = instances[slot][k];
            double x = p.x + off[k * 3], y = p.y + off[k * 3 + 1], z = p.z + off[k * 3 + 2];
            if (inst.x() != x || inst.y() != y || inst.z() != z) {
                inst.move(x, y, z);
            }
        }
    }

    private void retire(int slot) {
        retire(slot, FADE);
    }

    private void retire(int slot, float fade) {
        for (int k = 0; k < counts[slot]; k++) {
            FxInstance inst = instances[slot][k];
            inst.stop(fade);
            inst.release();
            instances[slot][k] = null;
        }
        counts[slot] = 0;
        used[slot] = false;
        types[slot] = null;
    }
}
