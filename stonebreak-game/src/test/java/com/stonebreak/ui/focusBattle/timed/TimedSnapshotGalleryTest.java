package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import io.github.humbleui.types.Rect;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders every representative timed-input state (the four timed layers, underlay first) at
 * 1920x1080, over arena snow and over near-black (the Archon / night sky), and checks each one
 * actually changes the picture.
 * With {@code -Dstonebreak.timed.snapshots=<dir>} the frames are also written as PNGs for a human
 * (or an agent) to look at; {@code -Dstonebreak.timed.frames=<dir>} adds real game screenshots as
 * backdrops (files named like the bot's: {@code 014_ring_0.png}, …).
 */
class TimedSnapshotGalleryTest {

    private static final int W = 1920, H = 1080;
    private static final int SNOW = BattleRasterFixture.BACKGROUND;
    private static final int DARK = 0xFF0A1018;

    /** One gallery entry: builds the view + drives the layers, names the real frame it matches. */
    private record Scene(String frameFile, Matrix4f camera, FakeBattleView view, TimedInputLayers layers) {}

    private static Matrix4f archonAt(float x, float y, float ppb) {
        return TimedScenes.cameraPlacing(TimedScenes.archonChest(), x, y, ppb, W, H);
    }

    private static Matrix4f monkAt(float x, float y, float ppb) {
        return TimedScenes.cameraPlacing(TimedScenes.monkTorso(), x, y, ppb, W, H);
    }

    private static Scene scene(String frame, Matrix4f camera, FakeBattleView view, Consumer<TimedInputLayers> drive) {
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        drive.accept(layers);
        return new Scene(frame, camera, view, layers);
    }

    private static Map<String, Scene> scenes() {
        Map<String, Scene> all = new LinkedHashMap<>();
        // Positions measured off the bot's reference screenshots.
        Matrix4f flurry = archonAt(966f, 572f, 180f);
        Matrix4f guard = monkAt(1266f, 690f, 200f);

        FakeBattleView early = TimedScenes.ringView(0, 0.15f);
        all.put("ring_1_early", scene("014_ring_0.png", flurry, early, l -> TimedScenes.frame(l, early, 0.016f)));
        FakeBattleView good = TimedScenes.ringView(1, 0.49f);
        all.put("ring_2_good", scene("014_ring_0.png", flurry, good, l -> TimedScenes.frame(l, good, 0.016f)));
        FakeBattleView perfect = TimedScenes.ringView(1, 0.62f);
        all.put("ring_3_perfect", scene("014_ring_0.png", flurry, perfect, l -> TimedScenes.frame(l, perfect, 0.016f)));
        for (TimedGrade grade : TimedGrade.values()) {
            FakeBattleView v = new FakeBattleView();
            all.put("ring_4_resolved_" + grade, scene("015_resolved_RING_PERFECT.png", flurry, v, l -> {
                TimedScenes.frame(l, v, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, grade, 0));
                TimedScenes.frame(l, v, 0.10f);
            }));
        }
        FakeBattleView missThenNext = TimedScenes.ringView(1, 0.06f);
        all.put("ring_5_miss_then_next", scene("017_ring_1.png", flurry, missThenNext, l -> {
            TimedScenes.frame(l, missThenNext, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.MISS, 0));
            TimedScenes.frame(l, missThenNext, 0.30f);
        }));

        FakeBattleView closing = TimedScenes.parryView(0.55f);
        all.put("parry_1_closing", scene("027_guard_telegraph.png", guard, closing, l -> TimedScenes.frame(l, closing, 0.016f)));
        FakeBattleView live = TimedScenes.parryView(1.1f);
        all.put("parry_2_window", scene("027_guard_telegraph.png", guard, live, l -> TimedScenes.frame(l, live, 0.05f)));
        FakeBattleView parried = new FakeBattleView();
        all.put("parry_3_flash", scene("028_resolved_PARRY_PERFECT.png", guard, parried, l -> {
            TimedScenes.frame(l, parried, 0.016f, new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0));
            TimedScenes.frame(l, parried, 0.08f);
        }));
        FakeBattleView blocked = new FakeBattleView();
        all.put("parry_4_block", scene("027_guard_telegraph.png", guard, blocked, l -> {
            TimedScenes.frame(l, blocked, 0.016f,
                    new BattleEvent.DamageDealt(CombatantId.MONK, 19f, BattleEvent.DamageFlavor.BLOCKED));
            TimedScenes.frame(l, blocked, 0.10f);
        }));
        FakeBattleView early2 = TimedScenes.parryView(0.4f);
        early2.prompt = null;
        all.put("parry_5_too_early", scene("027_guard_telegraph.png", guard, early2, l -> {
            TimedScenes.frame(l, early2, 0.016f, new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.MISS, 0));
            TimedScenes.frame(l, early2, 0.10f);
        }));

        FakeBattleView combo = TimedScenes.comboView(3, 0.35f, TimedGrade.PERFECT, TimedGrade.GOOD, TimedGrade.PERFECT);
        all.put("combo_1_mid", scene("038_combo_prompt_3.png", null, combo, l -> {
            TimedScenes.frame(l, combo, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
            for (int i = 0; i < 3; i++) TimedScenes.frame(l, combo, 0.016f, landed(i));
            TimedScenes.frame(l, combo, 0.5f);
        }));
        FakeBattleView missed = TimedScenes.comboView(2, 0.2f, TimedGrade.PERFECT, TimedGrade.GOOD);
        all.put("combo_2_miss", scene("038_combo_prompt_3.png", null, missed, l -> {
            TimedScenes.frame(l, missed, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
            TimedScenes.frame(l, missed, 0.5f);
            missed.prompt = null;
            TimedScenes.frame(l, missed, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, 2),
                    new BattleEvent.ComboFinished(2, false));
            TimedScenes.frame(l, missed, 0.2f);
        }));
        FakeBattleView flawless = TimedScenes.comboView(5, 0.2f, TimedGrade.PERFECT, TimedGrade.GOOD,
                TimedGrade.PERFECT, TimedGrade.PERFECT, TimedGrade.GOOD);
        all.put("combo_3_flawless", scene("040_combo_finished.png", null, flawless, l -> {
            TimedScenes.frame(l, flawless, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
            for (int i = 0; i < 6; i++) TimedScenes.frame(l, flawless, 0.016f, landed(i));
            TimedScenes.frame(l, flawless, 0.5f);
            flawless.prompt = null;
            TimedScenes.frame(l, flawless, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 5));
            TimedScenes.frame(l, flawless, 0.6f, new BattleEvent.ComboFinished(6, true));
            TimedScenes.frame(l, flawless, 0.25f);
        }));

        FakeBattleView lowHp = new FakeBattleView();
        lowHp.monk.hp = lowHp.monk.maxHp * 0.12f;
        all.put("fx_1_low_hp", scene("003_idle_wide.png", null, lowHp, l -> settle(l, lowHp)));
        FakeBattleView chilled = new FakeBattleView();
        chilled.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 8f, 1));
        all.put("fx_2_chilled", scene("012_status_CHILLED.png", null, chilled, l -> settle(l, chilled)));
        FakeBattleView focus = new FakeBattleView();
        focus.focus = 100f;
        all.put("fx_3_focus_ready", scene("033_focus_full.png", null, focus, l -> settle(l, focus)));
        FakeBattleView everything = new FakeBattleView();
        everything.focus = 100f;
        everything.monk.hp = 10f;
        everything.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 8f, 1));
        all.put("fx_4_all_three", scene("003_idle_wide.png", null, everything, l -> settle(l, everything)));
        // Bars are for the intro and the victory hold only; the fight itself is never letterboxed.
        FakeBattleView cinematic = new FakeBattleView();
        cinematic.phase = com.stonebreak.battle.api.BattlePhase.INTRO;
        all.put("fx_5_letterbox_intro", scene("001_intro.png", null, cinematic, l -> settle(l, cinematic)));
        FakeBattleView defeat = new FakeBattleView();
        defeat.outcome = BattleOutcome.DEFEAT;
        defeat.monk.hp = 0f;
        all.put("fx_6_defeat", scene("003_idle_wide.png", null, defeat, l -> {
            TimedScenes.frame(l, defeat, 0.016f, new BattleEvent.Ended(BattleOutcome.DEFEAT));
            for (int i = 0; i < 40; i++) TimedScenes.frame(l, defeat, 0.05f);
        }));
        FakeBattleView crit = new FakeBattleView();
        all.put("fx_7_crit_flash", scene("007_impact_monk_strike.png", null, crit, l -> {
            TimedScenes.frame(l, crit, 0.016f,
                    new BattleEvent.DamageDealt(CombatantId.ARCHON, 80f, BattleEvent.DamageFlavor.CRITICAL));
            TimedScenes.frame(l, crit, 0.04f);
        }));
        return all;
    }

    /** One combo blow landing on the Archon: what the strip's counter counts. */
    private static BattleEvent landed(int hitIndex) {
        return new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, hitIndex, 6);
    }

    private static void settle(TimedInputLayers layers, FakeBattleView view) {
        for (int i = 0; i < 30; i++) TimedScenes.frame(layers, view, 0.033f);
    }

    /** The four timed layers (underlay first) over a flat colour. */
    private static BattleRasterFixture render(Scene scene, int clear, boolean withLayers) {
        if (!withLayers) {
            BattleRasterFixture blank = new BattleRasterFixture(W, H);
            blank.canvas.clear(clear);
            return blank;
        }
        return TimedScenes.paintLayers(scene.layers(), scene.view(), scene.camera(), W, H, clear);
    }

    @Test
    void everyRepresentativeStateChangesThePictureOnSnowAndOnDark() throws Exception {
        String out = System.getProperty("stonebreak.timed.snapshots");
        String frames = System.getProperty("stonebreak.timed.frames");
        Path outDir = out == null || out.isBlank() ? null : Files.createDirectories(Path.of(out));

        for (Map.Entry<String, Scene> e : scenes().entrySet()) {
            Scene scene = e.getValue();
            for (int clear : new int[]{SNOW, DARK}) {
                BattleRasterFixture with = render(scene, clear, true);
                BattleRasterFixture without = render(scene, clear, false);
                assertTrue(with.diff(without) > 400,
                        e.getKey() + " must be visible over " + (clear == SNOW ? "snow" : "dark"));
                if (outDir != null) write(with, outDir.resolve(e.getKey() + (clear == SNOW ? "_snow" : "_dark") + ".png"));
            }
            if (outDir != null && frames != null && scene.frameFile() != null) {
                Path frame = Path.of(frames, scene.frameFile());
                if (Files.isRegularFile(frame)) {
                    try (Image backdrop = Image.makeDeferredFromEncodedBytes(Files.readAllBytes(frame))) {
                        write(renderOverFrame(scene, backdrop), outDir.resolve(e.getKey() + "_game.png"));
                    }
                }
            }
        }
    }

    /** Over a real screenshot the HUD is already baked in, so only the timed layers are added. */
    private static BattleRasterFixture renderOverFrame(Scene scene, Image backdrop) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        fx.canvas.drawImageRect(backdrop, Rect.makeXYWH(0, 0, W, H));
        float s = FocusBattleLayout.effectiveScale(W, H, TimedScenes.UI_SCALE);
        TimedInputLayers l = scene.layers();
        for (SkijaFocusBattleRenderer.Layer layer : List.of(l.screenFxLayer(), l.timingRingLayer(),
                l.parryLayer(), l.comboStripLayer())) {
            layer.paint(fx.ui, fx.canvas, W, H, s, TimedScenes.UI_SCALE, scene.view(), null, scene.camera());
        }
        return fx;
    }

    private static void write(BattleRasterFixture fx, Path file) throws Exception {
        try (Image image = Image.makeRasterFromBitmap(fx.bitmap); Data png = EncoderPNG.encode(image)) {
            Files.write(file, png.getBytes());
        }
    }
}
