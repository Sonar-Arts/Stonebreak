# Player Monk animation handoff

`SB_Player.sbe` contains the original five states plus **29 full-body combat states**.
`clips.json` is the authoritative timing and transition contract. New tracks are sampled at
60 Hz with exact keys on contact beats, smooth cubic pose curves, analytic supporting-leg IK,
and finger articulation. The asset retains its case-sensitive ID `stonebreak:Player`.

## Focus battle integration

- Enter with `combat_enter`, then use `combat_idle` while waiting/selecting. Play `strike`,
  `flurry`, `stunning_strike`, `kick`, `swift_step`, `martial_surge`, `meditate`, or
  `focus_combo` as the **BASE** state. They return to the same combat stance.
- `guard_enter` → `guard` (loop); `parry` or `block` returns to that guard pose.
  Use `guard_exit` to return to combat idle. A successful parry reaction contacts at 0.20 s;
  the gameplay parry window remains aligned with the enemy's authored attack impact.
- Meditation has a complete 3.60 s action, plus `meditate_enter` / `meditate_loop` /
  `meditate_exit` for variable-duration presentation.
- `hurt` returns to combat idle. Stun has enter/loop/exit clips.
- `victory` → `victory_loop`; `defeat` → `defeated`. Clamp the one-shot before
  switching to its terminal loop. The defeat clip includes its floor-level collapse;
  do not apply an additional sinking translation.
- `combat_dash` is an in-place gait. Blend into/out of it over about 0.12 s while the stage
  moves the actor. Other clips have no horizontal root travel. Pause both clip time and
  action time for pause menus; slowing a cinematic must slow both clocks together.
- Use each contact cue in `clips.json` for hit effects and timed-input grading. Flurry has
  contacts at **0.43 / 0.95 / 1.47 s**. Focus Combo contacts are **0.60 / 1.17 / 1.74 /
  2.31 / 2.88 / 3.80 s**, finishing at 4.90 s. Its first four hits are punches, fifth is
  a front kick, sixth an open-palm finisher. Do not restart the entire combo for each input.
- Martial Surge extra hits can replay the appropriate strike/flurry beat with a short
  blend. A reduced number of combat hits should not change the supplied clip's timestamps.
- Preserve `attacking` for the existing first-person punch. It remains the original
  0.78 s OVERLAY, with its original 0.38 s contact and part mask. New combat clips are BASE.

## Stage placement and rig

The player faces **−Z** at yaw zero. The legacy `*_left` parts are at +X, which is the
character's anatomical right when facing −Z; names and all existing UUIDs remain unchanged.
New finger and fingertip parts follow their original hand parents, with matching bones.
Fists curl these joints at scale 1 instead of shrinking the hand. Hair/head sockets and
clothing remain attached to the same original parts.

New contact sockets: `punch_left`, `punch_right`, `kick_left`, `kick_right`. Names follow
legacy rig labels. Position effects using the posed socket. A **two-block** melee anchor
will leave punches visibly short: begin around **0.8–1.0 blocks between actor centres** and
adjust using the Archon's actual surface and these contact sockets. The kick extends
farther; adjust its stage spacing independently. The 18-block arena ring separation is
for home positions, not contact positions. Do not scale up the player to bridge that gap.

The player remains ~1.79 blocks tall, **130,668 triangles**, 85 parts and 33 bones. The
Archon remains larger (~2.72 blocks). The only player mesh change is splitting the original
finger geometry into articulated proximal/distal segments, with closed cut surfaces.

## Source and review

Open Mason source model: `Player/Player-Monk-Combat-20260918.omo`.
Clips: `Player/Animations/Monk-<state>-20260918.omanim` (the corrected combat enter/exit clips use the `-20260918-v2.omanim` suffix).
`author_monk_combat.py` regenerates the clips through Open Mason's `om.anim` API on that
model. Its output paths identify the local Player project; update them if relocating it.

Validation covers finite poses, matching part UUIDs, loop seams, shared transition poses,
contact direction, floor clearance, and preservation of the five original embedded clips.
The render review checks the complete body, curled fingers, guard, palm strike, kick,
meditation, salute and collapse. Full Focus battle timing/camera integration remains with
Fable's implementation; these are authored asset states, not new gameplay code.
