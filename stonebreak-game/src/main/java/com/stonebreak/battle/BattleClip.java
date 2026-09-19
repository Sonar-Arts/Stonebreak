package com.stonebreak.battle;

/**
 * One authored SBE animation state as the battle model sees it: its name, its length, whether it
 * loops, and the cue times (contacts / effects) the artists marked on it. Values live in
 * {@link MonkClips} and {@link ArchonClips}; a test checks them against the shipped {@code clips.json}.
 *
 * @param cues seconds from clip start, ascending; contact cues for attacks, effect cues for support moves
 */
record BattleClip(String state, float duration, boolean loop, float... cues) {

    int cueCount() {
        return cues.length;
    }

    float cue(int index) {
        return cues[index];
    }

    /**
     * Clip time for {@code elapsed} seconds of play: loops wrap, one-shots hold their last frame. The
     * SBE preview renderer does the same ({@code AnimSampler.wrapTime}); doing it here as well keeps
     * long-running loops precise and makes the published pose self-describing.
     */
    float timeAt(float elapsed) {
        if (duration <= 0f || !(elapsed > 0f)) return 0f;
        if (loop) return elapsed % duration;
        return Math.min(elapsed, duration);
    }
}
