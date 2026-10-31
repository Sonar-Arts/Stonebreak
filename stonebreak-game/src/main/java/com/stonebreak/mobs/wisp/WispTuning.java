package com.stonebreak.mobs.wisp;

/**
 * Every number that decides how a Wisp plays, in one place, so balancing it is editing a record
 * rather than hunting through behaviours. Times are seconds, distances blocks, damage hit points.
 *
 * <p>The fright meter's gains and decay are set so that a Wisp left to its routine — an ability
 * about every 25 seconds, nobody fighting back — snaps into its attack after three to four minutes
 * ({@code WispMindTest} holds it to that).
 *
 * @param meterMax                 fright meter at which the Wisp stops scaring and attacks
 * @param decayPerSec              meter drained per second while it stalks
 * @param peekGain                 meter for a Peek-a-boo the victim looked at
 * @param peekIgnoredGain          meter for a Peek-a-boo nobody noticed
 * @param veilGain                 meter for using Invisibility
 * @param wooshGain                meter for a Woosh that landed
 * @param spottedGain              meter for being spotted sneaking up
 * @param playerHitGain            meter for being hit by the player
 * @param calmMeter                where the meter falls back to once an attack is over (victim dead or gone)
 * @param peekCooldown             seconds between Peek-a-boos
 * @param wooshCooldown            seconds between Wooshes
 * @param veilCooldown             seconds between uses of Invisibility
 * @param sharedGapMin             least quiet time after a Peek-a-boo or Woosh before either again
 * @param sharedGapMax             most quiet time after a Peek-a-boo or Woosh
 * @param veilSeconds              how long Invisibility (and every escape veil) lasts
 * @param spottedRange             how close a victim must be to spot it sneaking up
 * @param spottedMemory            seconds a spotting keeps it cautious about Wooshing
 * @param wooshBackTurned          seconds the victim's back must stay turned before a Woosh
 * @param wooshBackTurnedMoving    the same, for a victim on the move (easier to sneak up on)
 * @param wooshMaxFranticness      victim turn rate (degrees per second) above which it daren't Woosh
 * @param wooshStartRange          furthest it starts sneaking up from
 * @param wooshDamage              the Woosh's sting
 * @param peekBandMin              nearest it peeks from
 * @param peekBandMax              furthest it peeks from
 * @param lurkBandMin              nearest it lurks while stalking
 * @param lurkBandMax              furthest it lurks while stalking
 * @param trackedBandMin           nearest it keeps to a victim who is tracking it
 * @param trackedBandMax           furthest it keeps to a victim who is tracking it
 * @param evadeCooldown            seconds after slipping a victim's eye before it slips again
 * @param cornerRange              a victim this close when a flight fails has it cornered
 * @param cornerFightSeconds       how long a cornered Wisp fights before trying to get away again
 * @param scratchReach             how close the victim must be to be scratched
 * @param scratchDamage            the scratch's damage
 * @param meleeBreakRange          in an attack, closer than this and it breaks away
 * @param meleeBreakCooldown       seconds between breaking away
 * @param kiteDistance             how far it tries to keep from the victim in an attack
 * @param castIntervalMin          least time between fireballs
 * @param castIntervalMax          most time between fireballs
 * @param castRange                furthest it throws from
 * @param orbDamage                a fireball's hit
 * @param orbBurnSeconds           how long a fireball leaves its victim burning
 * @param orbBurnDps               the burn's damage per second
 * @param dawnFog                  night fog below this and the Wisp leaves for the day
 */
public record WispTuning(
        float meterMax, float decayPerSec,
        float peekGain, float peekIgnoredGain, float veilGain, float wooshGain, float spottedGain,
        float playerHitGain, float calmMeter,
        float peekCooldown, float wooshCooldown, float veilCooldown,
        float sharedGapMin, float sharedGapMax,
        float veilSeconds,
        float spottedRange, float spottedMemory,
        float wooshBackTurned, float wooshBackTurnedMoving, float wooshMaxFranticness,
        float wooshStartRange, float wooshDamage,
        float peekBandMin, float peekBandMax, float lurkBandMin, float lurkBandMax,
        float trackedBandMin, float trackedBandMax, float evadeCooldown,
        float cornerRange, float cornerFightSeconds,
        float scratchReach, float scratchDamage, float meleeBreakRange, float meleeBreakCooldown,
        float kiteDistance, float castIntervalMin, float castIntervalMax, float castRange,
        float orbDamage, float orbBurnSeconds, float orbBurnDps,
        float dawnFog) {

    public static final WispTuning DEFAULT = new WispTuning(
            100f, 0.04f,
            16f, 8f, 6f, 20f, 10f,
            35f, 50f,
            35f, 45f, 12f,
            10f, 25f,
            10f,
            6f, 30f,
            1.5f, 1.0f, 90f,
            27f, 1.5f,
            12f, 24f, 15f, 30f,
            20f, 34f, 4f,
            10f, 5f,
            2.2f, 2.0f, 3.0f, 6f,
            10f, 2.5f, 3.5f, 33f,
            3.0f, 3.0f, 0.5f,
            0.2f);
}
