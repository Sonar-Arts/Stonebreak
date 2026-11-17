package com.stonebreak.mobs.entities;

import com.openmason.engine.entity.ClipState;
import com.openmason.engine.entity.EntityWorld;

/**
 * A living entity's clip clock: which animation clip it plays (a {@code MobBehaviorState} ordinal
 * for mobs), whether that clip is one-shot, the state clock (restarts when the clip changes) and the
 * continuous clock. The time a renderer samples is the state clock for one-shot clips and the
 * continuous clock for looping ones.
 *
 * <p>While the entity is in an {@link EntityManager} the clock lives in the engine's native entity
 * world ({@code ce_clip_*}, OpenMason #114) and every call goes there; the manager attaches it when
 * the entity enters the world and detaches it (copying the clock back) when it leaves. An entity
 * outside any world (a UI preview, a unit test) keeps the same clock in these fields, with the same
 * rules, so it behaves identically.
 *
 * <p>Native calls hold the world's monitor: the manager serializes everything it does with the
 * native world on it, so a clock read from another thread can never race the owner's commits.
 */
public final class EntityClipClock {

    private volatile EntityWorld world;
    private volatile long handle;

    private int clip = -1;
    private int flags;
    private float stateTime;
    private float totalTime;

    /** Plays {@code clip}; a different clip restarts the state clock. */
    public void setClip(int newClip, boolean oneShot) {
        int newFlags = oneShot ? ClipState.ONE_SHOT : 0;
        EntityWorld w = world;
        if (w != null) {
            synchronized (w) {
                w.clipSet(handle, newClip, newFlags);
            }
            return;
        }
        if (newClip != clip) {
            clip = newClip;
            stateTime = 0.0f;
        }
        flags = newFlags;
    }

    /** Advances the continuous clock by {@code dtTotal} and the state clock by {@code dtState}. */
    public void advance(float dtTotal, float dtState) {
        EntityWorld w = world;
        if (w != null) {
            synchronized (w) {
                w.clipAdvance(handle, dtTotal, dtState);
            }
            return;
        }
        totalTime += dtTotal;
        stateTime += dtState;
    }

    /** The time to sample the current clip at. */
    public float clipTime() {
        EntityWorld w = world;
        if (w != null) {
            synchronized (w) {
                return w.clipTime(handle);
            }
        }
        return (flags & ClipState.ONE_SHOT) != 0 ? stateTime : totalTime;
    }

    /** Seconds since the clip last changed. */
    public float stateTime() {
        return snapshot().stateTime();
    }

    /** The continuous clock. */
    public float totalTime() {
        return snapshot().totalTime();
    }

    /** The whole clock. */
    public ClipState snapshot() {
        EntityWorld w = world;
        if (w != null) {
            synchronized (w) {
                ClipState state = w.clip(handle);
                if (state != null) {
                    return state;
                }
            }
        }
        return new ClipState(clip, flags, stateTime, totalTime);
    }

    /** Moves the clock into the native world (the entity just entered it). Caller holds the monitor. */
    void attach(EntityWorld into, long entityHandle) {
        into.clipPut(entityHandle, new ClipState(clip, flags, stateTime, totalTime));
        this.world = into;
        this.handle = entityHandle;
    }

    /** Copies the clock back out (the entity just left the world). Caller holds the monitor. */
    void detach() {
        EntityWorld w = world;
        if (w == null) {
            return;
        }
        ClipState state = w.clip(handle);
        if (state != null) {
            clip = state.clip();
            flags = state.flags();
            stateTime = state.stateTime();
            totalTime = state.totalTime();
        }
        world = null;
        handle = 0L;
    }

    boolean isAttached() {
        return world != null;
    }
}
