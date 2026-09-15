# Open Mason tool workflows — how effective agents operate here

## Orient before touching anything
1. model_summary — parts, counts, bounds, selection. ALWAYS first.
2. describe_api {topic:"overview"} once per session if unsure of a domain;
   knowledge packs (this tool) for art judgment.
3. For asset context: asset_list / asset_manifest / asset_mesh_summary read
   the game's SBO/SBE files directly — no need to open them.

## Inspect before you mutate
- Geometry: model_describe (summary → parts → faces) gives winding loops,
  geometric orientation, materials and UVs. part_mesh/inspect_part are the
  raw-array fallbacks.
- Pixels: tex_describe / model_face_describe glyph grids — never claim a
  texture looks like anything without describing it first. hex:true for
  exact values.
- After topology edits (extrude/delete/set_geometry/imports): run
  model_check_winding. Inverted faces are invisible-from-outside bugs that
  no screenshot reveals reliably.

## Batch edits through scripts
- 3+ related operations → run_python_script (or run_model_ops). One call =
  ONE undo entry and full rollback on failure; loops give symmetry for free.
- validate_model_ops dry-runs JSON batches. include_trace:true on python
  returns the equivalent ops for audit.
- Two undo histories: model edits → model history; om.canvas/canvas_* edits
  → texture editor history. A mixed script needs undo in BOTH domains to
  fully revert.
- Reusable work: script_save it. script_list/script_read to build on prior
  scripts; the Scripting window shows the same library to the user.

## Verify visually, then report
- viewport_capture after meaningful geometry/texture changes; canvas_capture
  for the texture editor. Compare against the intent, not memory.
- Report what changed in model terms ("head widened 6→8, repainted snout"),
  not tool-call logs.

## Working with the user
- The viewport is visible to a human while you work — avoid flickering
  states (e.g. don't create parts then instantly delete them to "test").
- asset_open replaces the user's working model and therefore requires their
  approval; if declined, ask what they'd prefer instead of retrying.
- Anything under ~/.openmason (scripts, exports) is agent-writable without
  approval; game resource folders are read-only through the asset lens.
