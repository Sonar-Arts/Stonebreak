package com.stonebreak.battle.camera;

import com.stonebreak.battle.camera.CameraShot.End;
import com.stonebreak.battle.camera.CameraShot.Motion;
import com.stonebreak.battle.camera.CameraShot.Staging;
import com.stonebreak.battle.camera.CameraShot.Subject;
import com.stonebreak.battle.camera.ShotSequence.AdvanceOn;
import com.stonebreak.ui.startupIntro.tween.EasingType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.stonebreak.battle.camera.CameraShot.shot;
import static com.stonebreak.battle.camera.ShotSequence.sequence;

/**
 * Every authored camera sequence, as data, in one place. All numbers are blocks / degrees / seconds
 * in the anchor's frame (right, up, forward): for MONK, forward is toward the Archon and the 180°
 * side of the line is {@code right <= 0}; for ARCHON, forward is toward the monk and the same side
 * is {@code right >= 0}; for MIDPOINT / ARENA_CENTRE, forward is monk→Archon and the side is
 * {@code right <= 0}. Tune here; {@code ShotLibraryValidationTest} re-checks every shot against the
 * real arena.
 *
 * <p>Authored for the 7-block stand-off (a melee dash stops 2.2 blocks short, so attacker and victim
 * share the frame at every blow): tight two-shots are the default, the Archon reads screen-left and
 * the monk screen-right. Orbits stay under ~90°/s except the combo finisher; only the combo rolls.
 */
public final class ShotLibrary {

    private final Map<Situation, List<ShotSequence>> variants = new EnumMap<>(Situation.class);
    private final ShotSequence wide;

    private ShotLibrary() {
        wide = sequence("WIDE").cut(WIDE, AdvanceOn.NEVER).build();
        authorIntro();
        authorIdleAndTurnReady();
        authorMonkActions();
        authorArchonActions();
        authorFocusCombo();
        authorResults();
    }

    /** The shipped library. */
    public static ShotLibrary standard() { return new ShotLibrary(); }

    /** Variants for a situation, in authored order (never empty). */
    public List<ShotSequence> variants(Situation situation) {
        return variants.getOrDefault(situation, List.of(wide));
    }

    /** The static WIDE two-shot: reduced-motion pin and the last-resort fallback. */
    public ShotSequence wide() { return wide; }

    /** Every sequence in the library, fallback included. */
    public List<ShotSequence> all() {
        List<ShotSequence> out = new ArrayList<>();
        out.add(wide);
        variants.values().forEach(out::addAll);
        return out;
    }

    public ShotSequence byName(String name) {
        for (ShotSequence s : all()) {
            if (s.name().equals(name)) return s;
        }
        return null;
    }

    private void put(Situation situation, ShotSequence... sequences) {
        variants.put(situation, List.of(sequences));
    }

    // ---- anchor shorthands (right, up, forward) ----------------------------------------------------
    private static AnchoredPoint monk(float r, float u, float f) { return AnchoredPoint.at(CameraAnchor.MONK, r, u, f); }
    private static AnchoredPoint monkLive(float r, float u, float f) { return AnchoredPoint.following(CameraAnchor.MONK, r, u, f); }
    private static AnchoredPoint archon(float r, float u, float f) { return AnchoredPoint.at(CameraAnchor.ARCHON, r, u, f); }
    private static AnchoredPoint archonLive(float r, float u, float f) { return AnchoredPoint.following(CameraAnchor.ARCHON, r, u, f); }
    private static AnchoredPoint mid(float r, float u, float f) { return AnchoredPoint.at(CameraAnchor.MIDPOINT, r, u, f); }
    /** Halfway between the actors' LIVE feet: a two-shot that travels with whoever is advancing. */
    private static AnchoredPoint midLive(float r, float u, float f) { return AnchoredPoint.following(CameraAnchor.MIDPOINT, r, u, f); }
    private static AnchoredPoint centre(float r, float u, float f) { return AnchoredPoint.at(CameraAnchor.ARENA_CENTRE, r, u, f); }

    // ---- shared wide shots -------------------------------------------------------------------------
    /**
     * The home two-shot: low three-quarter angle from behind the monk's left, inside the court (the
     * cover blocks start at |x| 7.4), so the monk is large screen-right and the Archon faces us
     * screen-left. Feet sit about two thirds down the frame, clear of the bottom HUD windows.
     */
    private static final AnchoredPoint WIDE_EYE = mid(-5.4f, 1.9f, -4.3f);
    private static final AnchoredPoint WIDE_TARGET = mid(0.3f, 0.6f, -0.4f);
    private static final float WIDE_FOV = 50f;

    static final CameraShot WIDE = shot("WIDE", Motion.STATIC)
            .eye(WIDE_EYE).target(WIDE_TARGET).fov(WIDE_FOV).promptSafe().subject(Subject.BOTH).build();

    /** WIDE with a slow push round toward the profile (≈0.13 blocks/s, so still prompt-safe). */
    static final CameraShot WIDE_DRIFT = shot("WIDE_DRIFT", Motion.DOLLY)
            .eye(WIDE_EYE, mid(-6.0f, 1.8f, -3.4f)).target(WIDE_TARGET, mid(0.3f, 0.6f, -0.5f))
            .fov(WIDE_FOV).duration(9f).easing(EasingType.EaseInOutQuad).end(End.PING_PONG)
            .promptSafe().subject(Subject.BOTH).build();

    /** Low side-on profile from between the two −X cover blocks: the classic stand-off. */
    static final CameraShot WIDE_FLANK = shot("WIDE_FLANK", Motion.DOLLY)
            .eye(mid(-7.4f, 1.5f, -0.9f), mid(-7.4f, 1.6f, 0.5f)).target(mid(0f, 0.95f, -0.1f), mid(0f, 0.95f, 0.1f))
            .fov(48f).duration(9f).easing(EasingType.EaseInOutQuad).end(End.PING_PONG)
            .promptSafe().subject(Subject.BOTH).build();

    /** From behind the Archon's flank: the boss looms screen-left, the monk holds the right third. */
    static final CameraShot WIDE_NORTH = shot("WIDE_NORTH", Motion.DOLLY)
            .eye(mid(-5.6f, 2.4f, 6.6f), mid(-6.4f, 2.5f, 5.8f)).target(mid(0.5f, 0.8f, -0.4f))
            .fov(50f).duration(9f).easing(EasingType.EaseInOutQuad).end(End.PING_PONG)
            .promptSafe().subject(Subject.BOTH).build();

    // ---- intro (≈4.7 s) ----------------------------------------------------------------------------
    /** The HUD shows the "ICE ARCHON" name card over this stretch of the intro. */
    static final float INTRO_NAME_CARD_FROM = 1.7f;
    static final float INTRO_NAME_CARD_TO = 3.2f;

    private void authorIntro() {
        // Establishing sweep: starts high over the −X wall, swings 75° round that side while the look-at
        // slides from the court to the Archon, and lands on the low hero angle the next shot opens on
        // (a CRANE's end bearing is start + sweep: the start is placed 75° before the hero angle). The
        // hero shot then owns 1.7–3.2 s, exactly the HUD's name-card window, and the sequence ends
        // behind the party (WIDE).
        CameraShot crane = shot("INTRO_CRANE", Motion.CRANE)
                .eye(centre(-15.0f, 10f, 0.5f), archon(1.4f, 0.7f, 4.6f))
                .target(centre(0f, 1f, 0f), archon(0f, 1.55f, 0f)).sweep(75f)
                .fov(74f, 58f).duration(INTRO_NAME_CARD_FROM).easing(EasingType.EaseInOutQuad)
                .crossesLine().establishing().subject(Subject.ARCHON).build();
        CameraShot hero = shot("INTRO_ARCHON_HERO", Motion.DOLLY)
                .eye(archon(1.4f, 0.7f, 4.6f), archon(1.2f, 0.55f, 3.9f))
                .target(archon(0f, 1.55f, 0f), archon(0f, 1.6f, 0f))
                .fov(58f, 54f).duration(INTRO_NAME_CARD_TO - INTRO_NAME_CARD_FROM).easing(EasingType.EaseOutQuad)
                .subject(Subject.ARCHON).build();
        CameraShot monkSettle = shot("INTRO_MONK", Motion.DOLLY)
                .eye(monk(-1.9f, 1.1f, 2.6f), monk(-1.6f, 1.2f, 2.2f)).target(monk(0f, 1.2f, 0f))
                .fov(56f).duration(0.7f).easing(EasingType.EaseOutQuad).subject(Subject.MONK).build();
        put(Situation.INTRO, sequence("INTRO")
                .cut(crane, AdvanceOn.TIME)
                .cut(hero, AdvanceOn.TIME)
                .cut(monkSettle, AdvanceOn.TIME)
                .blend(0.8f, shot("INTRO_SETTLE", Motion.STATIC).eye(WIDE_EYE).target(WIDE_TARGET).fov(WIDE_FOV)
                        .duration(0.8f).promptSafe().subject(Subject.BOTH).build(), AdvanceOn.TIME)
                .build());
    }

    // ---- idle + turn ready -------------------------------------------------------------------------
    /** Long enough that the return from a close-up stays a glide, not a whip. */
    private static final float IDLE_BLEND_SECONDS = 0.9f;

    private void authorIdleAndTurnReady() {
        // Variant 0 is the home angle the director returns to after every action.
        put(Situation.IDLE,
                sequence("IDLE_WIDE").blend(IDLE_BLEND_SECONDS, WIDE_DRIFT, AdvanceOn.NEVER).build(),
                sequence("IDLE_FLANK").blend(IDLE_BLEND_SECONDS, WIDE_FLANK, AdvanceOn.NEVER).build(),
                sequence("IDLE_NORTH").blend(IDLE_BLEND_SECONDS, WIDE_NORTH, AdvanceOn.NEVER).build());

        // 0.4 s push-in to over the monk's left shoulder; the whole Archon stays in frame, left of the
        // monk, for target select.
        put(Situation.TURN_READY,
                sequence("TURN_OTS").blend(0.4f, shot("TURN_OTS", Motion.STATIC)
                        .eye(monk(-1.5f, 2.0f, -2.3f)).target(monk(0.15f, 1.0f, 5f))
                        .fov(54f).promptSafe().subject(Subject.BOTH).build(), AdvanceOn.NEVER).build(),
                sequence("TURN_OTS_HIGH").blend(0.4f, shot("TURN_OTS_HIGH", Motion.STATIC)
                        .eye(monk(-2.4f, 2.15f, -2.9f)).target(monk(0.3f, 1.15f, 5f))
                        .fov(54f).promptSafe().subject(Subject.BOTH).build(), AdvanceOn.NEVER).build());
    }

    // ---- monk commands -----------------------------------------------------------------------------
    /**
     * How long an impact shot takes to pull back from the blow. Must stay under the shortest time an
     * attacker lingers at the strike anchor after its hit (the Archon's 0.4 s glide-back delay).
     */
    static final float IMPACT_SETTLE_SECONDS = 0.4f;

    private void authorMonkActions() {
        // The blow and the hit reaction in one frame: a side two-shot riding the actors' live midpoint.
        // Opens tight on the pair toe to toe, has pulled back within IMPACT_SETTLE_SECONDS (before the
        // attacker leaves), and from there carries the monk home as he runs back.
        CameraShot strikeImpact = shot("STRIKE_IMPACT", Motion.DOLLY)
                .eye(midLive(-4.0f, 1.0f, -1.3f), midLive(-5.4f, 1.1f, -1.5f)).target(midLive(0f, 1.3f, 0.1f))
                .fov(50f, 58f).duration(IMPACT_SETTLE_SECONDS).easing(EasingType.EaseInOutQuad)
                .during(Staging.MONK_STRIKES).subject(Subject.BOTH).build();
        CameraShot strikeImpactHigh = shot("STRIKE_IMPACT_HIGH", Motion.DOLLY)
                .eye(midLive(-3.8f, 2.3f, -1.6f), midLive(-5.3f, 2.7f, -1.6f)).target(midLive(0f, 1.1f, 0.2f))
                .fov(50f, 60f).duration(IMPACT_SETTLE_SECONDS).easing(EasingType.EaseInOutQuad)
                .during(Staging.MONK_STRIKES).subject(Subject.BOTH).build();

        // Strike: starts on the attacker, tracks the run-in from the side and drops behind his shoulder
        // as he arrives (he ends chest to chest with the Archon, whose gauntlet arm is on the camera
        // side: from level with the pair it would cover him), cuts on the blow to the two-shot; the
        // director blends back to WIDE.
        put(Situation.STRIKE,
                sequence("STRIKE_SIDE")
                        .cut(shot("STRIKE_TRACK", Motion.TRACK)
                                .eye(monkLive(-4.4f, 1.2f, 0.2f), monkLive(-4.1f, 1.15f, -0.8f))
                                .target(monkLive(0f, 1.3f, 0.4f))
                                .fov(56f, 62f).duration(0.75f).subject(Subject.MONK).build(), AdvanceOn.IMPACT)
                        .cut(strikeImpact, AdvanceOn.ACTION_FINISHED).build(),
                sequence("STRIKE_HIGH")
                        .cut(shot("STRIKE_TRACK_HIGH", Motion.TRACK)
                                .eye(monkLive(-3.6f, 2.3f, -1.4f), monkLive(-3.4f, 2.1f, -0.6f))
                                .target(monkLive(0f, 1.0f, 1.2f))
                                .fov(56f, 62f).duration(0.75f).subject(Subject.MONK).build(), AdvanceOn.IMPACT)
                        .cut(strikeImpactHigh, AdvanceOn.ACTION_FINISHED).build());

        // Flurry: already on the prompt-safe over-the-shoulder before the first ring opens and held
        // through every ring. The monk runs in past the camera; the Archon is large and centred.
        put(Situation.FLURRY,
                sequence("FLURRY_OTS").cut(shot("FLURRY_OTS", Motion.STATIC)
                        .eye(archon(3.4f, 1.8f, 3.5f)).target(archon(0f, 1.5f, 0.5f))
                        .fov(54f).promptSafe().during(Staging.MONK_ENGAGED).subject(Subject.BOTH).build(),
                        AdvanceOn.ACTION_FINISHED).build(),
                sequence("FLURRY_OTS_LOW").cut(shot("FLURRY_OTS_LOW", Motion.STATIC)
                        .eye(archon(3.9f, 0.9f, 2.8f)).target(archon(0f, 1.55f, 0.5f))
                        .fov(56f).promptSafe().during(Staging.MONK_ENGAGED).subject(Subject.BOTH).build(),
                        AdvanceOn.ACTION_FINISHED).build());

        // Stunning Strike: ground-level dash, then half-speed battle clock for 0.3 s as the nerve strike
        // lands, seen from knee height.
        put(Situation.STUNNING_STRIKE,
                sequence("STUN_LOW")
                        .cut(shot("STUN_TRACK_LOW", Motion.TRACK)
                                .eye(monkLive(-3.5f, 0.45f, 0.1f), monkLive(-3.3f, 0.4f, -0.7f))
                                .target(monkLive(0f, 1.4f, 0.4f))
                                .fov(62f, 68f).duration(0.75f).subject(Subject.MONK).build(), AdvanceOn.IMPACT)
                        .cut(shot("STUN_IMPACT", Motion.DOLLY)
                                .eye(midLive(-3.9f, 0.45f, -0.8f), midLive(-5.4f, 0.55f, -0.7f)).target(midLive(0f, 1.45f, 0.1f))
                                .fov(54f, 62f).duration(IMPACT_SETTLE_SECONDS).easing(EasingType.EaseInOutQuad)
                                .slowMotion(0.5f, 0.3f).during(Staging.MONK_STRIKES).subject(Subject.BOTH).build(),
                                AdvanceOn.ACTION_FINISHED)
                        .build(),
                sequence("STUN_LOW_WIDE")
                        .cut(shot("STUN_TRACK_FRONT", Motion.TRACK)
                                .eye(monkLive(-3.2f, 0.5f, -0.8f)).target(monkLive(0f, 1.15f, 1.0f))
                                .fov(66f).duration(0.75f).subject(Subject.MONK).build(), AdvanceOn.IMPACT)
                        .cut(shot("STUN_IMPACT_SIDE", Motion.DOLLY)
                                .eye(midLive(-3.8f, 0.5f, -2.0f), midLive(-5.3f, 0.6f, -2.2f)).target(midLive(0f, 1.3f, 0.2f))
                                .fov(54f, 62f).duration(IMPACT_SETTLE_SECONDS).easing(EasingType.EaseInOutQuad)
                                .slowMotion(0.5f, 0.3f).during(Staging.MONK_STRIKES).subject(Subject.BOTH).build(),
                                AdvanceOn.ACTION_FINISHED)
                        .build());

        // Swift Step: a brisk 70° arc round the monk's −X side (≈54°/s) for as long as the action lasts,
        // FOV widening for the speed rush.
        put(Situation.SWIFT_STEP,
                sequence("SWIFT_ORBIT").cut(shot("SWIFT_ORBIT", Motion.ORBIT)
                        .eye(monk(-1.3f, 1.5f, -3.0f), monk(-3.2f, 1.3f, 0.3f)).target(monk(0f, 1.1f, 0f))
                        .sweep(-70f).fov(54f, 70f).duration(1.3f).easing(EasingType.Linear)
                        .subject(Subject.MONK).build(), AdvanceOn.ACTION_FINISHED).build(),
                sequence("SWIFT_ORBIT_FRONT").cut(shot("SWIFT_ORBIT_FRONT", Motion.ORBIT)
                        .eye(monk(-1.6f, 1.0f, 2.9f), monk(-3.3f, 1.4f, -0.3f)).target(monk(0f, 1.1f, 0f))
                        .sweep(70f).fov(56f, 72f).duration(1.3f).easing(EasingType.Linear)
                        .subject(Subject.MONK).build(), AdvanceOn.ACTION_FINISHED).build());

        // Martial Surge: a snap zoom (the expo ease spends its travel in the first 0.4 s) that then
        // creeps until the action ends, and the turn-ready shot blends back in.
        put(Situation.MARTIAL_SURGE,
                sequence("SURGE_SNAP").cut(shot("SURGE_SNAP", Motion.DOLLY)
                        .eye(monk(-2.7f, 1.5f, 2.5f), monk(-2.1f, 1.5f, 1.9f)).target(monk(0f, 0.95f, 0f))
                        .fov(62f, 48f).duration(1.5f).easing(EasingType.EaseOutExpo)
                        .subject(Subject.MONK).build(), AdvanceOn.ACTION_FINISHED).build(),
                sequence("SURGE_SNAP_LOW").cut(shot("SURGE_SNAP_LOW", Motion.DOLLY)
                        .eye(monk(-2.3f, 0.6f, 2.7f), monk(-1.9f, 0.7f, 2.2f)).target(monk(0f, 1.05f, 0f))
                        .fov(64f, 52f).duration(1.5f).easing(EasingType.EaseOutExpo)
                        .subject(Subject.MONK).build(), AdvanceOn.ACTION_FINISHED).build());

        // Meditate: soft crane rising round the monk toward a high angle, eased so most of the rise is
        // done by the time the heal lands mid-action.
        put(Situation.MEDITATE,
                sequence("MEDITATE_CRANE").blend(0.5f, shot("MEDITATE_CRANE", Motion.CRANE)
                        .eye(monk(-2.6f, 1.1f, 2.0f), monk(-3.3f, 2.9f, 0.2f))
                        .target(monk(0f, 1.0f, 0f), monk(0f, 0.9f, 0f))
                        .fov(58f, 52f).duration(3.4f).easing(EasingType.EaseOutQuad)
                        .subject(Subject.MONK).build(), AdvanceOn.ACTION_FINISHED).build(),
                sequence("MEDITATE_CRANE_BACK").blend(0.5f, shot("MEDITATE_CRANE_BACK", Motion.CRANE)
                        .eye(monk(-2.0f, 1.0f, -2.6f), monk(-3.4f, 2.8f, -0.6f))
                        .target(monk(0f, 1.0f, 0f), monk(0f, 0.9f, 0f))
                        .fov(58f, 52f).duration(3.4f).easing(EasingType.EaseOutQuad)
                        .subject(Subject.MONK).build(), AdvanceOn.ACTION_FINISHED).build());

        // Guard: static medium two-shot from behind the monk, held from the command until the Archon's
        // blow has resolved, so the whole telegraph and the parry prompt play on one still frame. Framed
        // so the Archon stays inside it from its ring all the way to the monk's face.
        put(Situation.GUARD,
                sequence("GUARD_TWO_SHOT").blend(0.35f, shot("GUARD_TWO_SHOT", Motion.STATIC)
                        .eye(monk(-2.7f, 1.9f, -3.1f)).target(monk(0.1f, 1.0f, 3f))
                        .fov(56f).promptSafe().during(Staging.ARCHON_ADVANCES).subject(Subject.BOTH).build(),
                        AdvanceOn.NEVER).build(),
                sequence("GUARD_TWO_SHOT_SIDE").blend(0.35f, shot("GUARD_TWO_SHOT_SIDE", Motion.STATIC)
                        .eye(monk(-3.9f, 2.3f, -2.2f)).target(monk(0f, 0.9f, 2.6f))
                        .fov(58f).promptSafe().during(Staging.ARCHON_ADVANCES).subject(Subject.BOTH).build(),
                        AdvanceOn.NEVER).build());
    }

    // ---- Archon attacks (monk not guarding) --------------------------------------------------------
    private void authorArchonActions() {
        // The blow lands in a two-shot (FF never cuts to the victim alone): rides the live midpoint,
        // tight on the sword's reach at impact, pulled back by the time the Archon starts to glide home.
        CameraShot blowTwoShot = shot("ARCHON_BLOW", Motion.DOLLY)
                .eye(midLive(-4.5f, 1.0f, 0.5f), midLive(-5.6f, 1.15f, 0.5f)).target(midLive(0f, 1.4f, -0.1f))
                .fov(52f, 58f).duration(IMPACT_SETTLE_SECONDS).easing(EasingType.EaseInOutQuad)
                .during(Staging.ARCHON_STRIKES).subject(Subject.BOTH).build();
        CameraShot blowTwoShotHigh = shot("ARCHON_BLOW_HIGH", Motion.DOLLY)
                .eye(midLive(-4.2f, 2.4f, -1.9f), midLive(-5.3f, 2.8f, -1.6f)).target(midLive(0f, 1.35f, 0.2f), midLive(0f, 1.0f, 0.2f))
                .fov(52f, 60f).duration(IMPACT_SETTLE_SECONDS).easing(EasingType.EaseInOutQuad)
                .during(Staging.ARCHON_STRIKES).subject(Subject.BOTH).build();

        // Low angle looking up at the Archon, travelling with its glide; the monk slides into the right
        // of the frame as it arrives.
        put(Situation.ARCHON_MELEE,
                sequence("ARCHON_LOW")
                        .cut(shot("ARCHON_LOW", Motion.TRACK)
                                .eye(archonLive(3.0f, 0.5f, 0.5f), archonLive(2.8f, 0.45f, 0.9f))
                                .target(archonLive(0f, 1.75f, 0.5f))
                                .fov(66f, 62f).duration(0.9f).easing(EasingType.EaseInOutQuad)
                                .subject(Subject.ARCHON).build(), AdvanceOn.IMPACT)
                        .cut(blowTwoShot, AdvanceOn.ACTION_FINISHED).build(),
                sequence("ARCHON_LOW_FAR")
                        .cut(shot("ARCHON_LOW_FAR", Motion.TRACK)
                                .eye(archonLive(4.0f, 0.8f, -0.5f), archonLive(3.8f, 0.7f, 0.1f))
                                .target(archonLive(0f, 1.8f, 0.7f))
                                .fov(60f, 56f).duration(0.9f).easing(EasingType.EaseInOutQuad)
                                .subject(Subject.ARCHON).build(), AdvanceOn.IMPACT)
                        .cut(blowTwoShotHigh, AdvanceOn.ACTION_FINISHED).build());

        // Frost Cast (1.11 s windup): arc round the caster from its flank, sinking to a low angle in
        // front of it as the spell releases, then cut to a high wide from behind the monk as the frost
        // lands on him. The high variant goes the other way and ends over the caster's shoulder.
        put(Situation.FROST_CAST,
                sequence("FROST_ORBIT")
                        .cut(shot("FROST_ORBIT", Motion.ORBIT)
                                .eye(archon(4.0f, 1.7f, -0.2f), archon(1.25f, 0.7f, 3.8f)).target(archon(0f, 1.7f, 0f))
                                .sweep(75f).fov(58f, 62f).duration(1.15f).easing(EasingType.Linear)
                                .subject(Subject.ARCHON).build(), AdvanceOn.IMPACT)
                        .cut(shot("FROST_WIDE", Motion.DOLLY)
                                .eye(mid(-4.4f, 2.9f, -6.6f), mid(-4.7f, 3.1f, -7.0f)).target(mid(0.4f, 0.3f, -0.4f))
                                .fov(52f, 55f).duration(1.3f).easing(EasingType.EaseOutCubic)
                                .subject(Subject.BOTH).build(), AdvanceOn.ACTION_FINISHED).build(),
                sequence("FROST_ORBIT_HIGH")
                        .cut(shot("FROST_ORBIT_HIGH", Motion.ORBIT)
                                .eye(archon(1.6f, 2.4f, 4.2f), archon(4.3f, 3.0f, 0.2f)).target(archon(0f, 1.6f, 0f), archon(0f, 1.4f, 1.2f))
                                .sweep(-75f).fov(58f, 62f).duration(1.15f).easing(EasingType.Linear)
                                .subject(Subject.ARCHON).build(), AdvanceOn.IMPACT)
                        .cut(WIDE, AdvanceOn.ACTION_FINISHED).build());
    }

    // ---- Focus Combo -------------------------------------------------------------------------------
    private void authorFocusCombo() {
        // Windup: glides in from the menu's over-the-shoulder to ride behind the monk's left shoulder
        // as he charges, the Archon growing ahead of him; held until the first correct input.
        put(Situation.COMBO_WINDUP,
                sequence("COMBO_WINDUP").blend(0.3f, shot("COMBO_PUSH_IN", Motion.TRACK)
                        .eye(monkLive(-2.2f, 1.8f, -3.0f), monkLive(-1.9f, 1.7f, -2.2f)).target(monkLive(0.1f, 1.45f, 2.0f))
                        .fov(60f, 52f).duration(1.0f).easing(EasingType.EaseInOutCubic)
                        .during(Staging.MONK_ADVANCES).subject(Subject.BOTH).build(), AdvanceOn.NEVER).build());

        // One cut per correct input, in this order: six tight two-shots round the pair (the monk stands
        // toe to toe with the Archon for the whole string). The set deliberately breaks the line.
        put(Situation.COMBO_ANGLE,
                comboAngle("COMBO_A1_LOW_LEFT", archon(3.5f, 0.5f, 2.5f), archon(3.3f, 0.5f, 2.3f), archon(0f, 1.5f, 0.8f), 58f, 0f),
                comboAngle("COMBO_A2_RIGHT", archon(-3.9f, 1.5f, 1.7f), archon(-3.6f, 1.5f, 1.6f), archon(0f, 1.45f, 0.7f), 56f, 0f),
                comboAngle("COMBO_A3_TOP", archon(2.2f, 4.6f, 2.4f), archon(2.0f, 4.3f, 2.2f), archon(0f, 0.9f, 0.6f), 58f, 0f),
                comboAngle("COMBO_A4_BEHIND", archon(4.0f, 2.9f, -0.3f), archon(3.8f, 2.8f, -0.3f), archon(0f, 1.2f, 0.6f), 58f, 0f),
                comboAngle("COMBO_A5_DUTCH", archon(-3.4f, 1.0f, 2.7f), archon(-3.2f, 1.0f, 2.5f), archon(0f, 1.5f, 0.7f), 56f, 12f),
                comboAngle("COMBO_A6_LOW_FRONT", archon(2.7f, 0.4f, 3.7f), archon(2.5f, 0.4f, 3.5f), archon(0f, 1.7f, 0.5f), 62f, -6f));

        // Flawless: battle clock at 0.4 for the first 0.9 s of a full rising orbit round the pair, FOV
        // wide → narrow. Leaves the −X side toward the monk's back and comes home from behind the
        // Archon, so the monk running back never passes the lens. Timed, not event-ended: the director
        // lets the flourish play out even when the monk's action finishes under it.
        put(Situation.COMBO_FINISHER,
                sequence("COMBO_FINISHER").cut(shot("COMBO_FINISHER_ORBIT", Motion.ORBIT)
                        .eye(archon(4.4f, 1.2f, 1.1f), archon(4.0f, 2.6f, 1.1f)).target(archon(0f, 1.3f, 1.1f))
                        .sweep(360f).fov(78f, 46f).duration(2.6f).easing(EasingType.EaseOutQuad)
                        .slowMotion(0.4f, 0.9f).crossesLine().during(Staging.MONK_ENGAGED)
                        .subject(Subject.BOTH).build(),
                        AdvanceOn.TIME).build());
    }

    private static ShotSequence comboAngle(String name, AnchoredPoint eyeFrom, AnchoredPoint eyeTo,
                                           AnchoredPoint target, float fov, float roll) {
        return sequence(name).cut(shot(name, Motion.DOLLY).eye(eyeFrom, eyeTo).target(target)
                .fov(fov, fov - 4f).roll(roll, roll).duration(0.9f).easing(EasingType.EaseOutQuad)
                .crossesLine().during(Staging.MONK_ENGAGED).subject(Subject.BOTH).build(), AdvanceOn.NEVER).build();
    }

    // ---- results -----------------------------------------------------------------------------------
    /** Seconds from Ended(VICTORY) to the collapsing Archon hitting the floor (its death clip's contact). */
    static final float ARCHON_GROUND_CONTACT_SECONDS = 1.82f;
    /**
     * The result panel rises in the centre of the screen a second into the hero orbit: the camera looks
     * this far to the monk's left all the way round, which holds him in the right third.
     */
    static final float VICTORY_PAN_DEGREES = 19f;

    private void authorResults() {
        put(Situation.VICTORY,
                sequence("VICTORY")
                        // Profile from the −X side with room screen-left for the backward collapse
                        // (3.1 s, the body hits the floor 1.82 s after the battle ends: the shot's
                        // shake beat). Wherever the monk stood for the last blow he is beyond the
                        // Archon, never in front of the lens.
                        .cut(shot("VICTORY_ARCHON_FALLS", Motion.DOLLY)
                                .eye(archon(4.9f, 1.5f, -0.4f), archon(4.5f, 0.9f, -0.9f))
                                .target(archon(0f, 1.35f, -0.7f), archon(0f, 0.8f, -1.1f))
                                .fov(56f, 52f).duration(3.0f).easing(EasingType.EaseInOutQuad)
                                .shakeBeat(ARCHON_GROUND_CONTACT_SECONDS, 0.7f).during(Staging.MONK_ADVANCES).subject(Subject.ARCHON).build(), AdvanceOn.TIME)
                        .cut(shot("VICTORY_ORBIT", Motion.ORBIT)
                                .eye(monkLive(-1.4f, 1.4f, 3.6f)).target(monkLive(0f, 0.95f, 0f)).pan(VICTORY_PAN_DEGREES)
                                .sweep(-360f).fov(56f).duration(14f).easing(EasingType.Linear).end(End.LOOP)
                                .crossesLine().during(Staging.HOME).subject(Subject.MONK).build(), AdvanceOn.NEVER)
                        .build());

        // Slow rising pull-back from the fallen monk.
        put(Situation.DEFEAT,
                sequence("DEFEAT").blend(0.6f, shot("DEFEAT_PULL_UP", Motion.CRANE)
                        .eye(monkLive(-1.8f, 1.3f, 2.2f), monkLive(-4.2f, 7.0f, -3.0f))
                        .target(monkLive(0f, 0.7f, 0f))
                        .fov(56f, 62f).duration(6f).easing(EasingType.EaseInOutQuad)
                        .establishing().during(Staging.HOME).subject(Subject.MONK).build(), AdvanceOn.NEVER).build());
    }
}
