# Pitfalls — the mistakes this codebase has already paid for

## Winding and face orientation
- Shipped SBO assets contain MIXED triangle winding — a face's cross-product
  normal is not trustworthy. Judge orientation geometrically:
  model_check_winding / asset_check_winding classify via parity ray probes.
- An inverted face renders invisible from outside and correct from inside —
  screenshots from one angle will not catch it. Check winding after imports
  and topology edits.
- Open meshes, panes and sprite crosses have NO defined outward side: the
  checker reports them "indeterminate" — that is informational, not an error.
  Do not "fix" a sprite cross by flipping faces.

## The face-id contract
- One face id = one simple polygon (its triangles must form a triangulated
  simple polygon). Violations surface as "Boundary walk failed" on import
  and as contractViolations in winding reports. Fix by giving each polygon
  its own face id — never by ignoring the warning; downstream UV and
  texture mapping key off face ids.

## Pixel formats
- PixelCanvas.packRGBA stores bytes as ABGR in the int (a<<24|b<<16|g<<8|r).
  ALWAYS use packRGBA/unpackRGBA — hand-packed 0xRRGGBBAA ints will silently
  swap red and blue.
- The describe tools speak 0xRRGGBBAA / flat [r,g,b,a] — conversion is done
  for you; only raw canvas access needs the ABGR caution.

## Editor session lifecycle
- tex_* tools operate on the texture editor's open canvas — call
  tex_open_editor first (face mode: tex_open_editor {face_id}); tex_editor_status
  tells you the current state. model_face_* tools work WITHOUT the editor.
- om.canvas.* script ops likewise require the editor open; om.tex works on
  the live viewport only (headless runs get a teaching error).
- Closing the texture editor flushes + auto-saves; don't leave a face region
  open when you're done (tex_close_editor).

## File vs GPU truth
- asset_* tools read the SBO/SBE FILES; model_* / tex_* tools read the LIVE
  session. After asset_open the two can diverge — the live copy is unsaved
  until the user saves. Never assume an asset file reflects live edits.
- The default texture inside SBO/OMO files is an OMT ARCHIVE (layered), not
  a PNG. Flattening means compositing all visible layers — taking layer 0 is
  a known historical bug.

## Misc traps
- objectIds are namespaced ("stonebreak:cow") while files are SB_Cow.sbe —
  asset tools accept both, but store the objectId when persisting references.
- Legacy texture-only SBOs (some items) embed no model at all: asset_open
  refuses them with asset_has_no_model; describe/export their texture instead.
- Part dimensions off the 1/16 grid cause texel shimmer in-game even when
  the editor looks fine.
