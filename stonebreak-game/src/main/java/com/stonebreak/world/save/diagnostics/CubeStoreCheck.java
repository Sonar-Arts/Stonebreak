package com.stonebreak.world.save.diagnostics;

import com.openmason.engine.voxel.world.CubeStore;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.WorldFormat;
import com.stonebreak.world.save.io.CubeRegionFiles;
import com.stonebreak.world.save.serialization.EntitySectionCodec;
import com.stonebreak.world.voxel.VoxelLayers;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a world's cube store ({@code <world>/cubes}) - the replacement of the column format's corruption
 * cleanup tool. Never deletes or rewrites anything: the native store already keeps the previous committed
 * record on a torn write and sets a garbage region file aside on its next write; this only REPORTS.
 * <ul>
 *   <li>headers: every region file's committed header slot (CRC, version, kind, edge) - {@link CubeRegionFiles};</li>
 *   <li>records (optional, {@code readRecords}): every cube record read back through {@link CubeStore} (record
 *       CRC + zstd), decoded into a scratch world, and its game blob (SBC1 sections, the entity section)
 *       parsed;</li>
 *   <li>the block-state table ({@code block_states.txt}) is present when any record exists.</li>
 * </ul>
 * Usable on a live world (pass its open {@link CubeStore}: thread-safe) or offline ({@link #main}).
 */
public final class CubeStoreCheck {

    private static final int BLOB_MAGIC = 0x53424331; // "SBC1" (world/chunk/Cube)

    private CubeStoreCheck() {
    }

    /** What a check found. {@code problems} empty = healthy. */
    public record Report(int regionFiles, int cubeRegions, int columnRegions, long cubeRecords, long columnRecords,
                         long storedBytes, long rawBytes, long recordsRead, int blockStates, List<String> problems) {

        public boolean healthy() {
            return problems.isEmpty();
        }

        @Override
        public String toString() {
            return String.format("regions=%d (cube %d, column %d) cubes=%d columns=%d stored=%d B raw=%d B "
                    + "read=%d blockStates=%d problems=%d", regionFiles, cubeRegions, columnRegions, cubeRecords,
                columnRecords, storedBytes, rawBytes, recordsRead, blockStates, problems.size());
        }
    }

    /**
     * Checks {@code worldDir/cubes}. {@code store} = the live store to read records through, or null to read
     * nothing but headers (unless {@code readRecords}, which then opens the store itself - offline only).
     */
    public static Report check(Path worldDir, CubeStore store, boolean readRecords) {
        Path cubesDir = worldDir.resolve(WorldFormat.CUBES_DIR);
        List<String> problems = new ArrayList<>();
        List<CubeRegionFiles.Header> headers = new ArrayList<>();
        int regionFiles = 0;
        try {
            for (Path f : CubeRegionFiles.regionFiles(cubesDir)) {
                regionFiles++;
                try {
                    CubeRegionFiles.Header h = CubeRegionFiles.read(f);
                    if (h.cubeEdge() != WorldConfiguration.CUBE_EDGE) {
                        problems.add(f.getFileName() + ": cube edge " + h.cubeEdge() + " (expected "
                            + WorldConfiguration.CUBE_EDGE + ")");
                    }
                    headers.add(h);
                } catch (IOException e) {
                    problems.add("unreadable region " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add("cannot list " + cubesDir + ": " + e.getMessage());
        }

        int cubeRegions = 0;
        int columnRegions = 0;
        long cubes = 0;
        long columns = 0;
        long stored = 0;
        long raw = 0;
        for (CubeRegionFiles.Header h : headers) {
            if (h.kind() == CubeRegionFiles.KIND_CUBE) {
                cubeRegions++;
                cubes += h.recordCount();
            } else {
                columnRegions++;
                columns += h.recordCount();
            }
            stored += h.storedBytes();
            raw += h.rawBytes();
        }

        int blockStates = 0;
        Path table = cubesDir.resolve(com.stonebreak.world.World.BLOCK_STATES_FILE);
        if (Files.isRegularFile(table)) {
            try {
                blockStates = (int) Files.readAllLines(table, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isEmpty()).count();
            } catch (IOException e) {
                problems.add("block-state table unreadable: " + e.getMessage());
            }
        } else if (cubes > 0) {
            problems.add("block-state table missing (" + table.getFileName() + ")");
        }

        long read = 0;
        if (readRecords || store != null) {
            boolean own = store == null;
            CubeStore s = store;
            CubeWorld scratch = null;
            try {
                if (own) {
                    s = CubeStore.open(cubesDir);
                }
                scratch = CubeWorld.create(WorldConfiguration.MIN_Y, WorldConfiguration.MAX_Y, 0, null);
                VoxelLayers.define(scratch);
                for (CubeRegionFiles.Header h : headers) {
                    if (h.kind() != CubeRegionFiles.KIND_CUBE) {
                        continue;
                    }
                    long[] keys = h.cubeKeys();
                    for (int i = 0; i < keys.length; i += 3) {
                        String problem = checkRecord(s, scratch, keys[i], keys[i + 1], keys[i + 2]);
                        read++;
                        if (problem != null) {
                            problems.add(problem);
                        }
                    }
                }
            } catch (RuntimeException e) {
                problems.add("record check aborted: " + e);
            } finally {
                if (scratch != null) {
                    scratch.close();
                }
                if (own && s != null) {
                    s.close();
                }
            }
        }
        return new Report(regionFiles, cubeRegions, columnRegions, cubes, columns, stored, raw, read, blockStates,
            List.copyOf(problems));
    }

    private static String checkRecord(CubeStore store, CubeWorld scratch, long cx, long cy, long cz) {
        String at = "cube (" + cx + ", " + cy + ", " + cz + ")";
        byte[] record;
        try {
            record = store.readCube(cx, cy, cz);
        } catch (RuntimeException e) {
            return at + ": unreadable record (" + e.getMessage() + ")";
        }
        if (record == null) {
            return null; // deleted since the committed header we listed
        }
        try {
            scratch.decode(cx, cy, cz, record);
            byte[] blob = scratch.getBlob(cx, cy, cz);
            String blobProblem = checkBlob(blob);
            scratch.removeCube(cx, cy, cz);
            return blobProblem == null ? null : at + ": " + blobProblem;
        } catch (RuntimeException e) {
            return at + ": record does not decode (" + e.getMessage() + ")";
        }
    }

    /** Null when {@code blob} is empty or a well-formed SBC1 blob (incl. a readable entity section). */
    static String checkBlob(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            if (in.readInt() != BLOB_MAGIC) {
                return "game blob has no SBC1 magic";
            }
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                byte[] name = new byte[in.readUnsignedShort()];
                in.readFully(name);
                int len = in.readInt();
                if (len < 0 || len > blob.length) {
                    return "game blob section " + i + " has a bad length " + len;
                }
                byte[] data = new byte[len];
                in.readFully(data);
                if (EntitySectionCodec.SECTION.equals(new String(name, StandardCharsets.UTF_8))) {
                    try {
                        EntitySectionCodec.decode(data);
                    } catch (IOException e) {
                        return "entity section unreadable (" + e.getMessage() + ")";
                    }
                }
            }
            if (in.available() > 0) {
                return "game blob has " + in.available() + " trailing bytes";
            }
            return null;
        } catch (IOException e) {
            return "game blob truncated (" + e.getMessage() + ")";
        }
    }

    /** {@code java ... CubeStoreCheck <world-dir>}: reads every record of a world that is NOT open. */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: CubeStoreCheck <world-dir>");
            System.exit(1);
        }
        Report r = check(Path.of(args[0]), null, true);
        System.out.println("=== CUBE STORE CHECK: " + args[0] + " ===");
        System.out.println(r);
        r.problems().forEach(p -> System.out.println("  PROBLEM " + p));
        System.exit(r.healthy() ? 0 : 2);
    }
}
