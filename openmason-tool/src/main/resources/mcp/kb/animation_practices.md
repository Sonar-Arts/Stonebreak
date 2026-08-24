# Animation — keyframes, easing, and OMANIM layering

## Ground rules
- Animate PARTS via rotation around their pivots; translate only for
  root/bounce motion. If a limb needs translation to look right, its pivot
  is wrong — fix the model, not the clip.
- Pose-to-pose: set the key storytelling poses first (contact, passing,
  up/down), then let interpolation fill. om.anim keys read omitted components
  from the part's CURRENT transform — so pose the model, then key it.
- Frame economy: a readable walk cycle is 4 key poses; an idle is 2–3.
  More keys usually means mushier motion, not better motion.

## Cycles that work at Stonebreak scale
- Quadruped walk (loop ~1.0–1.2s): diagonal pairs (LF+RH forward while
  RF+LH back), legs swing ±20–35°, body bobs 1–2 sixteenths at double
  frequency, head counter-bobs slightly, tail lags 90° behind body.
- Idle (loop 2–4s): breathing scale or 1-sixteenth chest bob, occasional
  head turn / ear flick as a second layer. Subtle beats obvious.
- One-shot actions (peck, mine, door swing): fast out (ease_out on the
  strike), slow return (ease_in_out). Anticipation of 2–4 frames before a
  strike sells force.

## Easing
- ease_in_out is the default for organic motion; linear only for mechanical
  things (pistons, doors mid-swing).
- Contact moments (foot plant, impact) want a sharp change: end the swing
  with ease_out into the contact pose.

## Layers (OMANIM v1.1 mixing)
- BASE layer: the full-body cycle (walk, idle).
- OVERLAY layers with part-name masks add independent motion on top: head
  look, ear flicks, tail. c.layer(type="overlay", mask=["head","ears"]).
  Keep overlays subtle — they add life without re-authoring the base.
- Loop mode: cycles loop; one-shot clips (door open) play once and HOLD the
  final pose — design the final key as a stable pose.

## Stonebreak clip conventions
- States map to clips by lowercase name (idle/wandering/grazing/wing_flap/
  flying/swimming). Author at least idle + wandering for a mob; the shared
  goose clip table is the naming reference.
- om.anim clips are DETACHED: c.save("/abs/path.omanim") writes only on
  script success; load into the editor with anim_load to preview. Verify with
  anim transport + viewport_capture at 2–3 timestamps, not by trusting keys.
