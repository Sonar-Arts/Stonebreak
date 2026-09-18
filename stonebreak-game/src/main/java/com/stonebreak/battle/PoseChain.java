package com.stonebreak.battle;

import java.util.ArrayList;
import java.util.List;

/**
 * What a combatant plays when no action is driving it: a run of clip links in order (a reaction, the
 * walk home, a victory flourish) that ends in a loop. The pose is a pure function of the chain's own
 * clock, so however the frame time is sliced the same clip frame comes out.
 *
 * <p>Links switch exactly at clip ends. The combat clips share their end poses by authoring, which is
 * what makes a chain seamless without any cross-fade (the stage can only draw one state at a time).
 */
final class PoseChain {

    /** @param from clip time the link starts at; @param length seconds the link lasts */
    private record Link(BattleClip clip, float from, float length) {}

    /** The resolved pose of a chain at its current time. */
    record Frame(BattleClip clip, float clipTime) {}

    private final List<Link> links = new ArrayList<>(4);
    private BattleClip ending;
    private float time;

    /** Appends a one-shot played from its start to its end. */
    PoseChain then(BattleClip clip) {
        return then(clip, 0f, clip.duration());
    }

    /** Appends {@code length} seconds of {@code clip} starting at clip time {@code from}. */
    PoseChain then(BattleClip clip, float from, float length) {
        if (length > 0f) links.add(new Link(clip, Math.max(0f, from), length));
        return this;
    }

    /** The loop held once every link has played; without one the combatant's resting loop is used. */
    PoseChain endingIn(BattleClip loop) {
        ending = loop;
        return this;
    }

    void advance(float dt) {
        if (dt > 0f) time += dt;
    }

    /** @param rest the loop to fall back on when this chain names no ending of its own */
    Frame frame(BattleClip rest) {
        float remaining = time;
        for (Link link : links) {
            if (remaining < link.length()) {
                return new Frame(link.clip(), link.clip().timeAt(link.from() + remaining));
            }
            remaining -= link.length();
        }
        BattleClip loop = ending != null ? ending : rest;
        return new Frame(loop, loop.timeAt(remaining));
    }
}
