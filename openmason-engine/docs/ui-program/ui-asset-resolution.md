# UI asset resolution, embedding and portable export (#285)

How the dependency rows of the [wire contract](omui-sbui-wire-contract.md) (§5.6, §7.1) turn into bytes, in the
editor and in the game. Reference implementation: `openmason-engine/src/main/java/com/openmason/engine/ui/assets/`
(no GL, no tool or game dependency). Tests: `openmason-engine/src/test/java/com/openmason/engine/ui/assets/`
(system `ui-program`).

## 1. Identity

A dependency is identified by its logical id (`stonebreak:ui/textures/panel`) and checked by its SHA-256. Paths are
never identities:

- `sourceHint` is a project-relative path the editor last resolved. It passes the archive entry rules, so it can
  never be absolute or escape the project. Only the editor's project source reads it.
- Every other location is the **convention path** `<namespace>/<path><ext>`, the same in the project's `UI/`
  folder, the game's packaged root, resource packs and archive `assets/`. Extensions per kind, tried in this order:

| Kind | Extensions |
| --- | --- |
| `texture` | `.sbt`, `.omt` |
| `sprites` | `.sprites.json` |
| `image` | `.png` |
| `component` | `.omui` |
| `stylesheet` | `.uss.json` |
| `script` | `.lua` |
| `font` | `.ttf`, `.otf` |
| `sound` | `.ogg`, `.wav` |

## 2. Sources

| Source | Class | Origin | Reads hints | Serves |
| --- | --- | --- | --- | --- |
| Open Mason project | `ProjectAssetSource` | `PROJECT` | yes, first | shared rows (editor) |
| Packaged resources | `MountedAssetSource.packaged(prefix, opener)` | `PACKAGED` | no | shared rows without a pack |
| Resource pack | `MountedAssetSource.pack(id, opener)` | `PACK` | no | only rows whose `pack` is this id |

Packaged resources are opened by the module that owns them (`ResourceOpener.streams(Class::getResourceAsStream)`),
because JPMS hides another module's resources from the engine. Packs are directories or ZIPs
(`ResourceOpener.directory`, `ResourceOpener.zip`, read under the archive limits).

Hosts:
- **Open Mason**: `ProjectLayout.uiAssetSource(projectRoot)` (convention folder `UI/`), then any packaged sources.
- **Stonebreak**: `GameUiAssets.sources(packs)`: the classpath root `ui/shared/`, then the declared packs in pack-id
  order. No Open Mason workspace is consulted.

## 3. Precedence and ownership (`AssetResolver`)

1. An **embedded** row is owned by its archive. It is read only from the entry it names and must match its hash
   (`HASH_MISMATCH` error otherwise). A project or runtime resource with the same id never replaces it.
2. A **shared** row is resolved by the host's sources in the order given; the first source holding the id owns it.
   A row with a `pack` resolves only in that pack (`pack '…' is not mounted` if absent); other shared rows resolve
   only in default roots (sources without a pack id).
3. Shared bytes whose hash differs from the recorded one resolve with a `HASH_MISMATCH` **warning**: the document
   is behind the shared asset (editor) or the deployment drifted (game).
4. A missing **optional** row resolves to its `fallback`, following chains up to 16 hops; a loop is
   `DEPENDENCY_CYCLE`. Without a fallback it is left out with a warning. A missing **required** row is a
   `MISSING_ENTRY` error naming the sources consulted.

The editor resolves OMUI rows (`forDocument`); the game resolves the SBUI table (`forExport`), never the embedded
OMUI's own table.

`AssetResolver.candidates(id)` lists every eligible source holding an id; the export planner reports lower sources
with different bytes as `ASSET_SHADOWED`.

## 4. Editing commands (`ui.assets.edit`)

Commands return an `AssetEdit` (document before/after + `ProjectWrite`s + findings) and touch nothing until
`apply(folder)`. `undo(folder)` reverts the writes in reverse order. A write or revert refuses to proceed if the file
no longer holds the bytes the command expects, so undo never clobbers a later edit. Commands that cannot complete
throw `UiFormatException` and change nothing. Every result is validated with the OMUI writer, so a command can
never produce an unsavable document.

| Command | Effect | Collisions |
| --- | --- | --- |
| `EmbedOperations.embed` | snapshots the row and the shared members of its `requires` closure into `assets/<namespace>/<path><ext>`; hints are kept | a taken entry name becomes `name-2.ext`, ...; existing entries, orphans included, are never overwritten (`ENTRY_RENAMED`) |
| `EmbedOperations.refresh` | re-snapshots the row and the embedded members of its closure from their shared originals; unchanged ones are left alone | an entry shared with another row gets a new name |
| `ExtractOperations.extractToProject` | writes the snapshot to the project (hint, else convention path) and makes the row shared; the entry is dropped unless another row uses it | different project bytes: `CollisionPolicy.FAIL`, `KEEP_PROJECT` (link to theirs) or `REPLACE` (overwrite; undo restores). Identical bytes just relink. |
| `RelinkOperations.relink` | points a shared row at another project file and records its hash | embedded rows must be extracted first |
| `RelinkOperations.rename` | renames an id in the table and every reference | the new id must be free |

**References** (`DependencyRefs`): `styleSheets`, `codeBehind`, `instance.component`, a row's `requires`/`fallback`,
and any string value exactly equal to a dependency id in props, inline/sheet styles, sheet variables, instance params
and overrides, parameter defaults, graph literals and clip keys. Lua source and preserved unknown fields are never
rewritten. Scripts that mention a renamed id are reported.

**Editor saves keep unresolved references.** Resolution never edits the table: a row whose asset is missing is saved
unchanged until a relink, rename or extract repairs it.

## 5. Export (`ui.assets.export`)

`ExportPlanner.plan(doc, sources, request)` computes an `ExportPlan` and writes nothing. `UiExportService.export`
plans, refuses a blocked plan with all of its findings, then runs `SbuiExporter` with the plan's options.
`UiExportService.save` writes `<name>.report.json` and then the `.sbui`, both atomically.

| Mode | Shared rows |
| --- | --- |
| `SHARED` | stay shared. `ExportPlan.mustShip()` lists what must be deployed (game root or the assigned pack). |
| `COLLECT_ALL` | every resolvable shared row is collected into the SBUI, so it opens in a clean project (`SbuiImporter.importPortable`). |

Per-row actions: `source-embedded`, `collected`, `ships-shared`, `fallback`, `omitted` (optional, no fallback),
`missing`.

**Blocking errors**:
- an invalid document, which covers unlisted references, `requires` cycles and bad hints
- a required dependency that nothing provides, in either mode
- a component, shared or nested, that needs a shared dependency the document's table does not list. At runtime every
  dependency resolves through that one table.
- recursive composition across the components that travel with the export
- collected entry names that collide (case-insensitive, or file versus directory)
- a font or sound that would be redistributed without `license`: embedded in either mode, or collected in
  `COLLECT_ALL`. A shared font without a licence only warns.
- a sprite reference to a missing name, or to a region outside its texture or with a slice that no longer
  fits, and a sheet that is unreadable or bound to no texture (`SpriteChecks`, [ui-sprites.md](ui-sprites.md) §5)

**Advisory**: hash drift, shadowed sources, rows nothing references (`UNUSED_DEPENDENCY`, info; they still ship), and
a component row whose `requires` misses what the component needs.

**Host requirements are listed apart from artwork**: the plan carries the union of `hostApis`/`providers` over the
document and every component it carries. Shipping a texture does not ship the Java code a document calls.

### Report (`ExportReport`, canonical JSON)

```json
{
  "format": "omui-export-report",
  "version": 1,
  "document": "stonebreak:ui/pause_menu",
  "mode": "shared",
  "blocked": false,
  "dependencies": [
    {"id": "…", "kind": "texture", "action": "ships-shared", "source": "project:textures/ui/panel.sbt",
     "recordedSha256": "…", "sha256": "…", "size": 22, "pack": "…", "license": "…", "fallback": "…", "shadowed": ["…"]}
  ],
  "hostApis": [{"id": "stonebreak:screen.pause", "version": 1}],
  "providers": [],
  "diagnostics": [{"severity": "warning", "code": "HASH_MISMATCH", "entry": "…", "message": "…"}]
}
```

Absent optional fields are omitted. Locations are source-qualified and portable, never machine paths.

### Import into a project (`SbuiProjectImport`)

Each collected dependency becomes a shared project asset:
- If the project holds nothing under the id, the bytes are written to the hint, or else the convention path.
- If the project holds identical bytes, they are reused and nothing is written.
- If the project holds different bytes, the import gets a fresh id (`<id>-imported`, `-imported-2`, ...), is written
  to its convention path, and every reference is remapped. Existing project files are never overwritten.

A document id that already names a project component is reported. The result is an `AssetEdit`, so the import is
undoable.

## 6. Runtime checks (`HostCompatibility`)

`HostCompatibility.check(sbui, profile, sources)` combines `UiHostProfile.check` (ui API, layout semantics, host
contracts, providers) with resolving every dependency. `runnable()` is false when either has an error. The game
refuses the screen and shows the diagnostics instead of claiming the export is portable. An optional contract the
host lacks is a warning. The editor previews with its own profile and fixture data.

## 7. Live invalidation (`ui.assets.live`)

- `LiveAssets.track(doc, project)` records the document's dependency ids and, for each shared row, its hint and
  every convention path, whether or not the asset currently exists.
- `projectFileChanged(path)` and `assetChanged(id)` invalidate the id in every registered `UiAssetCache` and notify
  listeners with the affected documents. This includes documents reached through components (texture → component →
  screen).
- `UiAssetCache<T>`:
  - A current entry is a map hit.
  - A failed load is remembered per revision and never retried per frame. Invalidation clears it, so a file that
    appears, or a relink, recovers.
  - Loads record their starting revision and install only if it is still current, so stale loads never overwrite
    newer ones.
  - Replaced values are queued and freed by `drainReleases()` on the thread that owns the GL/Skia context.
    `peek(id)` keeps the old value drawable until the new one is ready.

## 8. Not in this layer yet

- (Done in #293/#294: the editor UI calls these classes — the UI Assets panel, the export dialog, texture and
  sprite authoring. A shared row whose file changed since it was recorded is badged `changed` and can be
  re-recorded with Accept Current Version, a relink to the same file.)
- Preview rendering of resolved assets (#286). Its texture cache is a `UiAssetCache<MTexture>` registered with
  `LiveAssets`.
- The `UiArchiveTool export` command still performs a plain shared export without resolving a project.
- `SbuiExporter.collectedEntry` uses the hint's last extension (`.json` for `x.uss.json`). The OMUI embed naming uses
  the kind extension. Both are deterministic, and the runtime reads entries by the name the table records.
