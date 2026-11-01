package com.openmason.engine.cearl;

import com.openmason.engine.vram.VramPlan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fully compiled CEARL program: the resolved VRAM plan (when the source
 * declared one) and every kernel lowered to GLSL compute with its binding
 * layout. Immutable; compilation happens once, queries are lookups.
 */
public final class CearlProgram {

    private final String sourceName;
    private final VramPlan plan;
    private final Map<String, CearlKernel> kernels;

    CearlProgram(String sourceName, VramPlan plan, LinkedHashMap<String, CearlKernel> kernels) {
        this.sourceName = sourceName;
        this.plan = plan;
        this.kernels = Collections.unmodifiableMap(kernels);
    }

    public String sourceName() {
        return sourceName;
    }

    /** The compiled plan, or null when the program declared none. */
    public VramPlan plan() {
        return plan;
    }

    public CearlKernel kernel(String name) {
        return kernels.get(name);
    }

    /** Kernels in declaration order (unmodifiable). */
    public Map<String, CearlKernel> kernels() {
        return kernels;
    }
}
