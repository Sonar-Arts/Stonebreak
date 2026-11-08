package com.stonebreak.ui.mainMenu.showcase;

import com.openmason.engine.util.easing.EasingType;
import com.stonebreak.battle.camera.CameraFrame;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The main menu showcase's GL-free core: shot evaluation, the shipped config, and the director's dip-to-black cycle. */
class ShowcaseDirectorTest {

    private static FlyoverShot linear(String caption, float duration) {
        return new FlyoverShot(caption, FlyoverShot.Motion.LINEAR, new Vector3f(0, 100, 0), new Vector3f(100, 100, 0),
                new Vector3f(0, 64, 50), new Vector3f(100, 64, 50), Float.NaN, 60, 50, 0, 0, duration,
                EasingType.Linear, null, null, false);
    }

    private static FlyoverShot orbit(boolean loop) {
        return new FlyoverShot("orbit", FlyoverShot.Motion.ORBIT, new Vector3f(0, 150, 100), null,
                new Vector3f(0, 80, 0), null, 360f, 62, 62, 0, 0, 100f, EasingType.Linear, 1800L, "CLEAR", loop);
    }

    /** A stage that records the camera and answers readiness as told. */
    private static final class FakeStage implements ShowcaseDirector.Stage {
        boolean ready;
        float lift;
        final List<CameraFrame> frames = new ArrayList<>();
        final List<Long> times = new ArrayList<>();

        @Override public void moveCamera(CameraFrame frame) { frames.add(frame); }
        @Override public boolean areaReady(CameraFrame frame) { return ready; }
        @Override public void setConditions(Long timeTicks, String weather) { times.add(timeTicks); }
        @Override public float liftFor(FlyoverShot shot) { return lift; }
    }

    @Test
    void aLinearShotGlidesBetweenItsEnds() {
        FlyoverShot shot = linear("a", 10f);
        CameraFrame start = shot.evaluate(0f, 0f);
        CameraFrame mid = shot.evaluate(5f, 0f);
        CameraFrame end = shot.evaluate(99f, 0f);
        assertEquals(0f, start.eye().x, 1e-4);
        assertEquals(50f, mid.eye().x, 1e-4);
        assertEquals(100f, end.eye().x, 1e-4, "time clamps to the shot");
        assertEquals(55f, mid.fovDeg(), 1e-4);
        assertEquals(110f, shot.evaluate(5f, 10f).eye().y, 1e-4, "a lift raises the eye...");
        assertEquals(74f, shot.evaluate(5f, 10f).target().y, 1e-4, "...and the target with it");
    }

    @Test
    void aLoopingOrbitKeepsItsRadiusAndMeetsItsStart() {
        FlyoverShot shot = orbit(true);
        for (float t = 0; t < 100f; t += 7.3f) {
            CameraFrame f = shot.evaluate(t, 0f);
            float r = (float) Math.hypot(f.eye().x - f.target().x, f.eye().z - f.target().z);
            assertEquals(100f, r, 1e-3, "radius at " + t);
            assertEquals(150f, f.eye().y, 1e-4);
        }
        CameraFrame a = shot.evaluate(0f, 0f);
        CameraFrame b = shot.evaluate(100f, 0f);
        CameraFrame c = shot.evaluate(130f, 0f);
        assertEquals(a.eye().x, b.eye().x, 1e-3, "one lap ends where it began (no visible cut)");
        assertEquals(a.eye().z, b.eye().z, 1e-3);
        assertEquals(shot.evaluate(30f, 0f).eye().x, c.eye().x, 1e-3, "the clock wraps");
    }

    @Test
    void theLiftClearsTheHighestGroundOnThePath() {
        FlyoverShot shot = linear("a", 10f); // eye at y = 100 from x 0 to 100
        FlyoverShot.GroundProbe hill = new FlyoverShot.GroundProbe() {
            @Override public int groundHeight(int x, int z) { return x >= 40 && x <= 60 ? 120 : 70; }
            @Override public int waterLevel(int x, int z) { return -1; }
        };
        assertEquals(25f, shot.liftAbove(hill, 5f, 3f), 1e-4);
        FlyoverShot.GroundProbe flat = new FlyoverShot.GroundProbe() {
            @Override public int groundHeight(int x, int z) { return 60; }
            @Override public int waterLevel(int x, int z) { return -1; }
        };
        assertEquals(0f, shot.liftAbove(flat, 5f, 3f), 1e-4, "never lowers a shot");
    }

    @Test
    void theDirectorHoldsBlackUntilTheAreaIsDrawnThenFadesIn() {
        FakeStage stage = new FakeStage();
        ShowcaseDirector d = new ShowcaseDirector(List.of(linear("a", 10f)), stage, 1L, -1);
        d.tick(0.016f);
        assertEquals(ShowcaseDirector.Phase.WAIT, d.phase());
        for (int i = 0; i < 60; i++) {
            d.tick(0.016f);
        }
        assertEquals(ShowcaseDirector.Phase.WAIT, d.phase(), "not ready: still black");
        assertEquals(1f, d.fade());
        assertEquals("", d.caption(), "no caption while black");
        stage.ready = true;
        d.tick(0.016f);
        assertEquals(ShowcaseDirector.Phase.FADE_IN, d.phase());
        for (int i = 0; i < 100; i++) {
            d.tick(0.016f);
        }
        assertEquals(ShowcaseDirector.Phase.PLAY, d.phase());
        assertEquals(0f, d.fade());
        assertEquals("a", d.caption());
    }

    @Test
    void theDirectorFadesInAnywayAfterTheReadyTimeout() {
        FakeStage stage = new FakeStage();
        ShowcaseDirector d = new ShowcaseDirector(List.of(linear("a", 10f)), stage, 1L, -1);
        float t = 0f;
        while (d.phase() != ShowcaseDirector.Phase.FADE_IN && t < 20f) {
            d.tick(0.1f);
            t += 0.1f;
        }
        assertEquals(ShowcaseDirector.Phase.FADE_IN, d.phase());
        assertTrue(t >= ShowcaseDirector.READY_TIMEOUT && t < ShowcaseDirector.READY_TIMEOUT + 0.5f, "timed out at " + t);
    }

    @Test
    void cutsNeverRepeatAShotBackToBack() {
        FakeStage stage = new FakeStage();
        stage.ready = true;
        List<FlyoverShot> shots = List.of(linear("a", 3f), linear("b", 3f), linear("c", 3f));
        ShowcaseDirector d = new ShowcaseDirector(shots, stage, 42L, -1);
        List<Integer> order = new ArrayList<>();
        int last = -1;
        for (int i = 0; i < 4000; i++) {
            d.tick(0.05f);
            if (d.currentShot() != last) {
                order.add(d.currentShot());
                last = d.currentShot();
            }
        }
        assertTrue(order.size() > 10, "many cuts: " + order);
        for (int i = 1; i < order.size(); i++) {
            assertNotEquals(order.get(i - 1), order.get(i), "back to back at " + i + ": " + order);
        }
    }

    @Test
    void aLoopingShotIsOneContinuousTake() {
        FakeStage stage = new FakeStage();
        stage.ready = true;
        ShowcaseDirector d = new ShowcaseDirector(List.of(orbit(true)), stage, 1L, -1);
        for (int i = 0; i < 6000; i++) { // 300 s: three laps
            d.tick(0.05f);
            if (d.phase() == ShowcaseDirector.Phase.PLAY) {
                assertEquals(0f, d.fade(), "no dip to black once playing");
            }
        }
        assertEquals(ShowcaseDirector.Phase.PLAY, d.phase());
        assertEquals(List.of(1800L), stage.times, "conditions set once, before the take");
    }

    @Test
    void theShippedShowcaseLoadsAndLoops() throws Exception {
        ShowcaseConfig cfg = ShowcaseConfig.load();
        assertNotEquals(0L, cfg.seed());
        assertFalse(cfg.shots().isEmpty());
        for (FlyoverShot s : cfg.shots()) {
            assertTrue(s.duration() > ShowcaseDirector.FADE_IN_SECONDS + ShowcaseDirector.FADE_OUT_SECONDS,
                    "a shot outlasts its fades");
            assertNotNull(s.evaluate(s.duration() / 2f, 0f));
        }
        assertTrue(cfg.shots().getFirst().loop(), "the menu flies one continuous take");
    }

    @Test
    void theConfigReadsPairsAndDefaults() throws Exception {
        String json = """
            {"seed": 7, "shots": [{"eyeFrom": [1,2,3], "targetFrom": [4,5,6], "fov": [50, 40], "duration": 9}]}
            """;
        ShowcaseConfig cfg = ShowcaseConfig.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        FlyoverShot s = cfg.shots().getFirst();
        assertEquals(7L, cfg.seed());
        assertEquals(FlyoverShot.Motion.LINEAR, s.motion());
        assertEquals(new Vector3f(1, 2, 3), s.eyeTo(), "eyeTo defaults to eyeFrom");
        assertEquals(50f, s.fovFrom());
        assertEquals(40f, s.fovTo());
        assertFalse(s.loop());
    }
}
