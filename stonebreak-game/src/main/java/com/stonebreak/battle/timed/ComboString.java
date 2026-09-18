package com.stonebreak.battle.timed;

import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.TimedGrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure grader for a Focus Combo input string: a fixed direction sequence and a per-prompt time limit.
 * The combo clip runs on toward each contact and freezes just short of it while a prompt is still
 * unanswered; answering before that freeze is what earns a PERFECT. Progress through the string and
 * the clip clock are the caller's.
 */
public record ComboString(List<ComboDirection> sequence, float stepDuration) {

    public ComboString {
        sequence = List.copyOf(sequence);
    }

    /** A seeded random string of {@code length} prompts. */
    public static ComboString random(Random random, int length, float stepDuration) {
        ComboDirection[] all = ComboDirection.values();
        List<ComboDirection> seq = new ArrayList<>(Math.max(0, length));
        for (int i = 0; i < length; i++) {
            seq.add(all[random.nextInt(all.length)]);
        }
        return new ComboString(seq, stepDuration);
    }

    public int length() {
        return sequence.size();
    }

    /**
     * Grade of pressing {@code direction} for prompt {@code index}, {@code stepTime} seconds after that
     * prompt appeared. A wrong direction, a late press or an out-of-range index is a MISS; a right one
     * is PERFECT while the clip was still running and GOOD once it had to freeze and wait.
     *
     * @param clipFrozen true when the clip is being held for this prompt
     */
    public TimedGrade press(int index, ComboDirection direction, float stepTime, boolean clipFrozen) {
        if (index < 0 || index >= sequence.size() || direction != sequence.get(index)) return TimedGrade.MISS;
        if (expired(stepTime)) return TimedGrade.MISS;
        return clipFrozen ? TimedGrade.GOOD : TimedGrade.PERFECT;
    }

    /** True once the current prompt has run out unanswered. */
    public boolean expired(float stepTime) {
        return stepTime >= stepDuration;
    }
}
