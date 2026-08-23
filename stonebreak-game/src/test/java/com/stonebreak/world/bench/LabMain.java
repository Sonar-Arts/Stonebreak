package com.stonebreak.world.bench;

/**
 * Plain {@code main} entry for the lab — for running under a profiler (JFR,
 * async-profiler) or any JVM flags surefire's fixed argLine won't pass through.
 * Same {@code -Dlab.*} properties as {@link ChunkFootprintLabTest}.
 */
public final class LabMain {
    private LabMain() {
    }

    public static void main(String[] args) throws Exception {
        new ChunkFootprintLab(LabConfig.fromSystemProperties()).run();
    }
}
