package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import com.stonebreak.world.generation.TerrainGeneratorType;
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

    private static float[] centre(TerrainMapperStateManager s, String name) {
        float[] r = TerrainMapperFixtures.rects(1920, 1080, s).get(name);
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

    private static void click(DocumentStage stage, TerrainMapperStateManager s, String name) {
        float[] c = centre(s, name);
        press(stage, c[0], c[1]);
        up(stage, c[0], c[1]);
    }

    @Test
    void pressesRunTheLegacyRules() throws Exception {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperStateManager s = screen.getStateManager();
        TerrainMapperFixtures.prepare(s, "default");
        try (DocumentStage stage = DocumentTerrainMapperCapture.stage(shipped, screen, 1920, 1080, 1f, 0.1)) {
            click(stage, s, "create");
            assertEquals(null, s.getErrorMessage(), "Create waits for a generator: the press does nothing");
            click(stage, s, "gen0");
            assertEquals(TerrainGeneratorType.STANDARD, s.getSelectedGenerator());
            assertEquals(VisualizerKind.HEIGHT, s.getActiveVisualizer(), "picking a generator shows its first mode");
            stage.host.terrainMapper().poll();
            stage.settle();
            click(stage, s, "mode6");
            assertEquals(VisualizerKind.BIOME, s.getActiveVisualizer());
            click(stage, s, "seed");
            assertEquals(ActiveField.SEED, s.getActiveField());

            float[] m = centre(s, "map");
            click(stage, s, "map");
            assertEquals(ActiveField.NONE, s.getActiveField(), "a map press unfocuses the fields");
            assertTrue(s.hasSpawnPoint(), "a press that barely moved sets the spawn");
            assertEquals(Math.round(s.getViewport().screenToWorldX(m[0], m[0])), s.spawnWorldX());
            click(stage, s, "spawn");
            assertFalse(s.hasSpawnPoint(), "Random Spawn? clears it");
            click(stage, s, "spawn");
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
            float[] side = centre(s, "sidebar");
            stage.view.input().wheel(side[0], side[1], 0, 1, 0);
            stage.settle();
            assertEquals(1.15f, s.getViewport().zoom(), 1e-5, "and only there");

            click(stage, s, "create");
            assertNotNull(s.getErrorMessage(), "Create validates the (empty) name");
        } finally {
            screen.dispose();
        }
    }

    /**
     * The EXP-2 world-options row: hidden until a generator is picked; the scale button cycles the new world's
     * terrain scale; the climate button is disabled for Standard (the press does nothing) and cycles the climate
     * layout for DaedalusTGM-Exp (picking it starts no services: they start when a preview samples); hovering an
     * option shows its tip.
     */
    @Test
    void theWorldOptionsRowCyclesScaleAndClimate() throws Exception {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperStateManager s = screen.getStateManager();
        TerrainMapperFixtures.prepare(s, "default");
        try (DocumentStage stage = DocumentTerrainMapperCapture.stage(shipped, screen, 1920, 1080, 1f, 0.1)) {
            assertTrue(stage.q("#scale-opt").isCollapsed(), "no world options before a generator is picked");
            assertTrue(stage.q("#climate-opt").isCollapsed());
            assertTrue(s.getSelectedGenerator() == null);
            assertTrue(screen.mouse().pressButton("scale:next"));
            assertEquals(com.stonebreak.config.TerrainScaleOption.DEFAULT, s.getSelectedScale(),
                "the hidden row does nothing");

            click(stage, s, "gen0");
            stage.host.terrainMapper().poll();
            stage.settle();
            assertFalse(stage.q("#scale-opt").isCollapsed(), "picking a generator shows the row");
            assertFalse(stage.q("#climate-opt").isCollapsed());
            assertTrue(stage.q("#climate-opt").hasClass("off"), "the climate layout is DaedalusTGM-Exp's");
            click(stage, s, "scale");
            assertEquals(com.stonebreak.config.TerrainScaleOption.next(com.stonebreak.config.TerrainScaleOption.DEFAULT),
                s.getSelectedScale(), "the scale button steps the scale");
            click(stage, s, "climate");
            assertEquals(com.stonebreak.world.generation.ClimateLayout.BELTS, s.getSelectedClimateLayout(),
                "the disabled climate button takes the press and does nothing");

            click(stage, s, "gen1");
            stage.host.terrainMapper().poll();
            stage.settle();
            assertEquals(TerrainGeneratorType.DIFFUSION, s.getSelectedGenerator());
            assertFalse(stage.q("#climate-opt").hasClass("off"));
            click(stage, s, "climate");
            assertEquals(com.stonebreak.world.generation.ClimateLayout.ZONES, s.getSelectedClimateLayout());
            click(stage, s, "climate");
            assertEquals(com.stonebreak.world.generation.ClimateLayout.LATITUDE, s.getSelectedClimateLayout());
            click(stage, s, "climate");
            assertEquals(com.stonebreak.world.generation.ClimateLayout.BELTS, s.getSelectedClimateLayout(),
                "it wraps");

            float[] c = centre(s, "climate");
            stage.view.input().pointerMove(c[0], c[1]);
            stage.host.terrainMapper().poll();
            stage.settle();
            assertFalse(stage.q("#opt-tip").isCollapsed(), "hovering an option shows its tip");
            assertFalse(stage.q("#climate-tip-text").isCollapsed());
            assertTrue(stage.q("#scale-tip-text").isCollapsed());
            float[] m = centre(s, "map");
            stage.view.input().pointerMove(m[0], m[1]);
            stage.settle();
            assertTrue(stage.q("#opt-tip").isCollapsed(), "and leaving it hides the tip");
        } finally {
            screen.dispose();
        }
    }

    /**
     * A 720 px window has no room for the world-options row under Standard's spawn section: it floats over the
     * map's top-left corner, where it still takes the pointer (a press on it neither pans nor sets the spawn).
     */
    @Test
    void aShortWindowFloatsTheWorldOptionsOverTheMap() throws Exception {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperStateManager s = screen.getStateManager();
        TerrainMapperFixtures.prepare(s, "standard");
        try (DocumentStage stage = DocumentTerrainMapperCapture.stage(shipped, screen, 1280, 720, 0.75f, 0.1)) {
            float[] oracle = TerrainMapperFixtures.rects(1280, 720, s).get("scale");
            assertEquals(TerrainMapperConfig.SIDEBAR_WIDTH + TerrainMapperConfig.PANEL_MARGIN, oracle[0], 1e-4,
                "the row floats over the map");
            var el = stage.q("#scale-opt").rect();
            assertEquals(oracle[0], el.x(), 1e-3);
            assertEquals(oracle[1], el.y(), 1e-3);
            float x = oracle[0] + oracle[2] / 2f;
            float y = oracle[1] + oracle[3] / 2f;
            press(stage, x, y);
            up(stage, x, y);
            assertEquals(com.stonebreak.config.TerrainScaleOption.next(com.stonebreak.config.TerrainScaleOption.DEFAULT),
                s.getSelectedScale(), "the floating scale button takes the press");
            assertFalse(s.hasSpawnPoint(), "not the map under it");
        } finally {
            screen.dispose();
        }
    }

    @Test
    void theDocumentLeavesTheKeyboardToTheScreen() throws Exception {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperFixtures.prepare(screen.getStateManager(), "default");
        try (DocumentStage stage = DocumentTerrainMapperCapture.stage(shipped, screen, 1920, 1080, 1f, 0.1)) {
            click(stage, screen.getStateManager(), "name");
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
        TerrainMapperFixtures.prepare(screen.getStateManager(), "standard");
        com.stonebreak.ui.fidelity.RecordingServices none = new com.stonebreak.ui.fidelity.RecordingServices();
        try (DocumentStage stage = new DocumentStage(shipped, 1920, 1080, 1f, none,
                com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER, Map.of())) {
            click(stage, screen.getStateManager(), "mode6");
            assertEquals(VisualizerKind.HEIGHT, screen.getStateManager().getActiveVisualizer());
        } finally {
            screen.dispose();
        }
    }
}
