package com.stonebreak.battle;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixed shape of a dash-in attack: dash out, one or more attack clips back to back, dash home.
 * Every time is absolute from action start and nothing a player presses moves it, so the monk's pose
 * (and therefore where each fist is) is a pure function of the action clock.
 */
final class MeleeTimeline {

    /**
     * One attack clip on the timeline.
     *
     * @param start    action time the clip starts at
     * @param speed    clip seconds per action second
     * @param standoff true for a kick: the monk stands a recoil-length farther back while it plays
     */
    record Span(BattleClip clip, float start, float speed, boolean standoff) {
        float length() { return clip.duration() / speed; }
        float end() { return start + length(); }
        /** Action time at which the clip's {@code cue}-th authored contact happens. */
        float contact(int cue) { return start + clip.cue(cue) / speed; }
        float clipTime(float actionTime) { return (actionTime - start) * speed; }
    }

    private final float dashSeconds;
    private final float standoffEase;
    private final float kickStandoff;
    private final List<Span> spans = new ArrayList<>(3);
    private float attackEnd;

    MeleeTimeline(BattleConfig.Melee melee) {
        this.dashSeconds = Math.max(0f, melee.dashSeconds());
        this.standoffEase = melee.standoffEaseSeconds();
        this.kickStandoff = melee.kickStandoff();
        this.attackEnd = dashSeconds;
    }

    /** Appends a clip right after the previous one (the first starts as the dash out ends). */
    Span add(BattleClip clip, float speed) {
        Span span = new Span(clip, attackEnd, speed > 0f ? speed : 1f, clip == MonkClips.KICK);
        spans.add(span);
        attackEnd = span.end();
        return span;
    }

    /** When the dash home starts. */
    float attackEnd() { return attackEnd; }

    float duration() { return attackEnd + dashSeconds; }

    void pose(float time, Combatant monk) {
        if (time < dashSeconds) {
            monk.drive(MonkClips.COMBAT_DASH, time, BattleAction.ease(BattleAction.ratio(time, dashSeconds)), 0f);
            return;
        }
        for (Span span : spans) {
            if (time < span.end()) {
                float standoff = span.standoff()
                        ? kickStandoff * BattleAction.envelope(time, span.start(), span.end(), standoffEase) : 0f;
                monk.drive(span.clip(), span.clipTime(time), 1f, standoff);
                return;
            }
        }
        float back = time - attackEnd;
        monk.drive(MonkClips.COMBAT_DASH, back, 1f - BattleAction.ease(BattleAction.ratio(back, dashSeconds)), 0f);
    }
}
