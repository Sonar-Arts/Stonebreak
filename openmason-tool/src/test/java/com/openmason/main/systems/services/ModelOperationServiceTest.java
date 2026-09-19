package com.openmason.main.systems.services;

import com.openmason.main.systems.stateHandling.ModelState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless (no viewport, no dialogs) save/open routing of the model service. */
class ModelOperationServiceTest {

    @TempDir
    Path tmp;

    private static ModelOperationService headless(ModelState state) {
        return new ModelOperationService(state, new StatusService(), null);
    }

    @Test
    void saveModelToPathWritesAndAdoptsThePath() throws Exception {
        ModelState state = new ModelState();
        ModelOperationService ops = headless(state);
        ops.newModel();
        assertTrue(state.hasUnsavedChanges());
        assertFalse(state.hasOMOFile());

        Path target = tmp.resolve("cube.omo");
        assertTrue(ops.saveModelToPath(target.toString()));
        assertTrue(Files.size(target) > 0);
        assertFalse(state.hasUnsavedChanges());
        assertEquals(target.toString(), state.getCurrentOMOFilePath());
        assertEquals(ModelState.ModelSource.OMO_FILE, state.getModelSource());
    }

    @Test
    void importedAssetCopyForgetsItsTempPathSoSaveReroutesToSaveAs() throws Exception {
        // Produce real .omo bytes from a blank cube.
        ModelState seedState = new ModelState();
        ModelOperationService seed = headless(seedState);
        seed.newModel();
        Path seedFile = tmp.resolve("seed.omo");
        assertTrue(seed.saveModelToPath(seedFile.toString()));
        byte[] bytes = Files.readAllBytes(seedFile);

        ModelState state = new ModelState();
        ModelOperationService ops = headless(state);
        var result = ops.openExtractedAssetModel(bytes, "Seed copy", "stonebreak:seed");
        assertTrue(result.opened(), result.message());

        assertEquals(ModelState.ModelSource.IMPORTED_ASSET, state.getModelSource());
        assertFalse(state.hasOMOFile(), "an extracted copy has no backing .omo");
        assertTrue(state.hasUnsavedChanges());
        assertNotNull(ops.getCurrentEditableModel());
        assertNull(ops.getCurrentEditableModel().getFilePath(),
                "the staging temp path must not survive — it is deleted right after the load");

        // Save must NOT write anywhere (no dialog service → Save As is a no-op here).
        ops.saveModel();
        assertFalse(state.hasOMOFile());
        assertTrue(state.hasUnsavedChanges());
    }

    @Test
    void saveWithoutPathIsRefused() {
        ModelState state = new ModelState();
        ModelOperationService ops = headless(state);
        ops.newModel();
        assertFalse(ops.saveModelToPath(null));
        assertFalse(ops.saveModelToPath(" "));
        assertFalse(headless(new ModelState()).saveModelToPath(tmp.resolve("none.omo").toString()),
                "no model loaded → nothing to save");
    }
}
