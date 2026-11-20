package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.TerrainModelRef;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-world terrain models (review-python HIGH-1): what new worlds record, which model a saved world runs, and that
 * the terrain pipeline sees it through {@link TGMPipe#modelPath()}.
 */
class TerrainModelsTest {

    @TempDir
    Path slm;

    @AfterEach
    void clear() {
        System.clearProperty("stonebreak.tgmpipe.repoDir");
        System.clearProperty("stonebreak.tgmpipe.model");
        System.clearProperty("stonebreak.tgmpipe.forceModel");
        TGMPipe.getInstance().useDefaultModel();
    }

    private void model(String rel, String contentId) throws IOException {
        Path dir = slm.resolve(rel);
        Files.createDirectories(dir.resolve("native"));
        Files.writeString(dir.resolve("native/manifest.json"), "{}");
        if (contentId != null) {
            Files.writeString(dir.resolve("release.json"), "{\"schema\": 1, \"content_id\": \"" + contentId + "\"}");
        }
    }

    @Test
    void newWorldsRecordTheDefaultReleaseWithItsContentId() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model(TGMPipe.DEFAULT_MODEL, "aaa111");
        assertEquals(new TerrainModelRef(TGMPipe.DEFAULT_MODEL, "aaa111"), TerrainModels.current());

        System.setProperty("stonebreak.tgmpipe.model", "checkpoints/exp2/world_exp2b");
        model("checkpoints/exp2/world_exp2b", null);
        assertEquals(new TerrainModelRef("checkpoints/exp2/world_exp2b", ""), TerrainModels.current(),
                "a dev folder: the path is its identity");
        System.setProperty("stonebreak.tgmpipe.model", slm.resolve("checkpoints/exp2/world_exp2b").toString());
        assertEquals("checkpoints/exp2/world_exp2b", TerrainModels.current().path(), "absolute inside the checkout: relative");
    }

    @Test
    void aSavedWorldRunsItsOwnModelWhenItIsInstalled() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model(TGMPipe.DEFAULT_MODEL, "new222");
        model("checkpoints/exp2-old", "old111");

        TerrainModels.Choice old = TerrainModels.resolve(new TerrainModelRef("checkpoints/exp2-old", "old111"));
        assertEquals("checkpoints/exp2-old", old.path());
        assertTrue(old.matches());

        // A release moved / renamed: found again by its content id.
        TerrainModels.Choice moved = TerrainModels.resolve(new TerrainModelRef("checkpoints/gone-r0", "old111"));
        assertEquals("checkpoints/exp2-old", moved.path());
        assertTrue(moved.matches());

        // The default's own world: unchanged.
        assertEquals(TGMPipe.DEFAULT_MODEL, TerrainModels.resolve(TerrainModels.current()).path());
    }

    @Test
    void aMissingOrRebuiltModelFallsBackToTheDefaultWithANotice() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model(TGMPipe.DEFAULT_MODEL, "new222");

        TerrainModels.Choice gone = TerrainModels.resolve(new TerrainModelRef("checkpoints/exp2-r0", "zzz"));
        assertEquals(TGMPipe.DEFAULT_MODEL, gone.path());
        assertFalse(gone.matches());
        assertNotNull(gone.notice());
        assertTrue(gone.notice().contains("exp2-r0") && gone.notice().contains("unexplored terrain")
                && gone.notice().contains("Install checkpoints/exp2-r0"), gone.notice());

        // A different release rebuilt at the world's path is not the world's model.
        model("checkpoints/exp2-r9", "other");
        TerrainModels.Choice rebuilt = TerrainModels.resolve(new TerrainModelRef("checkpoints/exp2-r9", "mine"));
        assertEquals(TGMPipe.DEFAULT_MODEL, rebuilt.path());
        assertFalse(rebuilt.matches());
    }

    @Test
    void worldsSavedWithoutAModelOrWithARetiredOneRunTheDefaultWithAClearWarning() throws IOException {
        // v4 removed 2026-11-19 (user decision): a save without "terrainModel" (= v4, TerrainModelRef.LEGACY) or one
        // naming an EXP-1 folder runs the default model with FX1's rule-4 notice - even if an old copy of the folder
        // is still on disk. The world keeps its honest record (LEGACY), so the notice repeats on every load.
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model(TGMPipe.DEFAULT_MODEL, "rel123");
        model("checkpoints/v4", null);
        model("checkpoints/v3", null);
        for (TerrainModelRef old : new TerrainModelRef[]{TerrainModelRef.LEGACY, TerrainModelRef.parse(null, null),
                new TerrainModelRef("checkpoints/v4/", ""), new TerrainModelRef("checkpoints/v3", "")}) {
            TerrainModels.Choice c = TerrainModels.resolve(old);
            assertEquals(TGMPipe.DEFAULT_MODEL, c.path(), old.toString());
            assertFalse(c.matches());
            assertNotNull(c.notice());
            assertTrue(c.notice().contains("retired") && c.notice().contains("unexplored terrain")
                    && c.notice().contains("removed from the game") && !c.notice().contains("Install "), c.notice());
        }
        assertEquals(new TerrainModelRef(TGMPipe.DEFAULT_MODEL, "rel123"), TerrainModels.current(), "new worlds: EXP-2");
    }

    @Test
    void aCheckoutWithoutTheReleaseNeverFallsBackToAnotherModel() throws IOException {
        // review-java finding 1 used to fall back to v4 here. Now: new worlds record the release they are meant to
        // run, and the service refuses to start with a message naming it (TerrainModels.installProblem).
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model("checkpoints/v4", null);
        assertEquals(new TerrainModelRef(TGMPipe.DEFAULT_MODEL, ""), TerrainModels.current());
        assertNotNull(TerrainModels.installProblem(TerrainModels.defaultModelPath()));
    }

    @Test
    void neitherModelInstalledRecordsTheReleasePathWhichAnyLaterReleaseThereServes() throws IOException {
        // No model at all: the service cannot start, so no terrain was generated; the world names the release folder
        // (no content id) and runs whatever release is installed there later.
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        TerrainModelRef made = TerrainModels.current();
        assertEquals(new TerrainModelRef(TGMPipe.DEFAULT_MODEL, ""), made);
        TerrainModels.Choice none = TerrainModels.resolve(made);
        assertFalse(none.matches(), "not installed");
        assertNotNull(none.notice());
        model(TGMPipe.DEFAULT_MODEL, "rel123");
        assertTrue(TerrainModels.resolve(made).matches());
    }

    @Test
    void forceModelRunsTheFlagModelForSavedWorldsToo() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model(TGMPipe.DEFAULT_MODEL, "new222");
        model("checkpoints/exp2-old", "old111");
        model("checkpoints/exp2/world_exp2e", null);
        TerrainModelRef saved = new TerrainModelRef("checkpoints/exp2-old", "old111");
        System.setProperty("stonebreak.tgmpipe.model", "checkpoints/exp2/world_exp2e");
        assertEquals("checkpoints/exp2-old", TerrainModels.resolve(saved).path(),
                "without forceModel the flag only sets the model of NEW worlds");
        System.setProperty("stonebreak.tgmpipe.forceModel", "true");
        TerrainModels.Choice forced = TerrainModels.resolve(saved);
        assertEquals("checkpoints/exp2/world_exp2e", forced.path());
        assertFalse(forced.matches());
        assertNotNull(forced.notice());
        TerrainModels.Choice same = TerrainModels.resolve(new TerrainModelRef("checkpoints/exp2/world_exp2e", ""));
        assertTrue(same.matches());
        assertNull(same.notice());
    }

    @Test
    void theWorldModelIsWhatBothBackendsLoad() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model(TGMPipe.DEFAULT_MODEL, "new222");
        model("checkpoints/exp2-old", "old111");
        TGMPipe pipe = TGMPipe.getInstance();
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());

        pipe.useWorldModel(new TerrainModelRef("checkpoints/exp2-old", "old111"));
        assertEquals("checkpoints/exp2-old", TGMPipe.modelPath(), "the TGMPipe child's command line");
        assertEquals(slm.resolve("checkpoints/exp2-old").resolve("native"), NativeTerrainLauncher.nativeDir(),
                "the native pipeline's release export");
        assertTrue(TGMPipe.generatorLabel().endsWith("exp2-old"));

        pipe.useWorldModel(TerrainModelRef.LEGACY);   // a pre-2026-11-19 save: v4 is retired -> the default
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());

        pipe.useDefaultModel();   // the terrain mapper: new worlds
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());
    }
}
