# Ice Archon reaction animation handoff

`SB_Ice_Archon.sbe` now has twelve states: the original five, unchanged, plus seven
60 fps BASE clips. It remains the same 216,492-triangle, 18-part, 18-bone model,
about 2.72 blocks tall, facing -Z. No enemy AI or battle logic is added here.

| State | Seconds | Use |
|---|---:|---|
| combat_idle | 3.20, loop | Alert ready stance; endpoints match the original attacks. |
| hurt | 0.76 | Hit recoil, strongest at 0.13 s; returns to ready. |
| stunned_enter | 0.66 | Stagger and lower the weapon into the stunned stance. |
| stunned | 2.80, loop | Subtle unsteady hold. |
| stunned_exit | 0.90 | Recover awareness and return to ready. |
| death | 3.10 | Backward collapse; ground-contact FX cue at 1.82 s, then settle. |
| defeated | 2.40, loop | Motionless fallen hold matching the end of death. |

`clips.json` gives machine-readable timings and pose transitions. Stun flow is
`stunned_enter → stunned → stunned_exit → combat_idle`; death is `death → defeated`.
The sword remains rigidly attached to the gripping hand and the guard remains attached
to the left forearm. Mesh-derived vertical keys keep the collapse above the floor.
Do not add a separate sinking translation or rotate/drop the weapon independently.

The new ready pose matches the existing attacks at their endpoints. For interruption
in the middle of a swing, cross-fade the current pose into hurt/stun/death over roughly
0.08–0.12 s. Stop the interrupted attack's damage event when stun/death begins. Do not
resume it underneath the reaction. Keep action, animation and cinematic clocks aligned.

Original impact times are unchanged: slash **0.66 s**, overhead **1.03 s**, frost cast
**1.11 s**. Original idle and wandering clips are preserved as well. Use the existing
`weapon_tip`, `casting_focus` and `chest_core` sockets for effects. The player is still
smaller; choose contact spacing from the actual weapon/hand reach rather than scaling
combatants to bridge the arena's home-position gap.

Sources are saved in the Open Mason SB_Archon project as
`Ice_Archon_<state>_20260918.omanim`. `author_reactions.py` reproduces them through
Open Mason's `om.anim` API on `Ice_Archon.omo`; adjust its absolute project path when
relocating the source. The collapse floor corrections are baked for this model.
