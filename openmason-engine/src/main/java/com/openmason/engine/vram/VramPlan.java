package com.openmason.engine.vram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compiled VRAM allocation plan — the runtime artifact a CEARL {@code plan}
 * block resolves to. Immutable; all shares, inheritance, and {@code when}
 * guards were resolved at compile time, so queries here are plain lookups.
 */
public final class VramPlan {

    /** {@code on pressure > threshold { shed ... }} — thresholds are fractions. */
    public record PressureRule(double threshold, List<String> shedOrder) {
    }

    private final String name;
    private final long deviceBudgetBytes;
    private final double headroom;
    private final Map<String, VramPool> pools;
    private final List<PressureRule> pressureRules;

    public VramPlan(String name, long deviceBudgetBytes, double headroom,
                    Map<String, VramPool> pools, List<PressureRule> pressureRules) {
        this.name = name;
        this.deviceBudgetBytes = Math.max(0, deviceBudgetBytes);
        this.headroom = Math.clamp(headroom, 0.0, 0.9);
        this.pools = Collections.unmodifiableMap(new LinkedHashMap<>(pools));
        this.pressureRules = List.copyOf(pressureRules);
    }

    public String name() {
        return name;
    }

    /** Declared device budget in bytes; 0 when the plan declared none. */
    public long deviceBudgetBytes() {
        return deviceBudgetBytes;
    }

    /** Fraction of the budget kept free (pressure reaches 1.0 early by this much). */
    public double headroom() {
        return headroom;
    }

    /** Budget minus headroom — the line pressure is measured against. 0 = no budget. */
    public long softBudgetBytes() {
        return deviceBudgetBytes == 0 ? 0 : (long) (deviceBudgetBytes * (1.0 - headroom));
    }

    /** used / soft budget; {@link Double#NaN} when the plan has no device budget. */
    public double pressure(long usedBytes) {
        long soft = softBudgetBytes();
        return soft <= 0 ? Double.NaN : (double) usedBytes / soft;
    }

    public VramPool pool(String name) {
        return pools.get(name);
    }

    /** Pools in declaration order (unmodifiable). */
    public Map<String, VramPool> pools() {
        return pools;
    }

    public List<PressureRule> pressureRules() {
        return pressureRules;
    }

    /**
     * The pools to shed at the given pressure, most-urgent rule first —
     * the union of every tripped rule's shed list, in rule order, deduplicated.
     * Empty when no rule trips (or pressure is NaN — no budget declared).
     */
    public List<String> shedAt(double pressure) {
        if (Double.isNaN(pressure)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (PressureRule rule : pressureRules) {
            if (pressure > rule.threshold()) {
                for (String pool : rule.shedOrder()) {
                    if (!out.contains(pool)) {
                        out.add(pool);
                    }
                }
            }
        }
        return out;
    }
}
