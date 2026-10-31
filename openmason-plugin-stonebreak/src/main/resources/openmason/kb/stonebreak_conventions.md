# Stonebreak conventions — scale, facing, clip names and asset data for the Stonebreak game target

## Scale and calibration
- World units are Stonebreak blocks; one block face is textured 16x16 px.
- A cow is the calibration reference: body ≈ 1.2 x 0.9 x 0.6 blocks, legs
  ≈ 0.5 tall. Player eye height ≈ 1.6. Keep new mobs plausible against these.
- The style is Minecraft-family: a good mob is 6–12 cuboid parts, a good
  block 1–4; detail goes in the 16 px texture, not in tiny cubes.

## Model facing and grounding
- Author mobs facing a consistent axis and remember it: the game maps model
  facing per EntityType (cow/sheep were authored −Z, chicken +Z). Pick −Z
  for new quadrupeds unless told otherwise, and never mix within one model.
- The game anchors a model by its rest-pose lowest point (restMinY): feet are
  planted at position.y − legHeight automatically. So DO ground the model at
  y=0 in the editor, but never fake grounding with a translated body.

## Clip conventions
- Mob behaviour states map to clips by lowercase name (idle/wandering/grazing/
  wing_flap/flying/swimming). Author at least idle + wandering for a mob; the
  shared goose clip table is the naming reference.
- One-shot block clips (door open) play once and hold the final pose.

## Files and ids
- objectIds use the `stonebreak:` namespace (`stonebreak:cow`); shipped files
  carry the `SB_` prefix (`SB_Cow.sbe`, `SB_Oak_Planks.sbo`). Store objectIds,
  not file names, when persisting references.
- Shipped assets live under the game's resources: `sbo/blocks`, `sbo/items`,
  `sbo/models`, `sbe/Mobs`, `sbe/Clothing`, `sbe/PlayerCustomize`,
  `sbe/Particles`. An item SBO must never sit in `sbo/blocks` (it becomes a
  phantom block).
- SBO manifests carry Stonebreak game data: gameProperties (hardness, solid,
  material, requiredTier, stack size, category, atlas X/Y, numericId), crafting
  recipes, smelting, fuel, drops and the item `tool` section. Block numericIds
  are unique (AIR 0 and WATER 8 are reserved); new items start at 1000.
