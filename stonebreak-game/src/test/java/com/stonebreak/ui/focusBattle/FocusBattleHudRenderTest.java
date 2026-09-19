package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assembled HUD on a CPU canvas: which elements are visible when, that each one lands in the
 * rect the layout (and therefore the mouse hit-test) says it does, and the Wave-2 layer hooks.
 */
class FocusBattleHudRenderTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float UI_SCALE = 1f;

    private static BattleRasterFixture hud(FakeBattleView view, BattleMenuState menu, BattleHudAnimState anim) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        new SkijaFocusBattleRenderer(null).paintHud(fx.ui, fx.canvas, W, H, UI_SCALE, view, menu, anim, null);
        return fx;
    }

    @Test
    void theAlwaysOnElementsPaintIntoTheirLayoutRects() {
        BattleRasterFixture fx = hud(new FakeBattleView(), new BattleMenuState(), null);
        assertTrue(fx.countPainted(FocusBattleLayout.partyWindowRect(W, H, UI_SCALE)) > 30_000, "E3");
        assertTrue(fx.countPainted(FocusBattleLayout.enemyPlateRect(W, H, UI_SCALE)) > 30_000, "E5");
        assertTrue(fx.countPainted(FocusBattleLayout.modeTagRect(W, H, UI_SCALE)) > 1500, "E13");
        assertTrue(fx.countPainted(FocusBattleLayout.commandWindowRect(W, H, UI_SCALE)) > 30_000,
                "E1 rests on screen in its static state even before the monk's gauge fills");
        assertEquals(0, fx.countPainted(FocusBattleLayout.actionBannerRect(W, H, UI_SCALE)),
                "reserved rects stay empty until Wave 2 fills them");
        assertEquals(0, fx.countPainted(FocusBattleLayout.comboStripRect(W, H, UI_SCALE)));
    }

    @Test
    void theCommandWindowAndHelpAppearWithTheTurn() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleRasterFixture fx = hud(view, new BattleMenuState(), null);
        assertTrue(fx.countPainted(FocusBattleLayout.commandWindowRect(W, H, UI_SCALE)) > 30_000, "E1");
        assertTrue(fx.countPainted(FocusBattleLayout.helpStripRect(W, H, UI_SCALE)) > 20_000, "E4");
        assertEquals(0, fx.countPainted(FocusBattleLayout.submenuRect(W, H, UI_SCALE)) > 5000 ? 1 : 0,
                "E2 stays closed until opened");

        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        menu.openSubmenu();
        BattleRasterFixture open = hud(view, menu, null);
        assertTrue(open.countPainted(FocusBattleLayout.submenuRect(W, H, UI_SCALE)) > 20_000, "E2");
    }

    @Test
    void slidesMoveTheBottomWindowsWithoutTouchingTheTop() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleRasterFixture rest = hud(view, new BattleMenuState(), null);

        // The command window never slides away. Waking (0) it still fills its rect, veiled like the
        // static state; awake (1) the veil is gone and the cursor row is lit.
        float[] cmd = FocusBattleLayout.commandWindowRect(W, H, UI_SCALE);
        BattleHudAnimState waking = new BattleHudAnimState();
        waking.commandWake = 0f;
        BattleRasterFixture veiled = hud(view, new BattleMenuState(), waking);
        assertTrue(veiled.countPainted(cmd) > 30_000, "E1 stays in its rect while it wakes");
        assertTrue(veiled.diff(rest, cmd) > 10_000, "and is veiled until it has woken");

        FakeBattleView resting = new FakeBattleView();
        BattleRasterFixture staticState = hud(resting, new BattleMenuState(), null);
        assertTrue(staticState.countPainted(cmd) > 30_000, "between turns E1 is still there");
        assertTrue(staticState.diff(rest, cmd) > 10_000, "in its static look: veiled, no cursor");

        BattleHudAnimState out = new BattleHudAnimState();
        out.bottomHudSlideOut = 1f;
        BattleRasterFixture cinematic = hud(view, new BattleMenuState(), out);
        assertEquals(0, cinematic.countPainted(0, H / 2, W, H), "the whole bottom HUD leaves for the cinematics");
        assertEquals(0, cinematic.diff(rest, 0, 0, W, H / 3), "the enemy plate stays put");
    }

    @Test
    void layersPaintInsideTheSameFrameInOrder() {
        List<String> order = new ArrayList<>();
        SkijaFocusBattleRenderer renderer = new SkijaFocusBattleRenderer(null);
        renderer.addUnderlay((ui, c, w, h, s, raw, v, a, vp) -> {
            order.add("under");
            MPainter.fillRect(c, 0f, 0f, w, h, 0xFF000000);
        });
        renderer.addOverlay((ui, c, w, h, s, raw, v, a, vp) -> {
            order.add("over");
            assertEquals(FocusBattleLayout.effectiveScale(w, h, raw), s, 1e-6f);
        });
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        renderer.paintHud(fx.ui, fx.canvas, W, H, UI_SCALE, new FakeBattleView(), null, null, null);

        assertEquals(List.of("under", "over"), order);
        // The underlay's black is covered by the opaque parts of the windows drawn after it.
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI_SCALE);
        assertTrue(fx.countExactly(0xFF000000, (int) party[0] + 12, (int) party[1] + 12,
                (int) (party[0] + party[2]) - 12, (int) (party[1] + party[3]) - 12)
                < (party[2] - 24) * (party[3] - 24), "windows draw over underlays");
        renderer.dispose();
    }

    @Test
    void theHudSurvivesEveryResolutionAndScale() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        menu.openSubmenu();
        int[][] windows = {{1024, 600}, {1920, 1080}};
        for (int[] win : windows) {
            for (float scale : new float[]{0.75f, 2f}) {
                BattleRasterFixture fx = new BattleRasterFixture(win[0], win[1]);
                new SkijaFocusBattleRenderer(null).paintHud(fx.ui, fx.canvas, win[0], win[1], scale, view, menu,
                        null, null);
                assertTrue(fx.countPainted(FocusBattleLayout.commandWindowRect(win[0], win[1], scale)) > 5000,
                        win[0] + "x" + win[1] + " @" + scale);
            }
        }
    }

    @Test
    void theWindowLooksStaticWhileAPromptOwnsTheInput() {
        // A guarding monk with a full gauge gets the command window AND a parry prompt at once. Confirm
        // then means "parry", so the window must look exactly as static as it behaves.
        FakeBattleView resting = new FakeBattleView();
        resting.prompt = new com.stonebreak.battle.api.PromptView.Parry(0.2f, 0.4f, 0.65f, 0.65f);
        FakeBattleView both = new FakeBattleView();
        both.prompt = resting.prompt;
        both.commandWindowOpen = true;
        float[] cmd = FocusBattleLayout.commandWindowRect(W, H, UI_SCALE);
        assertEquals(0, hud(both, new BattleMenuState(), null).diff(hud(resting, new BattleMenuState(), null), cmd),
                "no cursor, veiled: identical to the resting window");
        assertTrue(!BattleHudRules.menuLive(both), "a prompt outranks the menu");
        both.prompt = null;
        assertTrue(BattleHudRules.menuLive(both));
    }

    // ── the screen owns and drives the windows ───────────────────────────────

    private static FocusBattleScreen boundScreen(FakeBattleView view) {
        FocusBattleScreen screen = new FocusBattleScreen(null);
        screen.bind(view, null, FakeBattleView.crucibleLayout(), null);
        return screen;
    }

    private static void frame(FocusBattleScreen screen, FakeBattleView view, float dt, BattleEvent... events) {
        view.events.clear();
        view.events.addAll(List.of(events));
        screen.update(dt);
        view.events.clear();
    }

    @Test
    void theScreenTurnsBattleEventsIntoWidgetAnimations() {
        FakeBattleView view = new FakeBattleView();
        FocusBattleScreen screen = boundScreen(view);
        frame(screen, view, 1f / 60f);
        assertTrue(screen.enemyPlate().hpGauge().ghostFraction() < 0f);

        view.archon.hp = 450f;
        view.qi = 1;
        frame(screen, view, 0f, new BattleEvent.DamageDealt(CombatantId.ARCHON, 450f, DamageFlavor.NORMAL),
                new BattleEvent.QiChanged(-2, 1), new BattleEvent.TurnReady(CombatantId.MONK));
        assertEquals(1f, screen.enemyPlate().hpGauge().ghostFraction(), 1e-5f, "the HP trail is the gauge's own");
        assertTrue(screen.enemyPlate().hpGauge().flashStrength() > 0.9f);
        assertEquals(0f, screen.partyWindow().hpGauge().flashStrength(), 0f, "the monk was not hit");
        assertEquals(1f, screen.partyWindow().qiPips().spendAmount(), 1e-6f);
        assertEquals(1f, screen.partyWindow().atbGauge().flashStrength(), 1e-6f);
        assertSame(screen.renderer().windows().enemy, screen.enemyPlate(), "the renderer draws the screen's windows");

        // The same event list republished by the model must not fire anything twice.
        screen.update(0.2f);
        float flash = screen.partyWindow().atbGauge().flashStrength();
        screen.update(0f);
        assertEquals(flash, screen.partyWindow().atbGauge().flashStrength(), 0f);
    }

    @Test
    void bindingStartsEveryWidgetClean() {
        FakeBattleView view = new FakeBattleView();
        FocusBattleScreen screen = boundScreen(view);
        frame(screen, view, 1f / 60f);
        view.monk.hp = 40f;
        frame(screen, view, 0f, new BattleEvent.DamageDealt(CombatantId.MONK, 140f, DamageFlavor.CRITICAL));
        assertTrue(screen.partyWindow().hpGauge().ghostFraction() > 0f);

        // A retry re-binds: the new fight's full HP must not read as a heal, nor its first Qi as a gain.
        FakeBattleView next = new FakeBattleView();
        screen.bind(next, null, FakeBattleView.crucibleLayout(), null);
        assertTrue(screen.partyWindow().hpGauge().ghostFraction() < 0f);
        assertEquals(0f, screen.partyWindow().hpGauge().flashStrength(), 0f);
        frame(screen, next, 1f / 60f);
        assertEquals(1f, screen.partyWindow().hpGauge().displayedFraction(), 1e-6f);
        assertEquals(0f, screen.partyWindow().qiPips().popAmount(), 0f);
        assertFalse(screen.commandWindow().rootList().active(), "and the menu rests until the gauge fills");
    }

    @Test
    void theHudReadsAsTheRestOfTheGame() {
        // Frames come from the library: near-black border, no private glass or neon border colours.
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleRasterFixture fx = hud(view, new BattleMenuState(), null);
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI_SCALE);
        assertTrue(fx.countExactly(MStyle.HUD_BORDER, (int) party[0], (int) party[1], (int) (party[0] + party[2]),
                (int) (party[1] + party[3])) > 800, "the stone frame's near-black border");
        float[] cmd = FocusBattleLayout.commandWindowRect(W, H, UI_SCALE);
        assertTrue(fx.countExactly(MStyle.ROW_CURRENT, (int) cmd[0], (int) cmd[1], (int) (cmd[0] + cmd[2]),
                (int) (cmd[1] + cmd[3])) > 2000, "the library's selection fill");
    }

    // ── gallery ──────────────────────────────────────────────────────────────

    private static final int DARK = 0xFF0A1018;

    private static TelegraphView overhead(float elapsed) {
        float impact = EnemyAction.OVERHEAD.impactTime();
        return new TelegraphView(EnemyAction.OVERHEAD, elapsed, impact, impact * 0.75f, impact * 0.95f, false);
    }

    /** Representative HUD states, each driven through a real screen so the widgets animate. */
    private static Map<String, FocusBattleScreen> gallery(Map<String, FakeBattleView> views) {
        Map<String, FocusBattleScreen> all = new LinkedHashMap<>();

        FakeBattleView resting = new FakeBattleView();
        resting.monk.atb = 0.55f;
        resting.archon.atb = 0.3f;
        resting.focus = 42f;
        resting.monk.statuses.add(new StatusView(BattleStatus.HASTE, 12f, 1));
        put(all, views, "1_resting", resting, s -> { });

        FakeBattleView live = new FakeBattleView();
        live.commandWindowOpen = true;
        live.monk.atb = 1f;
        live.focus = live.maxFocus;
        live.qi = 4;
        live.archon.hp = 610f;
        live.archon.atb = 0.7f;
        put(all, views, "2_live_focus_ready", live, s -> {
            for (int i = 0; i < 30; i++) s.update(1f / 60f);   // past the wake fade
        });

        FakeBattleView arts = new FakeBattleView();
        arts.commandWindowOpen = true;
        arts.monk.atb = 1f;
        arts.qi = 1;
        arts.focus = 77f;
        arts.meditateCharges = 0;
        arts.unavailable.put(BattleCommand.MEDITATE, "No charges left.");
        arts.unavailable.put(BattleCommand.STUNNING_STRIKE, "Not enough Qi.");
        arts.unavailable.put(BattleCommand.MARTIAL_SURGE, "Not enough Qi.");
        arts.queuedSurgeHits = 2;
        put(all, views, "3_qi_arts_submenu", arts, s -> {
            s.menuState().selectRoot(FocusBattleLayout.qiArtsRowIndex());
            s.menuState().openSubmenu();
            for (int i = 0; i < 30; i++) s.update(1f / 60f);
        });

        FakeBattleView cast = new FakeBattleView();
        cast.monk.atb = 0.2f;
        cast.monk.hp = 96f;
        cast.monk.statuses.add(new StatusView(BattleStatus.GUARDING, -1f, 1));
        cast.archon.statuses.add(new StatusView(BattleStatus.CHILLED, 6f, 1));
        cast.telegraph = overhead(EnemyAction.OVERHEAD.impactTime() * 0.62f);
        put(all, views, "4_telegraph_guarding", cast, s -> s.update(0.12f));

        FakeBattleView hit = new FakeBattleView();
        hit.qi = 4;
        hit.monk.atb = 0.4f;
        put(all, views, "5_hit_and_qi_spend", hit, s -> {
            s.update(1f / 60f);
            hit.monk.hp = 38f;
            hit.archon.hp = 520f;
            hit.qi = 2;
            hit.archon.statuses.add(new StatusView(BattleStatus.STUNNED, 4f, 1));
            hit.events.addAll(List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 142f, DamageFlavor.CRITICAL),
                    new BattleEvent.DamageDealt(CombatantId.ARCHON, 380f, DamageFlavor.NORMAL),
                    new BattleEvent.QiChanged(-2, 2)));
            s.update(1f / 60f);
            hit.events.clear();
            s.update(0.08f);
        });
        return all;
    }

    private static void put(Map<String, FocusBattleScreen> all, Map<String, FakeBattleView> views, String name,
                            FakeBattleView view, java.util.function.Consumer<FocusBattleScreen> drive) {
        FocusBattleScreen screen = boundScreen(view);
        screen.update(1f / 60f);
        drive.accept(screen);
        all.put(name, screen);
        views.put(name, view);
    }

    /**
     * Every gallery state over arena snow and over near-black. With
     * {@code -Dstonebreak.hud.snapshots=<dir>} the frames are also written as PNGs to look at.
     */
    @Test
    void galleryOverSnowAndDark() throws Exception {
        String out = System.getProperty("stonebreak.hud.snapshots");
        Map<String, FakeBattleView> views = new LinkedHashMap<>();
        Map<String, FocusBattleScreen> screens = gallery(views);
        for (int[] size : new int[][]{{1280, 720}, {1920, 1080}}) {
            for (int background : new int[]{BattleRasterFixture.BACKGROUND, DARK}) {
                for (Map.Entry<String, FocusBattleScreen> e : screens.entrySet()) {
                    FocusBattleScreen screen = e.getValue();
                    BattleRasterFixture fx = new BattleRasterFixture(size[0], size[1]);
                    fx.canvas.clear(background);
                    BattleRasterFixture blank = new BattleRasterFixture(size[0], size[1]);
                    blank.canvas.clear(background);
                    screen.renderer().paintHud(fx.ui, fx.canvas, size[0], size[1], UI_SCALE, views.get(e.getKey()),
                            screen.menuState(), screen.animState(), null);
                    assertTrue(fx.diff(blank) > 60_000, e.getKey() + " paints a HUD");
                    if (out != null && !out.isBlank()) {
                        Path dir = Files.createDirectories(Path.of(out));
                        String name = e.getKey() + "_" + size[1] + (background == DARK ? "_dark" : "_snow") + ".png";
                        try (Image image = Image.makeRasterFromBitmap(fx.bitmap); Data png = EncoderPNG.encode(image)) {
                            Files.write(dir.resolve(name), png.getBytes());
                        }
                    }
                }
            }
        }
    }
}
