package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.BattleStats;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleHudRules;
import com.stonebreak.ui.focusBattle.FlowTheme;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

import java.util.List;

/**
 * E16 — the result panel. Once the battle has ended and the camera has had its moment
 * ({@link BattleHudRules#VICTORY_CINEMATIC_SECONDS} for a victory, {@link #DEFEAT_DELAY_SECONDS}
 * for a defeat) the panel rises into {@link FocusBattleLayout#resultPanelRect}, its stats count up,
 * and three buttons hand control back to the battle's host.
 *
 * <p>The button rects come from {@link #buttonRect}, used by the painter and by the screen's mouse
 * hit-test alike. The panel takes input only once it has fully risen ({@link #interactive}); until
 * then the screen swallows every press, so a confirm mashed at the killing blow cannot pick Retry.
 */
public final class ResultPanel implements SkijaFocusBattleRenderer.Layer {

    public static final float DEFEAT_DELAY_SECONDS = 2.0f;
    public static final float RISE_SECONDS = 0.45f;
    public static final float COUNT_UP_SECONDS = 1.0f;

    /** The panel's buttons, left to right. */
    public enum Button {
        RETRY("Retry"),
        EXPLORE_ARENA("Explore arena"),
        RETURN_TO_WORLD("Return to world");

        private final String label;

        Button(String label) { this.label = label; }

        public String label() { return label; }
    }

    public static final int BUTTON_COUNT = Button.values().length;

    /** One line of the stats block. */
    public record Stat(String label, String value) {}

    private static final float PAD = 18f;
    private static final float TITLE_H = 52f;
    private static final float BUTTON_H = 40f;
    private static final float BUTTON_GAP = 10f;
    private static final int STAT_ROWS = 5;

    private BattleOutcome outcome = BattleOutcome.NONE;
    /** Seconds since the battle ended; negative while it is running. */
    private float sinceEnd = -1f;
    private int focused;

    public void reset() {
        outcome = BattleOutcome.NONE;
        sinceEnd = -1f;
        focused = 0;
    }

    public void update(float dt, BattleView view) {
        if (view == null) return;
        if (sinceEnd < 0f) {
            if (view.outcome() == null || view.outcome() == BattleOutcome.NONE) return;
            outcome = view.outcome();
            sinceEnd = 0f;
            focused = 0;
            return;
        }
        sinceEnd += Math.max(0f, dt);
    }

    // ─────────────────────────────────────────────── State

    /** True from the moment the battle has an outcome, panel up or not: the screen routes input here. */
    public boolean ended() { return sinceEnd >= 0f; }

    public BattleOutcome outcome() { return outcome; }

    public static float delayFor(BattleOutcome outcome) {
        return outcome == BattleOutcome.VICTORY ? BattleHudRules.VICTORY_CINEMATIC_SECONDS : DEFEAT_DELAY_SECONDS;
    }

    /** 0 = still below the screen, 1 = seated (eased). */
    public float rise() {
        if (sinceEnd < 0f) return 0f;
        float t = (sinceEnd - delayFor(outcome)) / RISE_SECONDS;
        return EasingFunctions.apply(FocusBattleTheme.clamp01(t), EasingType.EaseOutCubic);
    }

    public boolean visible() { return sinceEnd >= 0f && sinceEnd > delayFor(outcome); }

    /** True once the panel is seated and its buttons answer. */
    public boolean interactive() { return sinceEnd >= 0f && sinceEnd >= delayFor(outcome) + RISE_SECONDS; }

    /** 0..1 share of each stat's final value currently shown. */
    public float countUp() {
        if (sinceEnd < 0f) return 0f;
        float t = (sinceEnd - delayFor(outcome) - RISE_SECONDS) / COUNT_UP_SECONDS;
        return EasingFunctions.apply(FocusBattleTheme.clamp01(t), EasingType.EaseOutCubic);
    }

    public Button focusedButton() { return Button.values()[focused]; }

    public int focusedIndex() { return focused; }

    /** Keyboard: next / previous button, wrapping. Ignored until {@link #interactive}. */
    public void moveFocus(int delta) {
        if (interactive()) focused = Math.floorMod(focused + delta, BUTTON_COUNT);
    }

    /** Mouse hover. Ignored until {@link #interactive} and for an index that is no button. */
    public void focus(int index) {
        if (interactive() && index >= 0 && index < BUTTON_COUNT) focused = index;
    }

    /**
     * Presses the focused button.
     * @return true when a host callback ran. The host may have re-bound or unbound the screen by
     *         the time this returns, so callers must not touch battle state afterwards.
     */
    public boolean activate(BattleScreenHost host) {
        if (!interactive() || host == null) return false;
        switch (focusedButton()) {
            case RETRY -> host.retry();
            case EXPLORE_ARENA -> host.exploreArena();
            case RETURN_TO_WORLD -> host.returnToWorld();
        }
        return true;
    }

    // ─────────────────────────────────────────────── Layout (shared with hit-testing)

    /** Rect of button {@code index}, in the seated panel. */
    public static float[] buttonRect(int index, int w, int h, float rawUiScale) {
        return buttonRect(FocusBattleLayout.resultPanelRect(w, h, rawUiScale), index,
                FocusBattleLayout.effectiveScale(w, h, rawUiScale));
    }

    /** Parent-relative form, so a rising (offset) panel lays out identically. */
    public static float[] buttonRect(float[] panel, int index, float scale) {
        float k = compression(panel, scale);
        float pad = PAD * scale * k;
        float gap = BUTTON_GAP * scale * k;
        float bw = Math.max(0f, (panel[2] - 2f * pad - (BUTTON_COUNT - 1) * gap) / BUTTON_COUNT);
        float bh = BUTTON_H * scale * k;
        return new float[]{(float) Math.floor(panel[0] + pad + index * (bw + gap)),
                (float) Math.floor(panel[1] + panel[3] - pad - bh), (float) Math.floor(bw), (float) Math.floor(bh)};
    }

    /** Index of the button under the pointer, or −1. */
    public static int buttonAt(float px, float py, int w, int h, float rawUiScale) {
        for (int i = 0; i < BUTTON_COUNT; i++) {
            if (FocusBattleLayout.contains(px, py, buttonRect(i, w, h, rawUiScale))) return i;
        }
        return -1;
    }

    static float[] titleRect(float[] panel, float scale) {
        float k = compression(panel, scale);
        return new float[]{panel[0], panel[1] + PAD * scale * k * 0.5f, panel[2], TITLE_H * scale * k};
    }

    /** Cell of stat {@code index}: two columns of {@value #STAT_ROWS} rows between title and buttons. */
    static float[] statRect(float[] panel, int index, float scale) {
        float k = compression(panel, scale);
        float pad = PAD * scale * k;
        float[] title = titleRect(panel, scale);
        float top = title[1] + title[3] + 10f * scale * k;
        float bottom = buttonRect(panel, 0, scale)[1] - 12f * scale * k;
        float rowH = Math.max(0f, (bottom - top) / STAT_ROWS);
        float colGap = 28f * scale * k;
        float colW = Math.max(0f, (panel[2] - 2f * pad - colGap) / 2f);
        int col = index / STAT_ROWS, row = index % STAT_ROWS;
        return new float[]{panel[0] + pad + col * (colW + colGap), top + row * rowH, colW, rowH};
    }

    // The layout caps the panel at 92% of a small window; everything inside shrinks with it.
    private static float compression(float[] panel, float scale) {
        float design = 320f * scale;
        return design <= 0f ? 1f : Math.min(1f, panel[3] / design);
    }

    // ─────────────────────────────────────────────── Content

    /** The stats block at {@code share} 0..1 of its final values (the count-up). */
    public static List<Stat> stats(BattleStats stats, float elapsedSeconds, float share) {
        BattleStats s = stats == null ? BattleStats.ZERO : stats;
        float k = FocusBattleTheme.clamp01(share);
        return List.of(
                new Stat("Time", clock(elapsedSeconds * k)),
                new Stat("Damage dealt", whole(s.damageDealt() * k)),
                new Stat("Damage taken", whole(s.damageTaken() * k)),
                new Stat("Parries", whole(s.parries() * k)),
                new Stat("Blocks", whole(s.blocks() * k)),
                new Stat("Perfect rings", whole(s.perfectRings() * k)),
                new Stat("Best streak", whole(s.bestRingStreak() * k)),
                new Stat("Combo hits", whole(s.comboHits() * k)),
                new Stat("Turns", whole(s.turnsTaken() * k)));
    }

    private static String whole(float value) {
        return String.valueOf(Math.round(Math.max(0f, value)));
    }

    static String clock(float seconds) {
        int total = Math.round(Math.max(0f, seconds));
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    public static String title(BattleOutcome outcome) {
        return outcome == BattleOutcome.VICTORY ? "VICTORY" : "DEFEATED";
    }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || view == null || !visible()) return;
        float rise = rise();
        FocusBattleTheme.fillRect(canvas, 0f, 0f, windowWidth, windowHeight,
                FocusBattleTheme.fade(FlowTheme.RESULT_SCRIM, rise));
        float[] seat = FocusBattleLayout.resultPanelRect(windowWidth, windowHeight, rawUiScale);
        float[] panel = FocusBattleLayout.offset(seat, 0f, (float) Math.round((1f - rise) * (windowHeight - seat[1])));
        paintPanel(ui, canvas, panel, outcome, stats(view.stats(), view.elapsedSeconds(), countUp()),
                interactive() ? focused : -1, uiScale, anim);
    }

    /** Draws the panel into {@code panel}; {@code focusedIndex} −1 draws no button as focused. */
    public static void paintPanel(MasonryUI ui, Canvas canvas, float[] panel, BattleOutcome outcome,
                                  List<Stat> stats, int focusedIndex, float uiScale, BattleHudAnimState anim) {
        boolean victory = outcome == BattleOutcome.VICTORY;
        int accent = victory ? FlowTheme.GOLD : FlowTheme.FROST_RED;
        FlowTheme.tintedWindow(canvas, panel[0], panel[1], panel[2], panel[3], 0xF0121A24, accent, 1f);

        float[] titleRect = titleRect(panel, uiScale);
        String title = title(outcome);
        float cx = panel[0] + panel[2] / 2f;
        float tracking = 6f * uiScale;
        Font titleFont = FocusBattleTheme.font(ui,
                Math.min(FlowTheme.FS_RESULT_TITLE, titleRect[3] / Math.max(0.01f, uiScale) * 0.72f), uiScale);
        if (titleFont != null) {
            FlowTheme.spacedTextCentered(canvas, title, cx,
                    FocusBattleTheme.baseline(titleRect[1] + titleRect[3] / 2f, titleFont.getSize()), titleFont,
                    tracking, accent, 1f);
        }
        float ruleY = titleRect[1] + titleRect[3];
        FocusBattleTheme.fillRect(canvas, panel[0] + panel[2] * 0.12f, ruleY, panel[2] * 0.76f,
                Math.max(1f, Math.round(uiScale)), FocusBattleTheme.fade(accent, 0.7f));

        for (int i = 0; i < stats.size(); i++) {
            float[] cell = statRect(panel, i, uiScale);
            Stat stat = stats.get(i);
            float size = Math.min(FlowTheme.FS_RESULT_STAT, cell[3] / Math.max(0.01f, uiScale) * 0.78f);
            Font font = FocusBattleTheme.font(ui, size, uiScale);
            if (font == null) continue;
            float baseline = FocusBattleTheme.baseline(cell[1] + cell[3] / 2f, font.getSize());
            FocusBattleTheme.text(canvas, stat.label(), cell[0], baseline, font, FocusBattleTheme.TEXT_LABEL);
            FocusBattleTheme.textRight(canvas, stat.value(), cell[0] + cell[2], baseline, font, FocusBattleTheme.TEXT);
        }

        Button[] buttons = Button.values();
        float pulse = anim == null ? 0f : anim.selectedPulse;
        for (int i = 0; i < buttons.length; i++) {
            paintButton(ui, canvas, buttonRect(panel, i, uiScale), buttons[i].label(), i == focusedIndex, accent,
                    pulse, uiScale);
        }
    }

    private static void paintButton(MasonryUI ui, Canvas canvas, float[] r, String label, boolean focused,
                                    int accent, float pulse, float uiScale) {
        float radius = 4f * uiScale;
        FocusBattleTheme.roundedFill(canvas, r[0], r[1], r[2], r[3], radius, FlowTheme.BUTTON_FILL);
        if (focused) {
            FocusBattleTheme.roundedFill(canvas, r[0], r[1], r[2], r[3], radius,
                    FocusBattleTheme.fade(accent, 0.22f + 0.12f * pulse));
        }
        FocusBattleTheme.roundedStroke(canvas, r[0], r[1], r[2], r[3], radius,
                focused ? accent : FocusBattleTheme.fade(FocusBattleTheme.WINDOW_BORDER, 0.55f),
                focused ? Math.max(1.5f, 2f * uiScale) : 1f);
        Font font = FocusBattleTheme.fitFont(ui, label, FlowTheme.FS_RESULT_BUTTON, uiScale, r[2] - 12f * uiScale);
        if (font == null) return;
        FocusBattleTheme.textCentered(canvas, label, r[0] + r[2] / 2f,
                FocusBattleTheme.baseline(r[1] + r[3] / 2f, font.getSize()), font,
                focused ? FocusBattleTheme.TEXT : FocusBattleTheme.TEXT_LABEL);
        if (focused) {
            float handH = r[3] * 0.62f, handW = handH * 1.3f;
            HandCursor.paint(canvas, r[0] - handW * 0.3f, r[1] + (r[3] - handH) / 2f, handW, handH, 1f);
        }
    }
}
