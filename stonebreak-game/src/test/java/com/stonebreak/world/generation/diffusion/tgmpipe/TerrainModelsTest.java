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
 * both terrain backends see it through {@link TGMPipe#modelPath()}.
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
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("planner.pt"), "w");
        if (contentId != null) {
            Files.writeString(dir.resolve("release.json"), "{\"schema\": 1, \"content_id\": \"" + contentId + "\"}");
        }
    }

    @Test
    void newWorldsRecordTheDefaultReleaseWithItsContentId() throws IOException {
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model(TGMPipe.DEFAULT_MODEL, "aaa111");
        model("checkpoints/v4", null);
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
        model("checkpoints/v4", null);
        model("checkpoints/exp2-old", "old111");

        TerrainModels.Choice v4 = TerrainModels.resolve(TerrainModelRef.LEGACY);
        assertEquals("checkpoints/v4", v4.path());
        assertTrue(v4.matches());
        assertNull(v4.notice());

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

        TerrainModels.Choice v4 = TerrainModels.resolve(TerrainModelRef.LEGACY);   // v4 not installed (a player build)
        assertEquals(TGMPipe.DEFAULT_MODEL, v4.path());
        assertFalse(v4.matches());
        assertNotNull(v4.notice());
        assertTrue(v4.notice().contains("v4") && v4.notice().contains("unexplored terrain"), v4.notice());

        // A different release rebuilt at the world's path is not the world's model.
        model("checkpoints/exp2-r9", "other");
        TerrainModels.Choice rebuilt = TerrainModels.resolve(new TerrainModelRef("checkpoints/exp2-r9", "mine"));
        assertEquals(TGMPipe.DEFAULT_MODEL, rebuilt.path());
        assertFalse(rebuilt.matches());
    }

    @Test
    void aCheckoutWithoutTheReleaseRecordsTheV4ItRunsAndKeepsItWhenTheReleaseArrives() throws IOException {
        // review-java finding 1: the gitignored release folder missing -> the v4 fallback. A world made then is a v4
        // world and stays one; it must not switch to EXP-2 once the release is installed.
        System.setProperty("stonebreak.tgmpipe.repoDir", slm.toString());
        model("checkpoints/v4", null);
        TerrainModelRef made = TerrainModels.current();
        assertEquals(TerrainModelRef.LEGACY, made, "the fallback that actually runs is what the world records");
        model(TGMPipe.DEFAULT_MODEL, "rel123");
        assertEquals(new TerrainModelRef(TGMPipe.DEFAULT_MODEL, "rel123"), TerrainModels.current(), "new worlds: EXP-2");
        TerrainModels.Choice old = TerrainModels.resolve(made);
        assertEquals("checkpoints/v4", old.path());
        assertTrue(old.matches());
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
        model("checkpoints/v4", null);
        model("checkpoints/exp2/world_exp2e", null);
        System.setProperty("stonebreak.tgmpipe.model", "checkpoints/exp2/world_exp2e");
        assertEquals("checkpoints/v4", TerrainModels.resolve(TerrainModelRef.LEGACY).path(),
                "without forceModel the flag only sets the model of NEW worlds");
        System.setProperty("stonebreak.tgmpipe.forceModel", "true");
        TerrainModels.Choice forced = TerrainModels.resolve(TerrainModelRef.LEGACY);
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
        model("checkpoints/v4", null);
        TGMPipe pipe = TGMPipe.getInstance();
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());

        pipe.useWorldModel(TerrainModelRef.LEGACY);
        assertEquals("checkpoints/v4", TGMPipe.modelPath(), "the TGMPipe child's command line");
        assertEquals(slm.resolve("checkpoints/v4").resolve("native"), NativeTerrainLauncher.nativeDir(),
                "the native pipeline's release export");
        assertTrue(TGMPipe.generatorLabel().endsWith("v4"));

        pipe.useDefaultModel();   // the terrain mapper: new worlds
        assertEquals(TGMPipe.DEFAULT_MODEL, TGMPipe.modelPath());
    }
}
