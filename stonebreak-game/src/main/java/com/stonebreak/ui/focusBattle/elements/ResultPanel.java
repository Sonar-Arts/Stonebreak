package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.BattleStats;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MResultCard;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleHudRules;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * E16: the result panel, a thin adapter over one {@link MResultCard}. Once the battle has ended and
 * the camera has had its moment ({@link BattleHudRules#VICTORY_CINEMATIC_SECONDS} for a victory,
 * {@link #DEFEAT_DELAY_SECONDS} for a defeat) the card rises into
 * {@link FocusBattleLayout#resultPanelRect}, its stats count up, and three {@link MButton}s hand
 * control back to the battle's host.
 *
 * <p>What is the battle's own here: when the card rises, what it says, and what its buttons do.
 * Layout, focus, the count-up, the scrim and the painting are the card's. It answers input only
 * once fully risen ({@link #interactive}); until then the screen swallows every press, so a confirm
 * mashed at the killing blow cannot pick Retry.
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

    /** Hit-testing twin of every panel's card: same structure, so the same button rects. */
    private static final MResultCard GEOMETRY = newCard(null);

    private final MResultCard card = newCard(this);
    private BattleScreenHost host;
    private BattleOutcome outcome = BattleOutcome.NONE;
    /** Seconds since the battle ended; negative while it is running. */
    private float sinceEnd = -1f;
    private BattleStats shownStats;
    private String shownTime;

    private static MResultCard newCard(ResultPanel owner) {
        List<MButton> buttons = new ArrayList<>();
        for (Button b : Button.values()) {
            MButton button = new MButton(b.label()).fontSize(MStyle.FONT_META);
            // A card without an owner is only ever measured, never pressed.
            if (owner != null) button.onClick(() -> owner.press(b));
            buttons.add(button);
        }
        // Titled from the start: the header is part of the layout the two cards must share.
        MResultCard card = new MResultCard().title(title(BattleOutcome.VICTORY), MStyle.TEXT_ACCENT)
                .columns(2).scrim(true).buttons(buttons);
        fill(card, BattleStats.ZERO, clock(0f));
        return card.countUp(COUNT_UP_SECONDS).rise(0f);
    }

    public void reset() {
        outcome = BattleOutcome.NONE;
        sinceEnd = -1f;
        host = null;
        shownStats = null;
        shownTime = null;
        card.rise(1f);            // focus only moves on a seated card
        card.focus(0);
        card.rise(0f).restartCountUp();
    }

    public void update(float dt, BattleView view) {
        if (view == null) return;
        if (sinceEnd < 0f) {
            if (view.outcome() == null || view.outcome() == BattleOutcome.NONE) return;
            reset();
            outcome = view.outcome();
            sinceEnd = 0f;
            card.title(title(outcome), outcome == BattleOutcome.VICTORY ? MStyle.TEXT_ACCENT : BattlePalette.ACCENT_ARCHON);
        } else {
            float step = Math.max(0f, dt);
            sinceEnd += step;
            card.rise(rise());
            // Only the part of the step after the card seated counts toward the count-up.
            card.update(Math.min(step, sinceEnd - delayFor(outcome) - RISE_SECONDS));
        }
        syncStats(view);
    }

    /** The clock row is text (m:ss), so it follows the card's count-up by hand; the rest are numbers. */
    private void syncStats(BattleView view) {
        BattleStats stats = view.stats() == null ? BattleStats.ZERO : view.stats();
        String time = clock(view.elapsedSeconds() * card.countProgress());
        if (stats.equals(shownStats) && time.equals(shownTime)) return;
        shownStats = stats;
        shownTime = time;
        fill(card, stats, time);
    }

    private static void fill(MResultCard card, BattleStats s, String time) {
        card.clearStats()
                .stat("Time", time)
                .statNumber("Damage dealt", s.damageDealt(), null)
                .statNumber("Damage taken", s.damageTaken(), null)
                .statNumber("Parries", s.parries(), null)
                .statNumber("Blocks", s.blocks(), null)
                .statNumber("Perfect rings", s.perfectRings(), null)
                .statNumber("Best streak", s.bestRingStreak(), null)
                .statNumber("Combo hits", s.comboHits(), null)
                .statNumber("Turns", s.turnsTaken(), null);
    }

    static String clock(float seconds) {
        int total = Math.round(Math.max(0f, seconds));
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    public static String title(BattleOutcome outcome) {
        return outcome == BattleOutcome.VICTORY ? "VICTORY" : "DEFEATED";
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
        // Exactly 1 only once the rise time is up: the card answers input from that moment, not a
        // rounding error earlier.
        return t >= 1f ? 1f : Math.min(0.9999f, EasingFunctions.apply(MColor.clamp01(t), EasingType.EaseOutCubic));
    }

    public boolean visible() { return sinceEnd >= 0f && sinceEnd > delayFor(outcome); }

    /** True once the panel is seated and its buttons answer. */
    public boolean interactive() { return sinceEnd >= 0f && card.interactive(); }

    /** 0..1 share of each stat's final value currently shown. */
    public float countUp() { return interactive() ? card.countProgress() : 0f; }

    /** The card itself, for what it shows right now (stat texts, button rects as painted). */
    public MResultCard card() { return card; }

    public Button focusedButton() { return Button.values()[focusedIndex()]; }

    public int focusedIndex() { return Math.max(0, card.focused()); }

    /** Keyboard: next / previous button, wrapping. Ignored until {@link #interactive}. */
    public void moveFocus(int delta) { card.moveFocus(delta); }

    /** Mouse hover. Ignored until {@link #interactive} and for an index that is no button. */
    public void focus(int index) { card.focus(index); }

    /**
     * Presses the focused button.
     * @return true when a host callback ran. The host may have re-bound or unbound the screen by
     *         the time this returns, so callers must not touch battle state afterwards.
     */
    public boolean activate(BattleScreenHost host) {
        if (host == null) return false;
        this.host = host;
        return card.activate();
    }

    private void press(Button button) {
        BattleScreenHost target = host;
        if (target == null) return;
        switch (button) {
            case RETRY -> target.retry();
            case EXPLORE_ARENA -> target.exploreArena();
            case RETURN_TO_WORLD -> target.returnToWorld();
        }
    }

    // ─────────────────────────────────────────────── Layout (shared with hit-testing)

    private static MResultCard seat(MResultCard card, int w, int h, float rawUiScale, float scale) {
        float[] seat = FocusBattleLayout.resultPanelRect(w, h, rawUiScale);
        return card.screen(w, h).scale(scale).bounds(seat[0], seat[1], seat[2], seat[3]);
    }

    /** Rect of button {@code index}, in the seated panel. */
    public static synchronized float[] buttonRect(int index, int w, int h, float rawUiScale) {
        return seat(GEOMETRY, w, h, rawUiScale, FocusBattleLayout.effectiveScale(w, h, rawUiScale)).buttonRect(index);
    }

    /** Index of the button under the pointer, or −1. */
    public static synchronized int buttonAt(float px, float py, int w, int h, float rawUiScale) {
        return seat(GEOMETRY, w, h, rawUiScale, FocusBattleLayout.effectiveScale(w, h, rawUiScale)).buttonAt(px, py);
    }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || view == null || !visible()) return;
        seat(card, windowWidth, windowHeight, rawUiScale, uiScale).render(ui);
    }
}
