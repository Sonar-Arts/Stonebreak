package com.openmason.engine.ui.script;

/**
 * Limits of one screen's Lua state (#292).
 *
 * @param memoryLimitBytes  per-state allocator cap; an allocation over it is a Lua memory error
 * @param deadlineMillis    wall-clock limit of any one call into the state (watchdog; ~0 cost)
 * @param instructionBudget exact instruction budget per call, 0 = off. An opt-in diagnostic:
 *                          any count hook doubles VM-bound cost in Lua 5.5 (measured in #283)
 */
public record UiScriptOptions(long memoryLimitBytes, double deadlineMillis, long instructionBudget) {

    public static final UiScriptOptions DEFAULTS = new UiScriptOptions(16L << 20, 100, 0);

    public UiScriptOptions {
        if (memoryLimitBytes < 0 || !(deadlineMillis > 0) || instructionBudget < 0) {
            throw new IllegalArgumentException("invalid script limits");
        }
    }

    public UiScriptOptions withMemoryLimit(long bytes) {
        return new UiScriptOptions(bytes, deadlineMillis, instructionBudget);
    }

    public UiScriptOptions withDeadline(double millis) {
        return new UiScriptOptions(memoryLimitBytes, millis, instructionBudget);
    }

    public UiScriptOptions withInstructionBudget(long instructions) {
        return new UiScriptOptions(memoryLimitBytes, deadlineMillis, instructions);
    }
}
