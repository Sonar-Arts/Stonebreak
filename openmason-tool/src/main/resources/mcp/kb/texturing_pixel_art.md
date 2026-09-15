# Texturing — pixel art that reads at 16x16

## Palette discipline
- 3–5 colors per material zone (fur, wood, metal), not per texture. A whole
  mob usually needs 8–14 colors total. More colors = mud at game distance.
- Build each zone as a ramp: shadow / base / light. Do NOT make the ramp by
  adding white/black — HUE-SHIFT it: highlights drift toward warm yellow,
  shadows toward cool blue/purple, saturation highest in the midtone.
  Example fur ramp: (92,58,40) → (140,96,64) → (186,148,102).
- Check contrast between adjacent zones, not within them: hide/fur vs hooves
  vs snout must separate at a glance.

## Light and form
- One imaginary light, top-front. Top faces lightest, bottom faces darkest,
  sides in between — even before any painted shading, tint face ramps this
  way and the model instantly reads as solid.
- Shade in big shapes: darken the belly row, lighten the spine row. Avoid
  per-pixel noise ("pillow shading" and speckle both read as fuzz).
- Dithering: use sparingly for large gradient areas (2x1 or checker between
  two ramp steps). Never dither detail areas like faces.

## Detail budget
- At 16x16, one pixel is 1/16 of the face — every pixel is a decision.
  Eyes: 1–2 px plus a 1 px highlight makes a character. Nostrils: 1 px.
- Outline selectively: dark outline where a part meets a differently-lit
  part sells separation; full black outlines around everything looks sticker-ish.
- Texture features must agree with geometry: if the snout is its own part,
  paint the nostrils on the snout part's front face, not the head.

## Faces, UVs and mapping in Open Mason
- Prefer om.tex.create(selection, size=(16,16)) — one shared material +
  texture across the selected faces, painted via t.fill/rect/line/flood/
  set_pixels/noise. tex tools are LIVE-viewport only.
- UV regions per face come from face mappings (u0,v0,u1,v1 + rotation).
  When hand-checking texel alignment use model_face_describe (glyph grid) —
  never guess pixel positions.
- Keep face texture resolution consistent: 16 px per block edge. A 2x1 block
  face gets 32x16, not 16x16 stretched.

## Verify like an artist
After painting: tex/model_face_describe with hex:true to check exact ramp
values; viewport_capture from front/side/three-quarter; check the silhouette
still reads with texture applied (texture can visually destroy a good shape
with misplaced contrast).
