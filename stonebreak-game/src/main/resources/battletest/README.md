# Frostbound Crucible combat-test staging area

Enter from a loaded **singleplayer** world with `/battletest`. `/battletest reset`
returns to the player marker; `/battletest leave` restores the previous world,
position, view, health, inventory and saved progression. Leaving the game also
restores that snapshot before persistence. The suspended server does not tick or
receive arena actions. No menu entry, ordinary terrain placement or world-list
entry is registered.

The Ice Archon is **not spawned yet**. This prepares the environment and its spawn
anchors for the forthcoming combat implementation; it does not add enemy AI.

| Anchor | Feet position (blocks) | Facing | Floor symbol |
| --- | --- | --- | --- |
| `player` | `(0, 0.06, 9)` | North, camera yaw −90° | Cyan ring and arrow |
| `ice_archon` | `(0, 0.06, -9)` | South, model yaw 180° (authored −Z front) | Violet ring and crown |

`arena.json` owns the spawn anchors and static collision boxes. Runtime consumers
can use `BattleTestSession.current().world().arena().archonSpawn()`. The arenas
use +Y up, 1 unit = 1 block. Actors are 18 blocks apart with a clear approach.
Player collision, camera and melee sight rays share the static boxes. These are
conservative architectural proxies, not per-triangle collision; decorative
crystals, the arch overhead and distant mountains are scenery. Future enemy
physics/navigation will need to consume the scene collision layout too.

`Frostbound_Crucible.omo` is the baked overview from the Open Mason project
`SB_Ice_Battle/Models/Frostbound_Crucible_Overview.omo`: all 52 placements, including
the restored outer shelf, and 49,572 authored triangles. Its original editing
scene is `SB_Ice_Battle/Scenes/Frostbound_Crucible.omsc`. The runtime uses this
baked OMO so deployment has no dependency on editor project paths. Re-export
with identity part transforms and solid palette materials when updating it.

The runtime adds two rings of glacial mountains, a snowy valley floor, falling
snow, a moon/stars/animated aurora sky, distance fog and both spawn markers.
Assets and GPU buffers load only on entry. Scene buffers are disposed on return
or renderer shutdown; neither editor code nor Open Mason must run with the game.

Verification: `Testing/run-tests.sh player networking persistence` includes the
headless arena, collision and return/persistence checks. Opt-in real OpenGL check:

```
mvn -q -pl stonebreak-game -am test -Dtest=BattleTestRenderTest -Dstonebreak.battletest.gl=true -Dsurefire.failIfNoSpecifiedTests=false
```

It uses a hidden GLFW window and writes player/overview views to
`/tmp/battletest-player.png` and `/tmp/battletest-overview.png`.
