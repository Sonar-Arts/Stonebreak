package com.openmason.engine.ui.script;

/**
 * Limits of one screen's Lua state (#292).
 *
 * @param memoryLimitBytes   per-state allocator cap; an allocation over it is a Lua memory error
 * @param deadlineMillis     wall-clock limit of any one call into the state (watchdog; ~0 cost)
 * @param instructionBudget  exact instruction budget per call, 0 = off. An opt-in diagnostic:
 *                           any count hook doubles VM-bound cost in Lua 5.5 (measured in #283)
 * @param graphDebug         compile behavior graphs (#291) with trace calls for the preview's
 *                           {@link GraphDebugger}; never in the game
 * @param randomHashSeed     seed the state's string hashing randomly (hash-flooding resistance for
 *                           documents the host did not write); false = a fixed seed, so
 *                           {@code pairs()} order repeats run to run (fixtures, tests)
 * @param trustDerivedGraphs run a document's precompiled {@code derived/} graph Lua when its
 *                           recorded hashes match. The chunk itself cannot be verified without
 *                           compiling the graph again, so only hosts loading first-party documents
 *                           (packaged with the game) should trust it; otherwise graphs compile at
 *                           load and a pack cannot ship Lua that differs from its graph
 */
public record UiScriptOptions(long memoryLimitBytes, double deadlineMillis, long instructionBudget,
                              boolean graphDebug, boolean randomHashSeed, boolean trustDerivedGraphs) {

    /** Test and fixture defaults: deterministic hashing, graphs always compiled. */
    public static final UiScriptOptions DEFAULTS = new UiScriptOptions(16L << 20, 100, 0, false, false, false);

    public UiScriptOptions {
        if (memoryLimitBytes < 0 || !(deadlineMillis > 0) || instructionBudget < 0) {
            throw new IllegalArgumentException("invalid script limits");
        }
    }

    /** Host limits with the production policies: a random hash seed, graphs compiled at load. */
    public UiScriptOptions(long memoryLimitBytes, double deadlineMillis, long instructionBudget, boolean graphDebug) {
        this(memoryLimitBytes, deadlineMillis, instructionBudget, graphDebug, true, false);
    }

    public UiScriptOptions withMemoryLimit(long bytes) {
        return new UiScriptOptions(bytes, deadlineMillis, instructionBudget, graphDebug, randomHashSeed,
            trustDerivedGraphs);
    }

    public UiScriptOptions withDeadline(double millis) {
        return new UiScriptOptions(memoryLimitBytes, millis, instructionBudget, graphDebug, randomHashSeed,
            trustDerivedGraphs);
    }

    public UiScriptOptions withInstructionBudget(long instructions) {
        return new UiScriptOptions(memoryLimitBytes, deadlineMillis, instructions, graphDebug, randomHashSeed,
            trustDerivedGraphs);
    }

    public UiScriptOptions withGraphDebug(boolean on) {
        return new UiScriptOptions(memoryLimitBytes, deadlineMillis, instructionBudget, on, randomHashSeed,
            trustDerivedGraphs);
    }

    public UiScriptOptions withRandomHashSeed(boolean on) {
        return new UiScriptOptions(memoryLimitBytes, deadlineMillis, instructionBudget, graphDebug, on,
            trustDerivedGraphs);
    }

    /** See {@link #trustDerivedGraphs()}: only for first-party documents the host shipped itself. */
    public UiScriptOptions withTrustedDerivedGraphs(boolean on) {
        return new UiScriptOptions(memoryLimitBytes, deadlineMillis, instructionBudget, graphDebug, randomHashSeed,
            on);
    }
}
