package com.openmason.engine.vram;

import com.openmason.engine.diagnostics.GpuMemoryTracker;

/**
 * One named VRAM pool in a compiled plan: an accounting bucket with a budget,
 * a shed priority, storage/growth strategy hints, and optionally the arena
 * policy for consumers that sub-allocate ({@link VramArenaPolicy}).
 *
 * @param name        the pool's plan name (e.g. "chunk_mesh")
 * @param category    the {@link GpuMemoryTracker} category this pool's bytes
 *                    are tracked under, or null when purely declarative
 * @param budgetBytes resolved budget in bytes; 0 = no budget declared
 * @param priority    shed order under pressure — higher holds its ground longer
 * @param storage     buffer storage strategy hint
 * @param grow        arena growth strategy ({@code SPARSE} reserved for the
 *                    ARB_sparse_buffer path; consumers fall back to COPY and
 *                    log until it ships)
 * @param arena       arena sizing policy, or null when the pool has none
 */
public record VramPool(String name, GpuMemoryTracker.Category category, long budgetBytes,
                       int priority, Storage storage, Grow grow, VramArenaPolicy arena) {

    public enum Storage { STATIC, PERSISTENT }

    public enum Grow { COPY, SPARSE }
}
