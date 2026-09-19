package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.CombatantId;

/**
 * Strike and Stunning Strike: dash in, the attack clip, dash home. The blow lands on the clip's
 * authored contact. A Martial Surge adds a kick after the Strike, landing on the kick's contact.
 */
final class StrikeAction extends BattleAction {

    private final BattleCommand command;
    private final MeleeTimeline timeline;
    private final float[] contacts;
    private final float damage;
    private final float focusPerHit;
    private final boolean stuns;
    private final float atbRestart;

    private int nextHit;

    private StrikeAction(BattleCommand command, BattleConfig config, BattleClip clip, int bonusKicks, float damage,
                         boolean stuns, float atbRestart) {
        this.command = command;
        this.timeline = new MeleeTimeline(config.melee());
        this.contacts = new float[1 + Math.max(0, bonusKicks)];
        this.contacts[0] = timeline.add(clip, 1f).contact(0);
        for (int i = 1; i < contacts.length; i++) {
            contacts[i] = timeline.add(MonkClips.KICK, 1f).contact(0);
        }
        this.damage = damage;
        this.focusPerHit = config.resources().focusPerStrikeHit();
        this.stuns = stuns;
        this.atbRestart = atbRestart;
    }

    static StrikeAction strike(BattleConfig config, boolean surged) {
        BattleConfig.Melee m = config.melee();
        return new StrikeAction(BattleCommand.STRIKE, config, MonkClips.STRIKE, surged ? m.surgeBonusHits() : 0,
                m.strikeDamage(), false, m.strikeAtbRestart());
    }

    static StrikeAction stunningStrike(BattleConfig config) {
        BattleConfig.Melee m = config.melee();
        return new StrikeAction(BattleCommand.STUNNING_STRIKE, config, MonkClips.STUNNING_STRIKE, 0,
                m.stunningStrikeDamage(), true, m.stunningStrikeAtbRestart());
    }

    @Override CombatantId actor() { return CombatantId.MONK; }
    @Override String displayName() { return command.displayName(); }
    @Override BattleCommand command() { return command; }
    @Override float atbRestart() { return atbRestart; }
    @Override float duration() { return timeline.duration(); }

    private float nextCueTime() {
        return nextHit < contacts.length ? contacts[nextHit] : duration();
    }

    @Override
    float timeToNextCue() {
        return Math.max(0f, nextCueTime() - elapsed);
    }

    @Override
    protected void fireCue(BattleContext ctx) {
        elapsed = nextCueTime(); // snap: accumulated float error never shifts later cues
        if (nextHit >= contacts.length) {
            finish();
            return;
        }
        int hit = nextHit++;
        ctx.monkHit(damage, 1f, hit, contacts.length);
        ctx.focus.gain(focusPerHit);
        if (stuns && hit == 0 && ctx.archon.alive()) ctx.stunArchon(); // never stun a corpse
    }

    @Override
    void applyPose(BattleContext ctx) {
        timeline.pose(elapsed, ctx.monk);
    }
}
