# Modelling fundamentals — parts-based voxel/low-poly models for Stonebreak

## The style
Stonebreak models are Minecraft-family: a small number of cuboid (occasionally
cylinder/wedge) PARTS, each axis-aligned in rest pose, textured with low-res
pixel art. Character comes from proportion, silhouette and texture — not
polycount. A good mob is 6–12 parts; a good block is 1–4. If you are adding
tiny detail cubes, stop and put the detail in the texture instead.

## Silhouette first
Judge every model by its outline at a distance (use viewport_capture and
squint). Rules that reliably read well:
- Exaggerate the identifying feature: a cow's snout, a goose's neck, a
  furnace's mouth. 20–40% bigger than realistic looks right in-game.
- Heads are large relative to bodies (roughly 60–80% of body height for
  friendly mobs; smaller = more serious/menacing).
- Avoid three similar-sized boxes in a row — vary at least one dimension per
  adjacent part so the outline has steps.
- Legs: thinner than you think (1–2 blocks-of-16 across for a cow-sized mob),
  and leave a visible gap between them.

## Proportion and units
- World units are Stonebreak blocks; one block face is textured 16x16 px.
- A cow is the calibration reference: body ≈ 1.2 x 0.9 x 0.6 blocks, legs
  ≈ 0.5 tall. Player eye height ≈ 1.6. Keep new mobs plausible against these.
- Keep part dimensions on the 1/16-block grid where possible (0.0625
  multiples) so textures map 1 texel : 1 sixteenth and never shimmer.

## Part hierarchy and pivots
- Parent parts by articulation, not anatomy: leg → body, head → body,
  jaw → head. Anything that should animate independently is its own part.
- Set each part's ORIGIN (pivot) where the joint really is: top of a leg,
  base of a neck, hinge edge of a lid. A wrong pivot cannot be fixed in
  animation — rotations will slice through the body.
- Rest pose = the neutral standing pose, symmetric wherever the creature is.
  Author symmetric limbs with om.mirror (winding-safe), not manual copies.

## Model facing and grounding (Stonebreak specifics)
- Author mobs facing a consistent axis and remember it: the game maps model
  facing per EntityType (cow/sheep were authored −Z, chicken +Z). Pick −Z
  for new quadrupeds unless told otherwise, and never mix within one model.
- The game anchors a model by its rest-pose lowest point (restMinY): feet are
  planted at position.y − legHeight automatically. So DO ground the model at
  y=0 in the editor, but never fake grounding with a translated body.

## Workflow that works (scripted)
1. model_summary → see what exists. 2. Block out the silhouette with om.box
calls at final sizes — no detail. 3. viewport_capture, judge, adjust sizes.
4. Parent + set pivots. 5. Mirror limbs. 6. Only then texture and detail.
Prefer one run_python_script per stage: each run is one undo entry, so a bad
stage is one undo away.
