package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TGMPipeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which model folder the game loads ({@link TGMPipe#modelPath}), when it refuses one ({@link TerrainModels#installProblem})
 * and when the launch setup must run again ({@link ModelSetup#fingerprint}: by content, never by timestamp or path).
 */
class ModelLocationTest {

    @TempDir
    Path slm;

    @AfterEach
    void clearProperties() {
        System.clearProperty("stonebreak.tgmpipe.repoDir");
        System.clearProperty("stonebreak.tgmpipe.model");
    }

    private static void touch(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void defaultIsTheExp2ReleaseWithNoFallbackModel() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        touch(slm.resolve("checkpoints/v4/native/manifest.json"), "an old v4 copy");
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath(), "no release in this checkout: still the release (v4 retired)");

        touch(slm.resolve(TGMPipe.DEFAULT_MODEL).resolve("native/manifest.json"), "{}");
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());

        System.setProperty("stonebreak.tgmpipe.model", "checkpoints/exp2/world_exp2b");
        assertEquals("checkpoints/exp2/world_exp2b", TGMPipe.modelPath());
    }

    @Test
    void aMissingReleaseIsAClearErrorNamingItAndGitLfs() {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());
        String problem = TerrainModels.installProblem(TGMPipe.modelPath());
        assertNotNull(problem);
        assertTrue(problem.contains(TGMPipe.DEFAULT_MODEL) && problem.contains("not installed")
                && problem.contains("git lfs pull"), problem);
        TGMPipeException e = assertThrows(TGMPipeException.class, () -> TerrainModels.requireInstalled(TGMPipe.modelPath()));
        assertEquals(problem, e.getMessage());
    }

    @Test
    void gitLfsPointerFilesAreRefusedWithTheFix() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        Path dir = slm.resolve(TGMPipe.DEFAULT_MODEL);
        Path export = dir.resolve("native");
        String pointer = "version https://git-lfs.github.com/spec/v1\n"
                + "oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 68271360\n";
        touch(export.resolve("manifest.json"), "{\"version\": 1}");
        touch(export.resolve("planner.cim"), "real network");
        touch(export.resolve("detail.cim"), pointer);
        touch(export.resolve("relief.cim"), pointer);
        touch(dir.resolve("detail.pt"), pointer);   // training weights: the game never reads them
        assertTrue(TerrainModels.isLfsPointer(export.resolve("detail.cim")));
        assertFalse(TerrainModels.isLfsPointer(export.resolve("planner.cim")));
        String problem = TerrainModels.installProblem(TGMPipe.DEFAULT_MODEL);
        assertNotNull(problem);
        assertTrue(problem.contains("Git LFS pointer") && problem.contains("native/detail.cim, native/relief.cim")
                && problem.contains("git lfs pull"), problem);
        assertThrows(TGMPipeException.class, () -> TerrainModels.requireInstalled(TGMPipe.DEFAULT_MODEL));

        touch(export.resolve("detail.cim"), "real network");
        touch(export.resolve("relief.cim"), "real network");
        assertNull(TerrainModels.installProblem(TGMPipe.DEFAULT_MODEL), "pulled: runnable (the .pt pointers do not matter)");
    }

    @Test
    void weightsWithoutTheNativeExportAreNotARunnableModel() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        touch(slm.resolve(TGMPipe.DEFAULT_MODEL).resolve("planner.pt"), "weights only");
        String problem = TerrainModels.installProblem(TGMPipe.DEFAULT_MODEL);
        assertNotNull(problem);
        assertTrue(problem.contains("native/manifest.json") && problem.contains("not installed"), problem);
    }

    @Test
    void retiredModelsAreRefusedEvenWhenAnOldCopyIsOnDisk() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        touch(slm.resolve("checkpoints/v4/native/manifest.json"), "an old v4 copy");
        String problem = TerrainModels.installProblem("checkpoints/v4");
        assertNotNull(problem);
        assertTrue(problem.contains("retired"), problem);
    }

    @Test
    void theSetupFingerprintFollowsContentNotTimestamps() throws IOException {
        Path lib = slm.resolve("libstonebreak_terrain.so");
        Path manifest = slm.resolve("native/manifest.json");
        touch(lib, "kernels v1");
        touch(manifest, "{\"export_key\": \"a\"}");
        Map<String, Path> files = new TreeMap<>(Map.of("terrain_lib", lib, "export_manifest", manifest));
        Map<String, String> facts = Map.of("gpus", "RTX 5090 (sm 12.0)", "device", "cuda:0");

        ModelSetup.Fingerprint first = ModelSetup.fingerprint(files, facts, new Properties());
        Path stampFile = slm.resolve("kernel_cache").resolve(ModelSetup.STAMP);
        ModelSetup.writeStamp(stampFile, first);
        Properties stamp = ModelSetup.readStamp(stampFile);
        assertEquals(first.value(), stamp.getProperty("fingerprint"));
        assertEquals(first.value(), ModelSetup.fingerprint(files, facts, stamp).value(), "unchanged: same fingerprint");

        // A rebuild that writes the same bytes (new mtime) is not a change: the file is hashed again and matches.
        Files.setLastModifiedTime(lib, FileTime.fromMillis(Files.getLastModifiedTime(lib).toMillis() + 60_000));
        assertEquals(first.value(), ModelSetup.fingerprint(files, facts, stamp).value(), "touched, same bytes");

        // The same bytes at another path (a moved checkout, a fresh clone) are not a change either.
        Path moved = slm.resolve("elsewhere/libstonebreak_terrain.so");
        touch(moved, "kernels v1");
        Map<String, Path> movedFiles = new TreeMap<>(files);
        movedFiles.put("terrain_lib", moved);
        assertEquals(first.value(), ModelSetup.fingerprint(movedFiles, facts, stamp).value(), "moved, same bytes");

        touch(lib, "kernels v2");
        assertNotEquals(first.value(), ModelSetup.fingerprint(files, facts, stamp).value(), "new library bytes");
        touch(lib, "kernels v1");
        touch(manifest, "{\"export_key\": \"b\"}");
        assertNotEquals(first.value(), ModelSetup.fingerprint(files, facts, stamp).value(), "a new export");
        touch(manifest, "{\"export_key\": \"a\"}");
        assertNotEquals(first.value(), ModelSetup.fingerprint(files, Map.of("gpus", "RTX 4090 (sm 8.9)", "device", "cuda:0"),
                stamp).value(), "another GPU architecture");
        assertEquals(first.value(), ModelSetup.fingerprint(files, facts, stamp).value(), "back to the original");

        Map<String, Path> missing = new TreeMap<>(files);
        missing.put("nvrtc", slm.resolve("no-such-lib.so"));
        assertNotEquals(first.value(), ModelSetup.fingerprint(missing, facts, stamp).value(), "a missing file counts");
    }

    @Test
    void aStampRecordWithMatchingSizeAndMtimeIsTrustedWithoutRehashing() throws IOException {
        Path lib = slm.resolve("libcenda_kernels.so");
        touch(lib, "engine");
        Map<String, Path> files = Map.of("engine_lib", lib);
        ModelSetup.Fingerprint f = ModelSetup.fingerprint(files, Map.of(), new Properties());
        String record = f.records().get("engine_lib");
        assertTrue(record.endsWith("|" + ModelSetup.sha256(lib)), record);

        // A record whose path, size and mtime match is reused as is: that is what keeps an unchanged launch cheap.
        Properties forged = new Properties();
        String id = record.substring(0, record.lastIndexOf('|') + 1);
        forged.setProperty("file.engine_lib", id + "0000");
        assertEquals("0000", ModelSetup.fingerprint(files, Map.of(), forged).parts().get("engine_lib"));
    }
}
