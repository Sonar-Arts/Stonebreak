package com.openmason.main.systems.menus.panes.projectBrowser;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetType;
import com.openmason.main.systems.menus.panes.projectBrowser.AssetEditorDetacher;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import com.openmason.main.systems.menus.textureCreator.TextureDocument;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the Project Browser's delete flow
 * ({@link ProjectBrowserController#deleteAsset}) and the editor-detach rules
 * ({@link AssetEditorDetacher}). Hand-rolled fakes — no Mockito in tool tests, no GL.
 */
class ProjectBrowserControllerDeleteTest {

    @TempDir
    Path temp;

    private CapturingStatusService statusService;

    /** Fake project rooted at the temp dir's project.omp; the real no-arg ctor is light and headless-safe. */
    private final class FakeProjectService extends ProjectService {
        private final String ompPath = temp.resolve("project.omp").toString();

        @Override
        public boolean hasCurrentProject() {
            return true;
        }

        @Override
        public String getCurrentProjectPath() {
            return ompPath;
        }

        @Override
        public String getCurrentProjectName() {
            return "project";
        }
    }

    /** Captures the last status message. */
    private static final class CapturingStatusService extends StatusService {
        String lastMessage;

        @Override
        public void updateStatus(String message) {
            lastMessage = message;
        }
    }

    private ProjectBrowserController newController() {
        statusService = new CapturingStatusService();
        // (null, null, null) ctor only stores refs and builds factories/serializers — headless-safe.
        return new ProjectBrowserController(new FakeProjectService(),
                new ModelOperationService(null, null, null), statusService);
    }

    private AssetEntry writeEntry(String fileName, AssetType type) throws IOException {
        Path file = temp.resolve(fileName);
        Files.writeString(file, "stub");
        return new AssetEntry(fileName, file, type);
    }

    // ---- Controller delete flow ----

    @Test
    void deleteOmoRemovesFileReportsStatusClearsSelectionAndRescans() throws IOException {
        ProjectBrowserController controller = newController();
        AssetEntry entry = writeEntry("sword.omo", AssetType.OMO);
        assertTrue(controller.getVisibleAssets().stream().anyMatch(e -> e.pathString().equals(entry.pathString())),
                "entry should be visible before delete");

        assertTrue(controller.deleteAsset(entry), "delete of an existing .OMO should succeed");

        assertFalse(Files.exists(entry.path()), ".OMO file should be gone from disk");
        assertEquals("Deleted sword.omo (.OMO Model)", statusService.lastMessage);
        assertEquals("No asset selected", controller.getState().getSelectedAssetInfo());
        assertFalse(controller.getVisibleAssets().stream().anyMatch(e -> e.name().equals(entry.name())),
                "entry should no longer be listed after rescan");
    }

    @Test
    void deleteOmtReportsOmtTextureStatus() throws IOException {
        ProjectBrowserController controller = newController();
        AssetEntry entry = writeEntry("parchment.omt", AssetType.OMT);

        assertTrue(controller.deleteAsset(entry), "delete of an existing .OMT should succeed");

        assertFalse(Files.exists(entry.path()));
        assertEquals("Deleted parchment.omt (.OMT Texture)", statusService.lastMessage);
    }

    @Test
    void deleteAlreadyDeletedPathFailsWithReadableStatusAndNoCrash() throws IOException {
        ProjectBrowserController controller = newController();
        AssetEntry entry = writeEntry("gone.omo", AssetType.OMO);

        assertTrue(controller.deleteAsset(entry));
        assertFalse(controller.deleteAsset(entry), "second delete of a deleted path should return false, not throw");

        assertEquals("Error deleting gone.omo: file not found", statusService.lastMessage);
        List<AssetEntry> visible = controller.getVisibleAssets();
        assertFalse(visible.stream().anyMatch(e -> e.name().equals(entry.name())));
    }

    @Test
    void deletingOneAssetKeepsAnotherSelectedStatusLine() throws IOException {
        ProjectBrowserController controller = newController();
        AssetEntry a = writeEntry("a.omo", AssetType.OMO);
        AssetEntry b = writeEntry("b.omo", AssetType.OMO);
        // Select a: the controller sets the status line before the (swallowed) load error.
        controller.selectAsset(a);
        assertEquals("Selected: a.omo (.OMO Model)", controller.getState().getSelectedAssetInfo());

        assertTrue(controller.deleteAsset(b));

        assertEquals("Selected: a.omo (.OMO Model)", controller.getState().getSelectedAssetInfo(),
                "deleting b must not wipe a's selection");

        assertTrue(controller.deleteAsset(a));
        assertEquals("No asset selected", controller.getState().getSelectedAssetInfo(),
                "deleting the selected entry clears the status line");
    }

    // ---- Editor-detach rules (AssetEditorDetacher) ----

    @Test
    void pathsEqualComparesNormalizedAndRejectsBlank() throws IOException {
        Path file = writeEntry("backing.omo", AssetType.OMO).path();
        assertTrue(AssetEditorDetacher.pathsEqual(file, file.toString()));
        assertTrue(AssetEditorDetacher.pathsEqual(file, temp.resolve("./backing.omo").toString()),
                "normalized comparison: same file, different resolution");
        assertFalse(AssetEditorDetacher.pathsEqual(file, temp.resolve("other.omo").toString()));
        assertFalse(AssetEditorDetacher.pathsEqual(file, null), "blank backing -> false (no detach)");
        assertFalse(AssetEditorDetacher.pathsEqual(file, ""), "blank backing -> false (no detach)");
        assertFalse(AssetEditorDetacher.pathsEqual(file, "\u0000bad"),
                "invalid backing path -> false, no throw");
    }

    @Test
    void omoDetachAfterSuccessfulDeleteBlanksMatchingPathOnly() throws IOException {
        AssetEntry entry = writeEntry("backing.omo", AssetType.OMO);
        ModelState modelState = new ModelState();
        modelState.setCurrentOMOFilePath(entry.pathString());
        modelState.setModelLoaded(true);

        new AssetEditorDetacher().afterDelete(entry, true, modelState, null);

        assertFalse(modelState.hasOMOFile(), "blank path -> hasOMOFile() false -> saveModel() routes to Save As");
        assertTrue(modelState.hasUnsavedChanges(),
                "in-memory copy is the only copy left; flagged unsaved so quitting/switching prompts to save");
        assertTrue(modelState.isModelLoaded(), "in-memory copy untouched");
    }

    @Test
    void omoDetachSkipsNonMatchingPath() throws IOException {
        AssetEntry entry = writeEntry("other.omo", AssetType.OMO);
        ModelState modelState = new ModelState();
        modelState.setCurrentOMOFilePath(temp.resolve("kept.omo").toString());

        new AssetEditorDetacher().afterDelete(entry, true, modelState, null);

        assertTrue(modelState.hasOMOFile(), "editor on a different file stays attached");
        assertFalse(modelState.hasUnsavedChanges(), "no detach -> no unsaved flag");
    }

    /** A texture editor with one untitled document per path, each attached to that file. */
    private static TextureCreatorController texturesOn(String... paths) {
        TextureCreatorController textures = new TextureCreatorController(new TextureCreatorState());
        for (String path : paths) {
            textures.newTexture(new TextureCreatorState.CanvasSize(4, 4));
            textures.getState().setCurrentFilePath(path);
            textures.getState().setIsProjectFile(true);
        }
        return textures;
    }

    @Test
    void omtDetachAfterSuccessfulDeleteBlanksPathKeepsDirty() throws IOException {
        AssetEntry entry = writeEntry("backing.omt", AssetType.OMT);
        TextureCreatorController textures = texturesOn(entry.pathString());
        TextureDocument doc = textures.getActiveDocument();

        new AssetEditorDetacher().afterDelete(entry, true, null, textures);

        assertNull(doc.getFilePath(), "blank path -> saveProject routes to Save Project As");
        assertFalse(doc.isProjectFile());
        assertTrue(doc.hasUnsavedChanges(), "in-memory copy is the only one left: flagged unsaved");
    }

    @Test
    void omtDetachReachesEveryTabOnTheFileNotJustTheActiveOne() throws IOException {
        AssetEntry entry = writeEntry("backing.omt", AssetType.OMT);
        TextureCreatorController textures = texturesOn(entry.pathString(), temp.resolve("kept.omt").toString());
        TextureDocument onDeleted = textures.getDocuments().get(0);
        TextureDocument kept = textures.getDocuments().get(1);
        assertSame(kept, textures.getActiveDocument(), "the deleted file's tab is in the background");

        new AssetEditorDetacher().afterDelete(entry, true, null, textures);

        assertNull(onDeleted.getFilePath());
        assertTrue(onDeleted.hasUnsavedChanges());
        assertTrue(kept.isProjectFile(), "a tab on another file stays attached");
        assertFalse(kept.hasUnsavedChanges());
    }

    @Test
    void omtDetachSkipsNonMatchingPath() throws IOException {
        AssetEntry entry = writeEntry("other.omt", AssetType.OMT);
        TextureCreatorController textures = texturesOn(temp.resolve("kept.omt").toString());

        new AssetEditorDetacher().afterDelete(entry, true, null, textures);

        assertTrue(textures.getState().hasFilePath(), "editor on a different file stays attached");
        assertFalse(textures.getState().hasUnsavedChanges(), "no detach -> no unsaved flag");
    }

    @Test
    void failedDeleteLeavesEditorsAttached() throws IOException {
        AssetEntry entry = writeEntry("backing.omo", AssetType.OMO);
        ModelState modelState = new ModelState();
        modelState.setCurrentOMOFilePath(entry.pathString());
        TextureCreatorController textures = texturesOn(entry.pathString());

        new AssetEditorDetacher().afterDelete(entry, false, modelState, textures);

        assertTrue(modelState.hasOMOFile(), "failed delete leaves the model editor attached");
        assertTrue(textures.getState().hasFilePath(), "failed delete leaves the texture editor attached");
        assertFalse(modelState.hasUnsavedChanges(), "failed delete flags nothing");
        assertFalse(textures.getState().hasUnsavedChanges(), "failed delete flags nothing");
    }
}
