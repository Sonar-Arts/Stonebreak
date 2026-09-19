package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MPromptStrip;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MSymbol;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * E10, the Focus Combo strip: a thin adapter that feeds one {@link MPromptStrip} from
 * {@link TimedInputState}'s snapshot of the string. The widget draws everything (frame, cells,
 * arrows, key letters, stamps, the draining timer, captions, the flash word) and owns the motion of
 * its own parts; this class only translates battle vocabulary into the widget's:
 *
 * <ul>
 *   <li>{@link ComboDirection} → arrow glyph + W/A/S/D legend;</li>
 *   <li>{@link TimedGrade} → {@code RESOLVED_BEST / RESOLVED_OK / RESOLVED_FAIL};</li>
 *   <li>the landed-blow count → the counter; a flawless finish → the "FLAWLESS!" word.</li>
 * </ul>
 *
 * <p>Fed from the snapshot rather than the live prompt: the last grade arrives in the same frame the
 * prompt disappears, and the strip stays up through the finisher.
 */
public final class ComboStripOverlay {

    static final String CAPTION = "FOCUS COMBO";
    static final String FLAWLESS_WORD = "FLAWLESS!";
    public static final float FLAWLESS_SECONDS = 1.2f;

    private final MPromptStrip strip = new MPromptStrip()
            .caption(CAPTION)
            .accent(BattlePalette.FOCUS)
            .flashColor(BattlePalette.FOCUS)
            .flashDuration(FLAWLESS_SECONDS)
            // Calm while there is time, through amber, to alarm red as the step runs out.
            .timerColors(MStyle.VITAL_CRIT, MStyle.VITAL_WARN, BattlePalette.ATB)
            .slide(0f);

    private List<ComboDirection> shown = List.of();
    private boolean flawlessAnnounced;

    /** The widget, for geometry and state queries ({@code cellRect}, {@code stateOf}, {@code flashRect}). */
    public MPromptStrip strip() {
        return strip;
    }

    public void reset() {
        shown = List.of();
        flawlessAnnounced = false;
        strip.steps(List.of()).current(-1).upNext(-1).timer(1f).counter("").slide(0f).flashText(null, -1f);
    }

    /**
     * Once per frame, after {@link TimedInputState#update}: the widget's own timers advance by
     * {@code dt}, then this frame's results are fed in (so a fresh stamp or flash starts at age 0).
     */
    public void update(TimedInputState state, float dt) {
        strip.update(dt);
        if (state == null) return;

        if (!state.comboSequence.equals(shown)) {
            shown = List.copyOf(state.comboSequence);
            strip.steps(steps(shown));
        }
        for (int i = 0; i < shown.size(); i++) {
            MPromptStrip.State wanted = resolvedState(i < state.comboResults.length ? state.comboResults[i] : null);
            MPromptStrip.State drawn = strip.stateOf(i);
            boolean drawnResolved = drawn != MPromptStrip.State.CURRENT && drawn != MPromptStrip.State.UPCOMING;
            if (wanted != (drawnResolved ? drawn : null)) strip.markResolved(i, wanted);
        }

        strip.current(state.comboIndex);
        // Between prompts nothing is awaiting input; the one about to open is lit (not enlarged, no
        // timer) so the eye is already on it when it does.
        strip.upNext(state.comboIndex < 0 && !state.comboFinished ? state.comboNextIndex() : -1);
        strip.timer(timeLeft(state.comboStepElapsed, state.comboStepDuration));
        strip.counter(counter(state.comboLanded()));
        strip.slide(state.comboSlide);

        if (state.comboFlawless()) {
            if (!flawlessAnnounced) strip.flashText(FLAWLESS_WORD, 0f);
            flawlessAnnounced = true;
        } else {
            // A new string (or a reset) takes the stage: the last one's word must not linger over it.
            if (flawlessAnnounced) strip.flashText(null, -1f);
            flawlessAnnounced = false;
        }
    }

    /** Places the widget for this window: the layout's reserved rect, sliding in from below the screen. */
    public MPromptStrip layout(int w, int h, float scale, float rawUiScale) {
        float[] rest = FocusBattleLayout.comboStripRect(w, h, rawUiScale);
        return strip.scale(scale).bounds(rest[0], rest[1], rest[2], rest[3])
                .slideDistance(h - rest[1] + 12f * scale);
    }

    public void paint(MasonryUI ui, int w, int h, float scale, float rawUiScale) {
        if (ui == null || strip.slide() <= 0f) return;
        layout(w, h, scale, rawUiScale).render(ui);
    }

    // ─────────────────────────────────────────────── Battle vocabulary → widget vocabulary

    static List<MPromptStrip.Step> steps(List<ComboDirection> sequence) {
        List<MPromptStrip.Step> steps = new ArrayList<>(sequence.size());
        for (ComboDirection dir : sequence) steps.add(new MPromptStrip.Step(glyphFor(dir), keyFor(dir)));
        return steps;
    }

    public static String keyFor(ComboDirection dir) {
        return switch (dir) {
            case UP -> "W";
            case LEFT -> "A";
            case DOWN -> "S";
            case RIGHT -> "D";
        };
    }

    public static MSymbol glyphFor(ComboDirection dir) {
        return switch (dir) {
            case UP -> MSymbol.ARROW_UP;
            case LEFT -> MSymbol.ARROW_LEFT;
            case DOWN -> MSymbol.ARROW_DOWN;
            case RIGHT -> MSymbol.ARROW_RIGHT;
        };
    }

    /** The stamp a grade earns; null for a cell with no grade yet. */
    static MPromptStrip.State resolvedState(TimedGrade grade) {
        if (grade == null) return null;
        return switch (grade) {
            case PERFECT -> MPromptStrip.State.RESOLVED_BEST;
            case GOOD -> MPromptStrip.State.RESOLVED_OK;
            case MISS -> MPromptStrip.State.RESOLVED_FAIL;
        };
    }

    static String counter(int landed) {
        return landed + (landed == 1 ? " HIT" : " HITS");
    }

    /** Share of the step's time that is left, 1 → 0. */
    static float timeLeft(float stepElapsed, float stepDuration) {
        return stepDuration <= 0f ? 0f : 1f - stepElapsed / stepDuration;
    }
}
