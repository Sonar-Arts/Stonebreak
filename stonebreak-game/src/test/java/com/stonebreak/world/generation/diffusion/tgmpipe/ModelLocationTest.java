package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TGMPipeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which model folder the game loads ({@link TGMPipe#modelPath}), when it refuses one ({@link TerrainModels#installProblem}) and when the warm-up must run again ({@link ModelSetup#kernelHash}). */
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
        touch(slm.resolve("checkpoints/v4/planner.pt"), "an old v4 copy");
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath(), "no release in this checkout: still the release (v4 retired)");

        touch(slm.resolve(TGMPipe.DEFAULT_MODEL).resolve("planner.pt"), "exp2");
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
        String pointer = "version https://git-lfs.github.com/spec/v1\n"
                + "oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 256181649\n";
        touch(dir.resolve("planner.pt"), "real weights");
        touch(dir.resolve("detail.pt"), pointer);
        touch(dir.resolve("relief.pt"), pointer);
        touch(dir.resolve("release.json"), "{\"content_id\": \"abc\"}");
        assertTrue(TerrainModels.isLfsPointer(dir.resolve("detail.pt")));
        assertFalse(TerrainModels.isLfsPointer(dir.resolve("planner.pt")));
        String problem = TerrainModels.installProblem(TGMPipe.DEFAULT_MODEL);
        assertNotNull(problem);
        assertTrue(problem.contains("Git LFS pointer") && problem.contains("detail.pt, relief.pt")
                && problem.contains("git lfs pull"), problem);
        assertThrows(TGMPipeException.class, () -> TerrainModels.requireInstalled(TGMPipe.DEFAULT_MODEL));

        touch(dir.resolve("detail.pt"), "real weights");
        touch(dir.resolve("relief.pt"), "real weights");
        assertNull(TerrainModels.installProblem(TGMPipe.DEFAULT_MODEL), "pulled: loadable");
    }

    @Test
    void retiredModelsAreRefusedEvenWhenAnOldCopyIsOnDisk() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        touch(slm.resolve("checkpoints/v4/planner.pt"), "an old v4 copy");
        String problem = TerrainModels.installProblem("checkpoints/v4");
        assertNotNull(problem);
        assertTrue(problem.contains("retired"), problem);
    }

    @Test
    void kernelHashFollowsNewSourcesAndTheReleaseManifestButNotCaches() throws IOException {
        touch(slm.resolve("terrain_slm/kernels/csrc/conv.cu"), "a");
        touch(slm.resolve("terrain_slm/kernels/cuda_configs.json"), "{}");
        touch(slm.resolve("terrain_slm/tgmpipe/warmup.py"), "w");
        String model = "checkpoints/exp2-r1";
        String base = ModelSetup.kernelHash(slm, model);
        assertEquals(base, ModelSetup.kernelHash(slm, model), "stable");

        touch(slm.resolve("terrain_slm/kernels/__pycache__/x.cpython-312.pyc"), "cache");
        touch(slm.resolve("terrain_slm/kernels/notes.md"), "not a source");
        assertEquals(base, ModelSetup.kernelHash(slm, model), "caches and docs do not count");

        touch(slm.resolve("terrain_slm/kernels/csrc/fp8_mma.hpp"), "new header");
        String withHeader = ModelSetup.kernelHash(slm, model);
        assertNotEquals(base, withHeader, "a new csrc header another change adds is hashed");

        touch(slm.resolve("terrain_slm/kernels/csrc/detail2.cu"), "new kernel");
        String withKernel = ModelSetup.kernelHash(slm, model);
        assertNotEquals(withHeader, withKernel, "a new csrc kernel is hashed");

        touch(slm.resolve(model).resolve("release.json"), "{\"content_id\": \"a\"}");
        String released = ModelSetup.kernelHash(slm, model);
        assertNotEquals(withKernel, released, "the release manifest counts");
        touch(slm.resolve(model).resolve("release.json"), "{\"content_id\": \"b\"}");
        assertNotEquals(released, ModelSetup.kernelHash(slm, model), "a release rebuilt at the same path warms up again");

        assertNotEquals(ModelSetup.kernelHash(slm, model), ModelSetup.kernelHash(slm, "checkpoints/exp2/world_exp2f"),
                "the model folder counts");
    }
}
