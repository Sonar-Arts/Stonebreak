# Goblin combat animation handoff

`SB_Goblin.sbe` carries six BASE clips authored against the current `Goblin.omo`
(21 parts, faces -Z, ~2.25 units tall; the game renders it at `Goblin.MODEL_SCALE` 0.6).
`clips.json` is the timing contract; `MobWeapon` in code must match it, and
`GoblinAssetContractTest` fails if the two drift.

| State | Seconds | Use |
|---|---:|---|
| Idle | 2.0, loop | Standing / holding at range. |
| Walking | 0.8, loop | Wander and chase. |
| Stab | 0.6 | Dagger jab; damage lands at **0.25 s**. |
| Smash | 0.9 | Patty smacker overhead slam; damage lands at **0.55 s**. |
| DrawBow | 1.2 | Bow draw, holds full draw; arrow leaves at the end. |
| ReleaseBow | 0.4 | Follow-through from full draw back to rest. |

Every one-shot starts and ends at rest (DrawBow ends at full draw, which ReleaseBow
starts from), because the mob renderer does not blend between states.

## Rig notes

- Part pivots (`origin`) are offsets from the part's own position — that is how both
  Open Mason and `SbePoseSolver` read them. They were once authored as absolute points,
  which tore limbs off the moment anything rotated.
- Legs chain `thigh -> shin -> foot`, so bending a thigh carries the lower leg.
- Weapons are voxelized item sprites mounted on `weapon_r` / `bow_l`. In a socket's frame
  an item is centred, one unit across, long axis along +Y (`DropRenderer.SOCKET_SPRITE_TRANSFORM`);
  the socket's rotation/scale does the fitting. Clips counter-rotate `hand_r` / `hand_l`
  so the blade keeps pointing forward and the bow stays upright through the swing.
- `Hatzone` and `Earring` use the same names as the player's sockets, so one cosmetic
  asset (`sbe/Clothing/*`) fits both; each host's socket scale carries the fit.

## Sources

Model and clips live in `Dev Working/Goblin/` (`Goblin.omo`, `Animations/*.omanim`,
`Cosmetics/*.omo`). `author_goblin_clips.py` regenerates the clips through Open Mason's
`om.anim` API on `Goblin.omo`; adjust its absolute output path when relocating.
