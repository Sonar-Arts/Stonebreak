package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MFloatingText;
import com.stonebreak.rendering.UI.masonryUI.MFloatingText.Lane;
import com.stonebreak.rendering.UI.masonryUI.MFloatingText.Style;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MWorldMarker;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * E11: floating numbers and words (damage, heals, Qi and Focus gains, timed-input grades, status
 * names, command rejections). A thin event adapter over one {@link MFloatingText}, which owns the
 * motion, lanes, stagger, cap and painting. What stays here is the battle's own part: which event
 * raises which words in which look ({@link #spawnFor}), and where on screen they are born.
 *
 * <p>A floater takes its <b>screen</b> position once, from the first camera it meets after its event
 * ({@link #resolvePending}), and then moves purely in screen space: the cinematic camera cuts
 * constantly, and a number that re-projected every frame would teleport with each cut. When its
 * body is off-screen it starts from its owner's HUD window instead.
 */
public final class BattleFloaters implements SkijaFocusBattleRenderer.Layer {

    public static final int MAX_LIVE = MFloatingText.DEFAULT_CAP;
    /** Body height the world anchor sits at (chest/head, where the eye already is on a hit). */
    public static final float ANCHOR_HEIGHT = 0.8f;

    // The looks: library presets recoloured with gameplay semantics. Small numbers are "minor" texts
    // moved into the number lane.
    public static final Style DAMAGE = Style.number();
    public static final Style CRITICAL = Style.emphasis();
    public static final Style BLOCKED = Style.minor().withLane(Lane.NUMBER);
    public static final Style PARRIED = Style.word().withColor(BattlePalette.ACCENT_MONK);
    public static final Style HEAL = Style.number().withColor(MStyle.VITAL_OK);
    public static final Style QI = Style.minor().withLane(Lane.NUMBER).withColor(BattlePalette.QI);
    public static final Style FOCUS = Style.word().withColor(BattlePalette.FOCUS);
    public static final Style REJECT = Style.minor().withLane(Lane.NUMBER).withColor(MStyle.TEXT_ERROR);

    /** A ring / combo grade word; a miss is said quietly. */
    public static Style gradeStyle(TimedGrade grade) {
        Style word = Style.word().withColor(BattlePalette.grade(grade));
        return grade == TimedGrade.MISS ? word.withFontSize(Style.minor().fontSize()) : word;
    }

    public static Style statusStyle(BattleStatus status) {
        return Style.minor().withColor(BattlePalette.status(status));
    }

    /** Where a floater starts. */
    public enum Origin { MONK, ARCHON, PARTY_WINDOW, QI_ROW, COMMAND_WINDOW }

    /** What one event raises. */
    public record Spawn(String text, Style style, Origin origin) {}

    /** A spawn waiting for the first camera it meets; {@code world} is null for HUD-anchored ones. */
    private record Pending(Spawn spawn, Vector3f world) {}

    private final MFloatingText text = new MFloatingText().cap(MAX_LIVE);
    private final List<Pending> pending = new ArrayList<>();
    private BattleStageLayout stage;
    private boolean gradeWords = true;

    /**
     * Whether ring / combo grades (PERFECT, GOOD, MISS) float up. Turn off when another layer already
     * writes the same word at the prompt itself, so the player never reads it twice.
     */
    public void setGradeWordsEnabled(boolean enabled) {
        this.gradeWords = enabled;
    }

    public void setStage(BattleStageLayout stage) {
        this.stage = stage;
    }

    public void reset() {
        text.clear();
        pending.clear();
    }

    /** The floaters in flight, oldest first (read-only). */
    public List<MFloatingText.Floater> live() {
        return text.live();
    }

    /** Floaters in flight plus those still waiting for a camera. */
    public int count() {
        return text.liveCount() + pending.size();
    }

    // ─────────────────────────────────────────────── Events

    /** Ages the floaters in flight, then queues this frame's. */
    public void update(float dt, BattleView view, List<BattleEvent> events) {
        text.update(dt);
        if (view == null || events == null) return;
        for (BattleEvent event : events) {
            Spawn spawn = spawnFor(event, gradeWords);
            if (spawn != null) spawn(spawn.text(), spawn.style(), spawn.origin(), view);
        }
    }

    /** The battle's whole vocabulary: the words and look {@code event} raises, or null for none. */
    public static Spawn spawnFor(BattleEvent event, boolean gradeWords) {
        return switch (event) {
            case BattleEvent.DamageDealt hit -> {
                Origin at = originOf(hit.target());
                BattleEvent.DamageFlavor flavor = hit.flavor() == null ? BattleEvent.DamageFlavor.NORMAL : hit.flavor();
                yield switch (flavor) {
                    case PARRIED -> new Spawn("PARRY!", PARRIED, at);
                    case BLOCKED -> new Spawn("BLOCK " + amount(hit.amount()), BLOCKED, at);
                    case CRITICAL -> new Spawn(amount(hit.amount()) + "!", CRITICAL, at);
                    case NORMAL -> new Spawn(amount(hit.amount()), DAMAGE, at);
                };
            }
            case BattleEvent.Healed heal -> new Spawn("+" + amount(heal.amount()), HEAL, originOf(heal.target()));
            case BattleEvent.QiChanged qi -> qi.delta() > 0 ? new Spawn("+" + qi.delta() + " Qi", QI, Origin.QI_ROW) : null;
            case BattleEvent.FocusFull full -> new Spawn("FOCUS MAX", FOCUS, Origin.MONK);
            // A parry's verdict already arrives as PARRIED / BLOCKED damage: one word, not two.
            case BattleEvent.PromptResolved resolved ->
                    gradeWords && resolved.kind() != PromptKind.PARRY && resolved.grade() != null
                            ? new Spawn(resolved.grade().name(), gradeStyle(resolved.grade()), Origin.ARCHON) : null;
            case BattleEvent.StatusApplied status -> status.status() == null ? null
                    : new Spawn(status.status().label(), statusStyle(status.status()), originOf(status.target()));
            case BattleEvent.CommandRejected rejected -> rejected.reason() == null || rejected.reason().isEmpty() ? null
                    : new Spawn(rejected.reason(), REJECT, Origin.COMMAND_WINDOW);
            case null, default -> null;
        };
    }

    private static Origin originOf(CombatantId id) {
        return id == CombatantId.MONK ? Origin.MONK : Origin.ARCHON;
    }

    // Anything that hurt shows at least 1: "0" reads as a miss.
    private static String amount(float value) {
        return String.valueOf(value > 0f ? Math.max(1, Math.round(value)) : 0);
    }

    /** Queues a floater. Public so other HUD layers can speak through the same channel. */
    public void spawn(String words, Style style, Origin origin, BattleView view) {
        if (words == null || words.isEmpty() || style == null || origin == null) return;
        Vector3f world = null;
        CombatantId body = origin == Origin.MONK ? CombatantId.MONK : origin == Origin.ARCHON ? CombatantId.ARCHON : null;
        if (body != null && stage != null && view != null) {
            CombatantView who = view.combatant(body);
            if (who != null && who.pose() != null) world = stage.bodyPoint(body, who.pose(), ANCHOR_HEIGHT);
        }
        pending.add(new Pending(new Spawn(words, style, origin), world));
        while (pending.size() > MAX_LIVE) pending.remove(0);
    }

    // ─────────────────────────────────────────────── Placement

    /**
     * The band of the screen floaters live in, {@code {left, top, right, bottom}}: below the action
     * banner (so a number never sits on the enemy plate) and above the party window. The help strip
     * and command window are inside it on purpose: the monk often stands behind them, and a number
     * pinned above them would detach from the body it belongs to.
     */
    public static float[] band(int w, int h, float rawUiScale, float scale) {
        float[] banner = FocusBattleLayout.actionBannerRect(w, h, rawUiScale);
        float[] party = FocusBattleLayout.partyWindowRect(w, h, rawUiScale);
        float margin = 40f * scale;
        float top = banner[1] + banner[3] + 26f * scale;
        return new float[]{margin, top, Math.max(margin, w - margin), Math.max(top, party[1] - 18f * scale)};
    }

    /** Where {@code origin}'s floaters start when they have no body on screen: its owner's HUD window. */
    public static float[] hudPoint(Origin origin, int w, int h, float rawUiScale, float scale) {
        float[] band = band(w, h, rawUiScale, scale);
        float[] party = FocusBattleLayout.partyWindowRect(w, h, rawUiScale);
        return switch (origin) {
            // Under the banner, top-centre.
            case ARCHON -> new float[]{w / 2f, Math.min(band[3], band[1] + 44f * scale)};
            case MONK, PARTY_WINDOW -> new float[]{party[0] + party[2] * 0.5f, band[3]};
            // The party window's left shoulder, clear of monk damage numbers.
            case QI_ROW -> new float[]{party[0] + party[2] * 0.18f, band[3]};
            // Just above the help strip, over the command window's column.
            case COMMAND_WINDOW -> {
                float[] cmd = FocusBattleLayout.commandWindowRect(w, h, rawUiScale);
                float[] help = FocusBattleLayout.helpStripRect(w, h, rawUiScale);
                yield new float[]{Math.max(band[0] + cmd[2] * 0.5f, cmd[0] + cmd[2] * 0.5f),
                        Math.max(band[1], help[1] - 18f * scale)};
            }
        };
    }

    /**
     * Gives every queued floater its screen position from the camera and window of <em>this</em>
     * frame and lets it go. Later frames never look at the camera again.
     */
    public void resolvePending(int w, int h, float rawUiScale, Matrix4fc viewProjection) {
        if (pending.isEmpty() || w <= 0 || h <= 0) return;
        resolvePending(w, h, rawUiScale, FocusBattleLayout.effectiveScale(w, h, rawUiScale), viewProjection);
    }

    private void resolvePending(int w, int h, float rawUiScale, float scale, Matrix4fc viewProjection) {
        if (pending.isEmpty() || w <= 0 || h <= 0) return;
        float[] band = band(w, h, rawUiScale, scale);
        text.sides(band[0], band[2]).band(band[1], band[3]);
        for (Pending p : pending) {
            float[] hud = hudPoint(p.spawn().origin(), w, h, rawUiScale, scale);
            MWorldMarker.Anchor at = MWorldMarker.orFallback(
                    MWorldMarker.project(viewProjection, p.world(), w, h), hud[0], hud[1]);
            text.spawn(p.spawn().text(), p.spawn().style(), at.x(), at.y());
        }
        pending.clear();
    }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null) return;
        resolvePending(windowWidth, windowHeight, rawUiScale, uiScale, viewProjection);
        text.render(ui, uiScale);
    }
}
