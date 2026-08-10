package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelScene;
import com.openmason.main.systems.layout.CenterTab;
import com.openmason.main.systems.layout.LayoutRebuildDecision;
import com.openmason.main.systems.scene.dnd.SceneDropResolver;
import com.openmason.main.systems.scene.dnd.ScenePayloads;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scene Viewer UI logic that can be pinned without a UI: selection semantics, the layout
 * migration matrix, tab resolution, and drop placement.
 */
class SceneUiLogicTest {

    // ------------------------------------------------------------ selection

    private static ModelScene sceneOf(int count) {
        ModelBounds bounds = new ModelBounds(new Vector3f(-0.5f, -0.5f, -0.5f),
                new Vector3f(0.5f, 0.5f, 0.5f), new Vector3f(), new Vector3f(1, 1, 1));
        ModelHandle handle = new ModelHandle("cube", null, "cube", null, new int[0], bounds);
        ModelScene scene = new ModelScene();
        for (int i = 0; i < count; i++) {
            scene.add(handle, "i" + i);
        }
        return scene;
    }

    @Test
    @DisplayName("a plain click replaces the selection")
    void plainClickReplaces() {
        List<ModelInstance> all = sceneOf(3).instances();
        SceneSelectionState selection = new SceneSelectionState();

        selection.select(all.get(0).id());
        selection.select(all.get(2).id());

        assertEquals(1, selection.size());
        assertTrue(selection.isSelected(all.get(2).id()));
        assertEquals(all.get(2).id(), selection.primary());
    }

    @Test
    @DisplayName("ctrl-click toggles one entry without disturbing the rest")
    void ctrlClickToggles() {
        List<ModelInstance> all = sceneOf(3).instances();
        SceneSelectionState selection = new SceneSelectionState();

        selection.select(all.get(0).id());
        selection.toggle(all.get(1).id());
        assertEquals(2, selection.size());

        selection.toggle(all.get(1).id());
        assertEquals(1, selection.size());
        assertTrue(selection.isSelected(all.get(0).id()));
    }

    @Test
    @DisplayName("shift-click selects the inclusive range in scene order, in either direction")
    void shiftClickSelectsRange() {
        List<ModelInstance> all = sceneOf(5).instances();
        SceneSelectionState selection = new SceneSelectionState();

        selection.select(all.get(1).id());
        selection.selectRangeTo(all.get(3).id(), all);
        assertEquals(3, selection.size());
        assertTrue(selection.isSelected(all.get(2).id()));
        assertFalse(selection.isSelected(all.get(4).id()));

        // Anchor stays at index 1, so shrinking back works from the same end.
        selection.selectRangeTo(all.get(0).id(), all);
        assertEquals(2, selection.size());
        assertTrue(selection.isSelected(all.get(0).id()));
        assertFalse(selection.isSelected(all.get(3).id()));
    }

    @Test
    @DisplayName("shift-click with no anchor behaves as a plain click")
    void shiftClickWithoutAnchor() {
        List<ModelInstance> all = sceneOf(3).instances();
        SceneSelectionState selection = new SceneSelectionState();

        selection.selectRangeTo(all.get(2).id(), all);

        assertEquals(1, selection.size());
        assertTrue(selection.isSelected(all.get(2).id()));
    }

    @Test
    @DisplayName("removing the selected primary re-picks another")
    void removingPrimaryRepicks() {
        List<ModelInstance> all = sceneOf(3).instances();
        SceneSelectionState selection = new SceneSelectionState();

        selection.select(all.get(0).id());
        selection.toggle(all.get(1).id());
        selection.remove(all.get(0).id());

        assertEquals(1, selection.size());
        assertEquals(all.get(1).id(), selection.primary());

        selection.clear();
        assertTrue(selection.isEmpty());
        assertNull(selection.primary());
    }

    @Test
    @DisplayName("resolve returns the selected instances in scene order")
    void resolveKeepsSceneOrder() {
        List<ModelInstance> all = sceneOf(4).instances();
        SceneSelectionState selection = new SceneSelectionState();

        selection.select(all.get(3).id());
        selection.toggle(all.get(1).id());

        List<ModelInstance> resolved = selection.resolve(all);

        assertEquals(List.of(all.get(1), all.get(3)), resolved);
    }

    // --------------------------------------------------------------- layout

    @Test
    @DisplayName("the layout rebuild matrix: exactly one forced rebuild per upgrade")
    void layoutRebuildMatrix() {
        int current = 2;

        // Brand new install: nothing saved.
        assertTrue(LayoutRebuildDecision.shouldRebuild(false, 0, current, false));
        // Pre-rename user: saved layout, never versioned.
        assertTrue(LayoutRebuildDecision.shouldRebuild(true, 0, current, false));
        // Already migrated to v1: needs the Scene Viewer slot, so rebuild once.
        assertTrue(LayoutRebuildDecision.shouldRebuild(true, 1, current, false));
        // Already on the current version: leave the user's arrangement alone.
        assertFalse(LayoutRebuildDecision.shouldRebuild(true, current, current, false));
        // Explicit reset always wins.
        assertTrue(LayoutRebuildDecision.shouldRebuild(true, current, current, true));
    }

    @Test
    @DisplayName("a project's recorded tab wins; only an unrecorded one takes the default")
    void centerTabResolution() {
        // An upgrading user has no recorded value and must not be moved off the editor.
        assertEquals(CenterTab.MODEL_EDITOR, CenterTab.resolve(null, CenterTab.MODEL_EDITOR));
        // A new project records the Scene Viewer.
        assertEquals(CenterTab.SCENE_VIEWER, CenterTab.resolve("SCENE_VIEWER", CenterTab.MODEL_EDITOR));
        // An explicit editor choice survives a forced layout rebuild.
        assertEquals(CenterTab.MODEL_EDITOR, CenterTab.resolve("MODEL_EDITOR", CenterTab.SCENE_VIEWER));
        // Garbage falls back rather than failing the project load.
        assertEquals(CenterTab.MODEL_EDITOR, CenterTab.resolve("nonsense", CenterTab.SCENE_VIEWER));
        assertEquals(CenterTab.SCENE_VIEWER, CenterTab.resolve("  ", CenterTab.SCENE_VIEWER));
    }

    @Test
    @DisplayName("each tab names a real window title")
    void tabsNameWindows() {
        assertEquals("Scene Viewer", CenterTab.SCENE_VIEWER.windowTitle());
        assertEquals("Model Editor", CenterTab.MODEL_EDITOR.windowTitle());
    }

    // ----------------------------------------------------------------- drop

    @Test
    @DisplayName("the drag payload type fits ImGui's 32-byte limit and accepts only .omo")
    void payloadRules() {
        assertTrue(ScenePayloads.OMO_ASSET.length() < 32);
        assertTrue(ScenePayloads.isPlaceable("/proj/Well.omo"));
        assertTrue(ScenePayloads.isPlaceable("/proj/Well.OMO"));
        assertFalse(ScenePayloads.isPlaceable("/proj/Well.omt"));
        assertFalse(ScenePayloads.isPlaceable(null));
    }

    /** Camera at (0,10,10) looking at the origin, 90-degree FOV, square viewport. */
    private static Matrix4f view() {
        return new Matrix4f().lookAt(new Vector3f(0, 10, 10), new Vector3f(0, 0, 0), new Vector3f(0, 1, 0));
    }

    private static Matrix4f projection() {
        return new Matrix4f().perspective((float) Math.toRadians(90), 1.0f, 0.1f, 1000.0f);
    }

    @Test
    @DisplayName("a drop at the viewport centre lands where the camera is aimed")
    void dropAtCentreLandsAtTarget() {
        Vector3f drop = SceneDropResolver.resolve(400, 400, 800, 800,
                view(), projection(), 0.0f, 10.0f);

        assertEquals(0.0f, drop.x, 1e-2f);
        assertEquals(0.0f, drop.y, 1e-3f);
        assertEquals(0.0f, drop.z, 1e-2f);
    }

    @Test
    @DisplayName("a drop right of centre lands to the right on the ground plane")
    void dropOffCentreShiftsCorrectly() {
        Vector3f drop = SceneDropResolver.resolve(600, 400, 800, 800,
                view(), projection(), 0.0f, 10.0f);

        assertTrue(drop.x > 0.5f, "cursor right of centre must land at positive X");
        assertEquals(0.0f, drop.y, 1e-3f);
    }

    @Test
    @DisplayName("aiming at the sky falls back to a point in front of the camera at ground level")
    void skywardAimFallsBack() {
        // Looking upward the ray never meets the ground plane ahead of the camera.
        Matrix4f upward = new Matrix4f().lookAt(
                new Vector3f(0, 1, 10), new Vector3f(0, 40, 0), new Vector3f(0, 1, 0));

        Vector3f drop = SceneDropResolver.resolve(400, 400, 800, 800,
                upward, projection(), 0.0f, 12.0f);

        assertEquals(0.0f, drop.y, 1e-3f, "the fallback still lands on the ground plane");
    }
}
