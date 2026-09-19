package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.battle.timed.TimingRing;

/**
 * Flurry of Blows: dash in, ONE continuous flurry clip (never restarted per hit), dash home. A Martial
 * Surge appends a kick and a strike after it. Each authored contact has a timing ring whose windows
 * are centred on that contact.
 *
 * <p>The fists never wait for the player: every {@link BattleEvent.Impact} lands exactly on its
 * contact. A press grades the ring at once; the damage of a hit is dealt the moment both its contact
 * has landed and its ring is graded. For a press before the contact that is the contact itself (fist
 * and number coincide); a press in the late half of the window, or no press at all, pays out when the
 * ring resolves, at most the GOOD window after the fist.
 */
final class FlurryAction extends BattleAction {

    private final BattleConfig.Flurry tuning;
    private final BattleConfig.Resources resources;
    private final MeleeTimeline timeline;
    private final int hits;
    /** Action time of each hit's contact. */
    private final float[] contacts;
    /** Start of the clip each hit belongs to: a ring never opens before its clip does. */
    private final float[] clipStarts;
    private final TimedGrade[] grades;

    /** Hits whose contact has passed. */
    private int landed;
    /** Hits whose damage has been dealt ({@code <= landed}). */
    private int paid;
    /** The ring that is open, or the next one to open. */
    private int ring;
    private boolean ringOpen;
    private float ringOpenedAt;
    private TimingRing window;
    /** The cue {@link #timeToNextCue()} last counted down to; {@link #fireCue} snaps the clock onto it. */
    private float pendingCue;

    FlurryAction(BattleConfig config, boolean surged) {
        this.tuning = config.flurry();
        this.resources = config.resources();
        this.timeline = new MeleeTimeline(config.melee());

        int clipHits = Math.max(1, Math.min(tuning.hits(), MonkClips.FLURRY.cueCount()));
        int bonus = surged ? Math.max(0, tuning.surgeBonusHits()) : 0;
        this.hits = clipHits + bonus;
        this.contacts = new float[hits];
        this.clipStarts = new float[hits];
        this.grades = new TimedGrade[hits];

        MeleeTimeline.Span flurry = timeline.add(MonkClips.FLURRY, tuning.playbackSpeed());
        for (int i = 0; i < clipHits; i++) {
            contacts[i] = flurry.contact(i);
            clipStarts[i] = flurry.start();
        }
        for (int i = 0; i < bonus; i++) {
            // Kick first: it is the bigger move, and ending on a strike returns the monk to punching range.
            MeleeTimeline.Span extra = timeline.add(i % 2 == 0 ? MonkClips.KICK : MonkClips.STRIKE, 1f);
            contacts[clipHits + i] = extra.contact(0);
            clipStarts[clipHits + i] = extra.start();
        }
    }

    @Override CombatantId actor() { return CombatantId.MONK; }
    @Override String displayName() { return BattleCommand.FLURRY.displayName(); }
    @Override BattleCommand command() { return BattleCommand.FLURRY; }
    @Override float atbRestart() { return tuning.atbRestart(); }
    @Override float duration() { return timeline.duration(); }

    int hitCount() { return hits; }

    /** Action time of hit {@code index}'s contact. */
    float contactTime(int index) { return contacts[index]; }

    // ---- timeline -------------------------------------------------------------------------------

    private float nominalOpen(int index) {
        return Math.max(clipStarts[index], contacts[index] - tuning.ringLeadSeconds());
    }

    private float closeTime(int index) {
        return contacts[index] + Math.max(0f, tuning.goodWindowSeconds());
    }

    private float nextCueTime() {
        float next = duration();
        if (landed < hits) next = Math.min(next, contacts[landed]);
        if (ringOpen) next = Math.min(next, closeTime(ring));
        else if (ring < hits) next = Math.min(next, nominalOpen(ring));
        return Math.max(next, elapsed);
    }

    @Override
    float timeToNextCue() {
        pendingCue = nextCueTime();
        return Math.max(0f, pendingCue - elapsed);
    }

    @Override
    protected void fireCue(BattleContext ctx) {
        elapsed = pendingCue; // snap: accumulated float error never shifts later cues
        if (ringOpen && elapsed >= closeTime(ring)) resolve(TimedGrade.MISS, ctx); // ran out unpressed
        openRingIfDue(ctx);
        if (landed < hits && elapsed >= contacts[landed]) land(ctx);
        if (elapsed >= duration()) {
            // A ring still open at the very end (only with extreme tuning) resolves as a MISS so its
            // hit is paid: no blow is ever lost.
            if (ringOpen) resolve(TimedGrade.MISS, ctx);
            finish();
        }
    }

    @Override
    void pressConfirm(BattleContext ctx) {
        if (ringOpen) resolve(window.press(elapsed - ringOpenedAt), ctx);
    }

    /** Rings never overlap: the next one opens at its lead time or when the previous one is done, whichever is later. */
    private void openRingIfDue(BattleContext ctx) {
        while (ring < hits && grades[ring] != null) ring++; // already graded (it landed ungraded): never re-open
        if (ringOpen || ring >= hits || elapsed < nominalOpen(ring)) return;
        ringOpen = true;
        ringOpenedAt = elapsed;
        window = TimingRing.around(contacts[ring] - elapsed, tuning.perfectWindowSeconds(), tuning.goodWindowSeconds());
        ctx.raise(new BattleEvent.PromptOpened(PromptKind.RING));
    }

    private void resolve(TimedGrade grade, BattleContext ctx) {
        ringOpen = false;
        grades[ring] = grade;
        ctx.raise(new BattleEvent.PromptResolved(PromptKind.RING, grade, ring));
        ctx.stats.ring(grade);
        ring++;
        payOut(ctx);
        openRingIfDue(ctx);
    }

    private void land(BattleContext ctx) {
        int hit = landed++;
        if (grades[hit] == null && !(ringOpen && ring == hit)) {
            // Its ring never got to open (only possible with extreme tuning): an ungraded hit is a MISS.
            grades[hit] = TimedGrade.MISS;
            ctx.raise(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.MISS, hit));
            ctx.stats.ring(TimedGrade.MISS);
            if (ring == hit) ring++;
        }
        ctx.monkImpact(hit, hits);
        payOut(ctx);
    }

    /** Deals the damage of every hit that has both landed and been graded, in order. */
    private void payOut(BattleContext ctx) {
        while (paid < landed && grades[paid] != null) {
            TimedGrade grade = grades[paid++];
            float multiplier = switch (grade) {
                case PERFECT -> tuning.perfectMultiplier();
                case GOOD -> tuning.goodMultiplier();
                case MISS -> tuning.missMultiplier();
            };
            ctx.monkDamage(tuning.damage(), multiplier);
            float bonus = switch (grade) {
                case PERFECT -> resources.focusFlurryPerfectBonus();
                case GOOD -> resources.focusFlurryGoodBonus();
                case MISS -> 0f;
            };
            ctx.focus.gain(resources.focusPerFlurryHit() + bonus);
        }
    }

    @Override
    PromptView prompt(BattleContext ctx) {
        if (!ringOpen) return null;
        return new PromptView.Ring(ring, hits, elapsed - ringOpenedAt, window.duration(), window.perfectStart(),
                window.perfectEnd(), window.goodStart(), window.goodEnd());
    }

    @Override
    void applyPose(BattleContext ctx) {
        timeline.pose(elapsed, ctx.monk);
    }
}
