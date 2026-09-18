package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FlowLayout;
import com.stonebreak.ui.focusBattle.FlowTheme;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import com.stonebreak.ui.focusBattle.WorldProjection;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * E11 — floating numbers and words: damage, heals, Qi and Focus gains, timed-input grades, status
 * names and command rejections. Screen-space, drawn inside the HUD's single Skija frame.
 *
 * <p>A floater born from something in the world captures its <b>screen</b> position once — the
 * first time it meets a camera matrix after spawning — and from then on moves purely in screen
 * space (a ballistic arc, then a fade). The cinematic camera cuts constantly; a number that
 * re-projected every frame would teleport with each cut. When the anchor is off-screen the floater
 * starts from its owner's HUD window instead.
 *
 * <p>Deterministic: the scatter of a floater is a function of how many were spawned before it since
 * {@link #reset}, never of a clock or an unseeded random.
 */
public final class BattleFloaters implements SkijaFocusBattleRenderer.Layer {

    public static final int MAX_LIVE = 24;
    public static final float LIFETIME_SECONDS = 0.9f;
    /** Body height the world anchor sits at (chest/head, where the eye already is on a hit). */
    public static final float ANCHOR_HEIGHT = 0.8f;

    /** Simultaneous floaters on one anchor leave this far apart in time… */
    private static final float STAGGER_SECONDS = 0.07f;
    /** …and a run of spawns counts as "simultaneous" until the anchor has been quiet this long. */
    private static final float STAGGER_MEMORY_SECONDS = 0.3f;
    private static final float FADE_FROM = 0.55f;
    private static final float POP_SECONDS = 0.12f;

    /**
     * Vertical lane a floater starts in, relative to its anchor. A single blow can raise a number, a
     * grade word and a status name in the same frame; lanes keep the three from landing on one spot.
     */
    public enum Lane {
        NUMBER(0f), WORD(-58f), STATUS(62f);

        final float offset;

        Lane(float offset) { this.offset = offset; }
    }

    /** Look of a floater: colour, size, lane and how hard it is thrown. */
    public enum Style {
        DAMAGE(FlowTheme.FLOAT_DAMAGE, FlowTheme.FS_FLOAT, 1f, Lane.NUMBER),
        CRITICAL(FlowTheme.FLOAT_CRIT, FlowTheme.FS_FLOAT_CRIT, 1.15f, Lane.NUMBER),
        BLOCKED(FlowTheme.FLOAT_BLOCK, FlowTheme.FS_FLOAT_SMALL, 0.7f, Lane.NUMBER),
        PARRIED(FlowTheme.FLOAT_PARRY, FlowTheme.FS_FLOAT_WORD, 0.8f, Lane.WORD),
        HEAL(FlowTheme.FLOAT_HEAL, FlowTheme.FS_FLOAT, 0.8f, Lane.NUMBER),
        QI(FlowTheme.FLOAT_QI, FlowTheme.FS_FLOAT_SMALL, 0.7f, Lane.NUMBER),
        FOCUS(FlowTheme.GOLD, FlowTheme.FS_FLOAT_WORD, 0.75f, Lane.WORD),
        PERFECT(FlowTheme.FLOAT_PERFECT, FlowTheme.FS_FLOAT_WORD, 0.85f, Lane.WORD),
        GOOD(FlowTheme.FLOAT_GOOD, FlowTheme.FS_FLOAT_WORD, 0.8f, Lane.WORD),
        MISS(FlowTheme.FLOAT_MISS, FlowTheme.FS_FLOAT_SMALL, 0.6f, Lane.WORD),
        STATUS_GOOD(FocusBattleTheme.CHIP_GOOD, FlowTheme.FS_FLOAT_SMALL, 0.7f, Lane.STATUS),
        STATUS_BAD(FocusBattleTheme.CHIP_BAD, FlowTheme.FS_FLOAT_SMALL, 0.7f, Lane.STATUS),
        REJECT(FlowTheme.FLOAT_REJECT, FlowTheme.FS_FLOAT_SMALL, 0.55f, Lane.NUMBER);

        final int color;
        final float fontSize;
        final float energy;
        final Lane lane;

        Style(int color, float fontSize, float energy, Lane lane) {
            this.color = color;
            this.fontSize = fontSize;
            this.energy = energy;
            this.lane = lane;
        }

        public int color() { return color; }
        public float fontSize() { return fontSize; }
        public Lane lane() { return lane; }
    }

    /** Where a floater starts. */
    public enum Origin { MONK, ARCHON, PARTY_WINDOW, QI_ROW, COMMAND_WINDOW }

    /** One live floater. Positions and velocities are in window pixels once {@link #placed}. */
    public static final class Floater {
        final String text;
        final Style style;
        final Origin origin;
        /** World anchor captured at spawn; null for HUD-anchored floaters. */
        final Vector3f world;
        final int serial;
        final int slot;
        float delay;
        float age;
        boolean placed;
        float x, y, vx, vy, gravity, scale = 1f;

        Floater(String text, Style style, Origin origin, Vector3f world, int serial, int slot, float delay) {
            this.text = text;
            this.style = style;
            this.origin = origin;
            this.world = world;
            this.serial = serial;
            this.slot = slot;
            this.delay = delay;
        }

        public String text() { return text; }
        public Style style() { return style; }
        public Origin origin() { return origin; }
        public boolean placed() { return placed; }
        public float age() { return age; }
        /** True once its stagger delay has run out. */
        public boolean started() { return delay <= 0f; }

        /** Current screen position {@code {x, y}}; meaningful once {@link #placed()}. */
        public float[] position() {
            return new float[]{x + vx * age, y + vy * age + 0.5f * gravity * age * age};
        }

        public float alpha() {
            float t = age / LIFETIME_SECONDS;
            return t <= FADE_FROM ? 1f : Math.max(0f, 1f - (t - FADE_FROM) / (1f - FADE_FROM));
        }
    }

    private final List<Floater> live = new ArrayList<>();
    /** Stagger bookkeeping per (origin, lane): how many spawned in the current burst, and how long ago. */
    private final int[] recent = new int[Origin.values().length * Lane.values().length];
    private final float[] quiet = new float[Origin.values().length * Lane.values().length];
    private BattleStageLayout stage;
    private int spawned;
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
        live.clear();
        java.util.Arrays.fill(recent, 0);
        java.util.Arrays.fill(quiet, 0f);
        spawned = 0;
    }

    /** The live floaters, oldest first (read-only; for tests and debugging). */
    public List<Floater> live() {
        return Collections.unmodifiableList(live);
    }

    // ─────────────────────────────────────────────── Update + spawn

    /** Ages the live floaters, then spawns this frame's. */
    public void update(float dt, BattleView view, List<BattleEvent> events) {
        float step = Math.max(0f, dt);
        for (Floater f : live) {
            if (f.delay > 0f) f.delay -= step;
            else if (f.placed) f.age += step;
        }
        live.removeIf(f -> f.age >= LIFETIME_SECONDS);
        for (int i = 0; i < recent.length; i++) {
            quiet[i] += step;
            if (quiet[i] >= STAGGER_MEMORY_SECONDS) recent[i] = 0;
        }
        if (view == null || events == null) return;
        for (BattleEvent event : events) spawnFor(event, view);
    }

    private void spawnFor(BattleEvent event, BattleView view) {
        switch (event) {
            case BattleEvent.DamageDealt hit -> {
                Origin at = originOf(hit.target());
                BattleEvent.DamageFlavor flavor = hit.flavor() == null ? BattleEvent.DamageFlavor.NORMAL : hit.flavor();
                switch (flavor) {
                    case PARRIED -> spawn("PARRY!", Style.PARRIED, at, view);
                    case BLOCKED -> spawn("BLOCK " + amount(hit.amount()), Style.BLOCKED, at, view);
                    case CRITICAL -> spawn(amount(hit.amount()) + "!", Style.CRITICAL, at, view);
                    case NORMAL -> spawn(amount(hit.amount()), Style.DAMAGE, at, view);
                }
            }
            case BattleEvent.Healed heal -> spawn("+" + amount(heal.amount()), Style.HEAL, originOf(heal.target()), view);
            case BattleEvent.QiChanged qi -> {
                if (qi.delta() > 0) spawn("+" + qi.delta() + " Qi", Style.QI, Origin.QI_ROW, view);
            }
            case BattleEvent.FocusFull full -> spawn("FOCUS MAX", Style.FOCUS, Origin.MONK, view);
            case BattleEvent.PromptResolved resolved -> {
                // A parry's verdict already arrives as PARRIED / BLOCKED damage: one word, not two.
                if (gradeWords && resolved.kind() != PromptKind.PARRY && resolved.grade() != null) {
                    spawn(resolved.grade().name(), styleOf(resolved.grade()), Origin.ARCHON, view);
                }
            }
            case BattleEvent.StatusApplied status -> {
                if (status.status() != null) {
                    spawn(status.status().label(), status.status().beneficial() ? Style.STATUS_GOOD : Style.STATUS_BAD,
                            originOf(status.target()), view);
                }
            }
            case BattleEvent.CommandRejected rejected -> {
                String reason = rejected.reason();
                if (reason != null && !reason.isEmpty()) spawn(reason, Style.REJECT, Origin.COMMAND_WINDOW, view);
            }
            default -> { }
        }
    }

    private static Origin originOf(CombatantId id) {
        return id == CombatantId.MONK ? Origin.MONK : Origin.ARCHON;
    }

    private static Style styleOf(TimedGrade grade) {
        return switch (grade) {
            case PERFECT -> Style.PERFECT;
            case GOOD -> Style.GOOD;
            case MISS -> Style.MISS;
        };
    }

    // Anything that hurt shows at least 1: "0" reads as a miss.
    private static String amount(float value) {
        return String.valueOf(value > 0f ? Math.max(1, Math.round(value)) : 0);
    }

    /** Adds a floater. Public so other HUD layers can speak through the same channel. */
    public Floater spawn(String text, Style style, Origin origin, BattleView view) {
        if (text == null || text.isEmpty() || style == null || origin == null) return null;
        Vector3f world = null;
        CombatantId body = origin == Origin.MONK ? CombatantId.MONK : origin == Origin.ARCHON ? CombatantId.ARCHON : null;
        if (body != null && stage != null && view != null) {
            CombatantView who = view.combatant(body);
            if (who != null && who.pose() != null) world = stage.bodyPoint(body, who.pose(), ANCHOR_HEIGHT);
        }
        int burst = origin.ordinal() * Lane.values().length + style.lane.ordinal();
        int slot = recent[burst]++;
        quiet[burst] = 0f;
        Floater f = new Floater(text, style, origin, world, spawned++, slot, slot * STAGGER_SECONDS);
        live.add(f);
        while (live.size() > MAX_LIVE) live.remove(0);   // the oldest is the most faded
        return f;
    }

    // ─────────────────────────────────────────────── Placement

    /**
     * Gives every not-yet-placed floater its screen position and launch velocity, from the camera
     * and window of <em>this</em> frame. Later frames never look at the camera again.
     */
    public void resolvePending(int w, int h, float rawUiScale, Matrix4fc viewProjection) {
        if (w <= 0 || h <= 0) return;
        float s = FocusBattleLayout.effectiveScale(w, h, rawUiScale);
        float[] bounds = FlowLayout.floaterBounds(w, h, rawUiScale);
        for (Floater f : live) {
            if (f.placed) continue;
            float[] at = f.world == null ? null : WorldProjection.toScreen(viewProjection, f.world, w, h, 0f);
            if (at == null) at = hudPoint(f.origin, w, h, rawUiScale);
            // A fan of slots so simultaneous hits never stack on one spot, in the style's own lane.
            float dx = (Math.floorMod(f.slot + 1, 3) - 1) * 64f * s;
            float dy = (f.style.lane.offset - Math.floorMod(f.slot, 4) * 30f) * s;
            // Alternate sides; the spread comes from the spawn serial, not from a random.
            float side = (f.serial & 1) == 0 ? 1f : -1f;
            float spread = 0.45f + 0.55f * (Math.floorMod(f.serial * 7, 5) / 4f);
            f.vx = side * spread * 62f * s * f.style.energy;
            f.vy = -(175f + 12f * Math.floorMod(f.serial * 3, 4)) * s * f.style.energy;
            f.gravity = 520f * s * f.style.energy;
            // Keep the whole arc inside the band: a floater born at the top edge would otherwise
            // rise straight into the action banner.
            float apex = f.vy * f.vy / (2f * f.gravity);
            f.x = Math.max(bounds[0], Math.min(bounds[2], at[0] + dx));
            f.y = Math.max(Math.min(bounds[1] + apex, bounds[3]), Math.min(bounds[3], at[1] + dy));
            f.scale = s;
            f.placed = true;
        }
    }

    private static float[] hudPoint(Origin origin, int w, int h, float rawUiScale) {
        return switch (origin) {
            case ARCHON -> FlowLayout.enemyFallbackPoint(w, h, rawUiScale);
            case MONK, PARTY_WINDOW -> FlowLayout.partyFallbackPoint(w, h, rawUiScale);
            case QI_ROW -> FlowLayout.qiFloaterPoint(w, h, rawUiScale);
            case COMMAND_WINDOW -> FlowLayout.commandFloaterPoint(w, h, rawUiScale);
        };
    }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || live.isEmpty()) return;
        resolvePending(windowWidth, windowHeight, rawUiScale, viewProjection);
        for (Floater f : live) {
            if (!f.placed || f.delay > 0f) continue;
            Font font = FocusBattleTheme.font(ui, f.style.fontSize, f.scale);
            if (font == null) continue;
            float[] p = f.position();
            // A long word near an edge (a rejection over the command window) stays fully on screen.
            float half = MPainter.measureWidth(font, f.text) / 2f + 6f;
            if (2f * half < windowWidth) p[0] = Math.max(half, Math.min(windowWidth - half, p[0]));
            // Lands big and settles: the pop sells the hit without moving the number's centre.
            float pop = 1f + 0.45f * (1f - Math.min(1f, f.age / POP_SECONDS));
            canvas.save();
            try {
                canvas.translate(p[0], p[1]);
                canvas.scale(pop, pop);
                FlowTheme.outlinedTextCentered(canvas, f.text, 0f, font.getSize() * 0.36f, font, f.style.color,
                        f.alpha());
            } finally {
                canvas.restore();
            }
        }
    }
}
