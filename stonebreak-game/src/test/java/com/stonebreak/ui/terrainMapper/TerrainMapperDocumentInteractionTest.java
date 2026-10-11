package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;

/**
 * The terrain mapper document's pointer (#301) runs the screen's mouse rules: view modes, field focus, a map
 * press that barely moves sets the spawn while a drag pans, the spawn buttons, the wheel zooms about the
 * pointer on the map only, Create validates; and the document never takes the keyboard (the screen keeps it).
 */
@Tag("regression")
class TerrainMapperDocumentInteractionTest {

    private static SbuiArchive shipped;

    @BeforeAll
    static void load() throws Exception {
        ChatDocumentGateTest.requireLua();
        shipped = GameUiDocuments.readScreen(TerrainMapperScreen.DOCUMENT_ID);
    }

    @AfterEach
    void release() {
        UiWallClock.release();
    }

    private static float[] centre(String name) {
        float[] r = TerrainMapperFixtures.rects(1920, 1080, null).get(name);
        return new float[]{r[0] + r[2] / 2f, r[1] + r[3] / 2f};
    }

    private static void press(DocumentStage stage, float x, float y) {
        stage.view.input().pointerMove(x, y);
        stage.view.input().pointerDown(x, y, 0, 0);
        stage.settle();
    }

    private static void up(DocumentStage stage, float x, float y) {
        stage.view.input().pointerUp(x, y, 0, 0);
        stage.settle();
    }

    private static void click(DocumentStage stage, String name) {
        float[] c = centre(name);
        press(stage, c[0], c[1]);
        up(stage, c[0], c[1]);
    }

    @Test
    void pressesRunTheLegacyRules() throws Exception {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperStateManager s = screen.getStateManager();
        TerrainMapperFixtures.prepare(s, "default");
        try (DocumentStage stage = DocumentTerrainMapperCapture.stage(shipped, screen, 1920, 1080, 1f, 0.1)) {
            click(stage, "mode6");
            assertEquals(VisualizerKind.BIOME, s.getActiveVisualizer());
            click(stage, "seed");
            assertEquals(ActiveField.SEED, s.getActiveField());

            float[] m = centre("map");
            click(stage, "map");
            assertEquals(ActiveField.NONE, s.getActiveField(), "a map press unfocuses the fields");
            assertTrue(s.hasSpawnPoint(), "a press that barely moved sets the spawn");
            assertEquals(Math.round(s.getViewport().screenToWorldX(m[0], m[0])), s.spawnWorldX());
            click(stage, "spawn");
            assertFalse(s.hasSpawnPoint(), "Random Spawn? clears it");
            click(stage, "spawn");
            assertFalse(s.hasSpawnPoint(), "the disabled button takes the press and does nothing");

            press(stage, m[0], m[1]);
            stage.view.input().pointerMove(m[0] + 50, m[1] + 20);
            stage.settle();
            up(stage, m[0] + 50, m[1] + 20);
            assertEquals(-50f, s.getViewport().panX(), 1e-4, "a drag pans");
            assertEquals(-20f, s.getViewport().panZ(), 1e-4);
            assertFalse(s.hasSpawnPoint(), "and sets no spawn");

            stage.view.input().wheel(m[0], m[1], 0, 1, 0);
            stage.settle();
            assertEquals(1.15f, s.getViewport().zoom(), 1e-5, "the wheel zooms on the map");
            float[] side = centre("sidebar");
            stage.view.input().wheel(side[0], side[1], 0, 1, 0);
            stage.settle();
            assertEquals(1.15f, s.getViewport().zoom(), 1e-5, "and only there");

            click(stage, "create");
            assertNotNull(s.getErrorMessage(), "Create validates the (empty) name");
        } finally {
            screen.dispose();
        }
    }

    @Test
    void theDocumentLeavesTheKeyboardToTheScreen() throws Exception {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperFixtures.prepare(screen.getStateManager(), "default");
        try (DocumentStage stage = DocumentTerrainMapperCapture.stage(shipped, screen, 1920, 1080, 1f, 0.1)) {
            click(stage, "name");
            for (int key : new int[]{GLFW_KEY_TAB, GLFW_KEY_ENTER}) {
                assertFalse(stage.view.input().keyDown(key, 0, false), "key " + key + " falls through");
                stage.view.input().keyUp(key, 0);
            }
            assertFalse(stage.view.input().text('a'), "typing falls through");
        } finally {
            screen.dispose();
        }
    }

    @Test
    void actionsAreRefusedWhenTheMapperIsNotShowing() throws Exception {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperFixtures.prepare(screen.getStateManager(), "default");
        com.stonebreak.ui.fidelity.RecordingServices none = new com.stonebreak.ui.fidelity.RecordingServices();
        try (DocumentStage stage = new DocumentStage(shipped, 1920, 1080, 1f, none,
                com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER, Map.of())) {
            click(stage, "mode6");
            assertEquals(VisualizerKind.HEIGHT, screen.getStateManager().getActiveVisualizer());
        } finally {
            screen.dispose();
        }
    }
}
