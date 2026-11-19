package com.stonebreak.world.generation.diffusion.tgmpipe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Which model folder the game loads ({@link TGMPipe#modelPath}) and when the warm-up must run again ({@link ModelSetup#kernelHash}). */
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
    void defaultIsTheExp2ReleaseAndV4IsOnlyTheFallbackOrAnExplicitChoice() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        touch(slm.resolve("checkpoints/v4/planner.pt"), "v4");
        assertEquals(TGMPipe.LEGACY_MODEL, TGMPipe.modelPath(), "no release in this checkout: v4 fallback");

        touch(slm.resolve(TGMPipe.DEFAULT_MODEL).resolve("planner.pt"), "exp2");
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());

        System.setProperty("stonebreak.tgmpipe.model", "checkpoints/v4");
        assertEquals("checkpoints/v4", TGMPipe.modelPath(), "the flag still reaches v4");
        System.setProperty("stonebreak.tgmpipe.model", "checkpoints/exp2/world_exp2b");
        assertEquals("checkpoints/exp2/world_exp2b", TGMPipe.modelPath());
    }

    @Test
    void neitherFolderPresentKeepsTheDefaultSoTheErrorNamesTheRelease() {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());
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

        assertNotEquals(ModelSetup.kernelHash(slm, model), ModelSetup.kernelHash(slm, "checkpoints/v4"),
                "the model folder counts");
    }
}
