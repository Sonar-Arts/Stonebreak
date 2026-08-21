package com.stonebreak.world.bench;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Run parameters for the {@link ChunkFootprintLab}, read from {@code -Dlab.*}
 * system properties so one compiled harness serves every iteration.
 *
 * <ul>
 *   <li>{@code lab.tier} — side of the measured chunk square: 1, 3, 8 or 16
 *       (chunks {@code [0,tier)²}, so 8 is exactly one 8×8 region and 16 is
 *       four regions / one FastLOD region)</li>
 *   <li>{@code lab.label} — ledger file name for this iteration (e.g. {@code baseline},
 *       {@code compact16})</li>
 *   <li>{@code lab.seed} — world seed (default {@value #DEFAULT_SEED})</li>
 *   <li>{@code lab.features} — also run feature population (trees, ores, flowers)
 *       so the chunk looks like a real world chunk (default true)</li>
 *   <li>{@code lab.cearl} — path to an alternative {@code .CEARL} plan whose
 *       arena policy drives the planned-VRAM simulation (default: shipped plan)</li>
 *   <li>{@code lab.gl} — also open a hidden GL context and upload through the
 *       real region arenas to compare planned vs tracked vs driver VRAM</li>
 *   <li>{@code lab.reps} — repetitions for the best-of centre-chunk timings (default 50)</li>
 *   <li>{@code lab.out} — ledger directory (default {@code <repo>/Dev Working/bench/chunk-lab})</li>
 * </ul>
 */
public record LabConfig(int tier, String label, long seed, boolean features, String cearlPath,
                        boolean gl, int reps, Path outDir) {

    public static final long DEFAULT_SEED = 20260820L;

    public static LabConfig fromSystemProperties() {
        int tier = Integer.getInteger("lab.tier", 1);
        if (tier != 1 && tier != 3 && tier != 8 && tier != 16) {
            throw new IllegalArgumentException("lab.tier must be 1, 3, 8 or 16 (got " + tier + ")");
        }
        String label = System.getProperty("lab.label", "run");
        long seed = Long.getLong("lab.seed", DEFAULT_SEED);
        boolean features = Boolean.parseBoolean(System.getProperty("lab.features", "true"));
        String cearl = System.getProperty("lab.cearl", "");
        boolean gl = Boolean.getBoolean("lab.gl");
        int reps = Integer.getInteger("lab.reps", 50);
        String out = System.getProperty("lab.out", "");
        Path outDir = out.isEmpty()
            ? repoRoot().resolve("Dev Working").resolve("bench").resolve("chunk-lab")
            : Path.of(out);
        return new LabConfig(tier, label, seed, features, cearl.isEmpty() ? null : cearl,
            gl, reps, outDir);
    }

    /** Walks up from the CWD (surefire runs in the module dir) to the multi-module root. */
    public static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.exists(p.resolve("Testing").resolve("systems.map"))) {
                return p;
            }
            p = p.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    public Path ledgerFile() {
        return outDir.resolve("tier" + tier).resolve(label + ".json");
    }
}
