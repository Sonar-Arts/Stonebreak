# OMUI / SBUI wire contract (#284)

Normative description of the `.omui` (authoring) and `.sbui` (game export) archives, schema **1.0**. The Java
reference implementation is `openmason-engine/src/main/java/com/openmason/engine/format/{omui,sbui,uiarchive}`.
The golden fixtures that pin this contract are in `openmason-engine/src/test/resources/ui/omui/`
(see [Conformance fixtures](#conformance-fixtures)). A future C++ reader or writer conforms when it reproduces
those fixtures.

"MUST" and "MUST NOT" are requirements. Everything else is explanation.

## 1. Version boundaries

Five contracts are versioned independently. A document records each one it depends on.

| Contract | Where | Form | Owner |
| --- | --- | --- | --- |
| Container and schema | `manifest.json` `schemaVersion` | `"MAJOR.MINOR"` | this document |
| Widget descriptor | node `typeVersion` | integer ≥ 1 | #287 |
| Lua `ui` script API | manifest `uiApi` | integer | #292, [ui-scripting.md](ui-scripting.md) |
| Layout semantics | manifest `layoutSemantics` | id, `flex-1` = Yoga v3.2.1 defaults frozen by #283 | #287 |
| Host contracts and providers | manifest `hostApis[]`, `providers[]` | `{id, version}`, integer versions are cumulative | game/tool |

Graph node kinds carry their own `kindVersion` (#291). Native ABIs (`CL_ABI_VERSION`, `CF_ABI_VERSION`) are
build contracts and never appear in documents.

### Negotiation

- Same major, any minor: readable. A minor newer than the reader's produces a `NEWER_MINOR_VERSION` warning. A
  minor bump MUST only add optional fields; readers preserve them (see §3.4).
- An older major MUST be refused by the reader with `NEEDS_UPGRADE`. Upgrading is a separate, explicit step (§6).
- A newer major MUST be refused with `UNSUPPORTED_SCHEMA_VERSION`.
- Anything an older reader must understand (a new enum value, a field with semantics) is announced in the manifest
  `requires` list. A reader MUST refuse a document that lists a feature it does not support
  (`UNSUPPORTED_REQUIRED_FEATURE`), and a writer MUST refuse a document that uses a feature without listing it
  (`UNDECLARED_FEATURE`).

  | Feature | Adds | Owner |
  | --- | --- | --- |
  | `ui-scroll` | the `ScrollView` widget (version 1, props `vertical` = true, `horizontal` = false) and the `scroll` keyword of `overflow` | #287 |
  | `ui-input` | the `TextField` widget (version 1); the interaction and accessibility props on any widget (`focusable`, `tabIndex`, `autofocus`, `navUp`, `navDown`, `navLeft`, `navRight`, `focusScope`, `draggable`, `tooltip`, `role`, `accessibleName`, `accessibleDescription`, `accessibleValue`, `status`, `actionHints`); the `focus-visible` and `invalid` pseudo-states | #288 |
  | `ui-l10n` | localized-text props `textKey`, `textArgs`, `placeholderKey`, `tooltipKey` | #288 |
  | `ui-data` | the `ListView` widget (version 1, props `items`, `itemKey`, `itemHeight` = 0, `selectionMode` = `single`); its single child is the row template | #289 |
  | `ui-canvas` | the `Canvas` widget (version 1, prop `capacity` = 32768): a surface its Lua code-behind draws each frame | #292 |
- `uiApi`, `layoutSemantics`, `hostApis` and `providers` are checked by the **host** before instantiating
  (`UiHostProfile.check`), not by the reader, so an editor can open and preserve a document its preview cannot run.
  An unmet optional requirement is a warning; an unmet required one is an error and the host refuses the document.

## 2. Container

Both formats are ZIP archives.

### 2.1 Entry names

An entry name MUST be a relative path with `/` separators, at most 255 UTF-8 bytes, and MUST NOT:
- start with `/` or end with `/` (directory entries are ignored but their names are still checked)
- contain an empty, `.` or `..` segment, or a segment that starts with `.` (source directories ignore dot-files,
  so such an entry could not survive pack/unpack)
- contain U+0000–U+001F, U+007F, or any of `\ : * ? " < > |`
- have a segment that ends with `.` or a space, or whose stem is a Windows device name (`con`, `prn`, `aux`,
  `nul`, `com1`–`com9`, `lpt1`–`lpt9`, case-insensitive, any extension)

Two names MUST NOT be equal after lowercasing, and no name may also be the directory of another (`assets/a` next
to `assets/a/b`), both `DUPLICATE_ENTRY`, so an archive extracts the same way on every filesystem.

### 2.2 Writing

- Entries are written in **canonical entry order**: `manifest.json` first, then all other names by Unicode code
  point (equivalently, UTF-8 byte order).
- Every entry is `STORED` (uncompressed), with CRC-32 and sizes in the local header, DOS time
  1980-01-01 00:00:00, no extra fields, no comments, UTF-8 names.
- Without a compressor, the archive bytes are a function of the entries alone, so the golden fixtures compare byte
  for byte on every platform. UI JSON is small, and embedded assets (SBT, OMT, PNG) are already compressed.
- Saves MUST be atomic: write a temporary sibling, flush it to disk, then rename it over the target. The target is
  always either the old file or the complete new one. An existing file keeps its permissions, saving through a
  symlink replaces the link's target, and the directory is flushed after the rename where the platform allows.

### 2.3 Reading limits

Defaults (`ArchiveLimits.DEFAULT`). Sizes are measured on the inflated stream, never trusted from headers.

| Limit | Default |
| --- | --- |
| Entries per archive | 4,096 |
| Any one entry | 64 MiB |
| All entries together | 256 MiB |
| Any JSON entry | 8 MiB |
| Nodes per document tree (slot content included) | 20,000 |
| Tree depth | 48 |
| Graph nodes (functions included) | 10,000 |
| Dependency rows (and SBUI derived rows) | 4,096 |
| JSON nesting depth | 64 |
| JSON string length | 1,048,576 chars |

Readers MUST read the end-of-central-directory record and check that the central directory lists exactly the
local entries, in the same order, with the CRC-32 and uncompressed size of the bytes actually read, so a
central-directory reader and a streaming reader can never see different content. A mismatch or a missing record
is `TRUNCATED_ARCHIVE`. ZIP64 is not supported.
ZIP readers accept DEFLATE entries (hand-made archives), but writers always emit `STORED`.

### 2.4 Content digest

`ArchiveDigest` is the implementation-independent identity of an archive's content. It is the lowercase hex
SHA-256 of the concatenation, over entries in canonical entry order, of:

```
UTF-8(name) 0x00 lowercase-hex(SHA-256(entry bytes)) 0x0A
```

## 3. Canonical JSON

Every JSON entry the format owns is written canonically. Readers accept any valid JSON within the rules below;
`pack` re-canonicalizes hand edits.

### 3.1 Decoding (strict)

A reader MUST reject:
- a UTF-8 BOM or invalid UTF-8 (`INVALID_TEXT`)
- duplicate object keys (`DUPLICATE_KEY`)
- trailing content after the value (`MALFORMED_JSON`)
- unpaired UTF-16 surrogates, including `\ud800`-style escapes (`INVALID_TEXT`): they have no UTF-8 encoding
- nesting deeper than 64 (`LIMIT_EXCEEDED`)
- integer literals outside ±2^53 (`NUMBER_PRECISION`)
- numbers that overflow binary64 (`NUMBER_PRECISION`)

### 3.2 Encoding (byte exact)

- UTF-8, no BOM. Two-space indentation, `"key": value`, LF line ends, one final LF.
- Empty object `{}`, empty array `[]`, otherwise one member or element per line.
- Strings escape `"`, `\` and U+0000–U+001F only: `\b \f \n \r \t`, otherwise `\u00xx` in lowercase hex.
  Everything else is written as raw UTF-8.
- Numbers are binary64, written as ECMAScript `Number.prototype.toString` does: the fewest significant digits
  that round-trip, and among those the decimal closest to the value (`Double.MIN_VALUE` is `5e-324`); no
  exponent in [1e-6, 1e21); exponent form `d[.ddd]e±x` outside that range; integral values have no fraction; `-0`
  is written `0`. NaN and infinities are not representable.
- Record fields are written in **schema order** (the order of the tables below). Free-form objects (`props`,
  `style`, `variables`, `params`, `inputs`, literal values, unknown fields) are written in code-point key order **at
  every depth**.

### 3.3 Defaults and omission

An optional field equal to its default, an absent optional reference, and an empty array or object are
**omitted**. A reader treats an absent field as its default. This makes "absent on read" and "default on write" the
same thing, so re-serialization is a fixed point.

### 3.4 Unknown data

- **Unknown fields**, on any record, are preserved and written back after the known fields in code-point key order
  (`UNKNOWN_FIELD_PRESERVED`, info).
- **Unknown entries** are preserved verbatim (`UNKNOWN_ENTRY`, info).
- **Unknown enum values** are errors (`UNKNOWN_ENUM`); new values need a `requires` feature.
- **Unknown style properties** are preserved with a warning.

Readers never drop content silently.

### 3.5 Numeric precision

All numbers are binary64 on the wire. Integer-typed fields (`uiApi`, `version`, `typeVersion`, `kindVersion`,
`size`) MUST be integral: versions in [1, 1,000,000] (`uiApi` from 0), `size` ≥ 0. Graph coordinates lie in
±1e7. Writers enforce the same bounds as readers, so a document that saves always reopens. Layout lengths are logical pixels; the runtime
narrows them to binary32 for Yoga. Times and durations are seconds (binary64), with a maximum of 3,600.

## 4. Identifiers and references

| Kind | Pattern | Examples |
| --- | --- | --- |
| Logical id (documents, dependencies, host contracts) | `[a-z0-9_.-]{1,64}:[a-z0-9_.-]+(/[a-z0-9_.-]+){0,15}` | `stonebreak:ui/pause_menu` |
| Local id (nodes, graph nodes, functions, variables, converters) | `[A-Za-z_][A-Za-z0-9_-]{0,63}` | `resume`, `on_resume` |
| Part id (in-archive sheets, graphs, clips, scripts) | `[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,7}` | `pause`, `menu/buttons` |
| Widget type | `([a-z0-9_.-]{1,64}:)?[A-Z][A-Za-z0-9]{0,63}` | `Button`, `stonebreak:CrucibleView` |
| Identifier (names, classes, states, slots, params, ports) | `[A-Za-z_-][A-Za-z0-9_-]*` | `menu-button` |

**Reference rule.** A reference string that contains `:` is a **dependency id** and MUST be listed in
`dependencies.json`. Otherwise it is an in-archive part id. This applies to `styleSheets[]`, `codeBehind`,
`instance.component` (always a dependency), and asset-valued style properties (`background-image`, `font`, which
also accept `none` and `var(--token)`).

Stable node ids are the only identity used across files: binding owners, override targets, clip tracks, slot
hosts and editor metadata all use `id`. `name` (the `#name` selector and `ui.q("#name")` handle) can be renamed
freely.

## 5. OMUI layout

| Entry | Required | Contents |
| --- | --- | --- |
| `manifest.json` | yes | §5.1 |
| `document.json` | yes | §5.2 |
| `dependencies.json` | no (absent = no dependencies) | §5.6 |
| `styles/<part id>.uss.json` | no | §5.3 |
| `graphs/<part id>.graph.json` | no | §5.4 |
| `animations/<part id>.anim.json` | no | §5.5 |
| `scripts/<part id>.lua` | no | Lua 5.5 source, UTF-8. Binary chunks (`ESC 'Lua'`) are refused (`BINARY_SCRIPT`). Never executed by readers or importers. |
| `assets/**` | no | embedded dependency snapshots, byte-verbatim |
| `editor/**` | no | editor-only data (workspace, preview fixtures, provenance), byte-verbatim, ignored by runtimes |
| anything else | no | preserved verbatim |

The `id` inside a style, graph or clip file MUST equal the part id in its entry name.

### 5.1 `manifest.json`

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `format` | `"omui"` | required | |
| `schemaVersion` | `"MAJOR.MINOR"` | required | |
| `documentId` | logical id | required | stable across saves and renames |
| `kind` | `screen` \| `component` | required | |
| `displayName` | string | `""` | editor name, not an identity |
| `uiApi` | integer | required | |
| `layoutSemantics` | string | required | `flex-1` |
| `requires` | string[] | `[]` | sorted, unique |
| `hostApis` | `{id, version, optional=false}`[] | `[]` | sorted by id |
| `providers` | same | `[]` | every namespaced widget type MUST be listed here |

### 5.2 `document.json`

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `root` | node | required | |
| `styleSheets` | ref[] | `[]` | precedence order: later wins on equal specificity |
| `codeBehind` | ref | none | Lua module; in-archive script or dependency of kind `script` |
| `component` | component contract | none | required when `kind` is `component`, forbidden otherwise |

**Node** (in this field order):

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `id` | local id | required | unique in the document, slot content included |
| `name` | identifier | none | |
| `type` | widget type | required | built-ins in 1.0: `Box`, `Label`, `Button`, `Image`, `ItemSlot`, `DrawProvider`, `Instance` (all at version 1); `ScrollView` (version 1) with the `ui-scroll` feature; `TextField` (version 1) with `ui-input`; `ListView` (version 1) with `ui-data`; `Canvas` (version 1) with `ui-canvas` |
| `typeVersion` | integer | `1` | MUST NOT exceed the reader's supported version |
| `classes` | identifier[] | `[]` | sorted, unique |
| `props` | object | `{}` | widget properties, validated by widget descriptors (#287). The names listed for `ui-input` and `ui-l10n` (§1) are reserved: using one requires that feature, checked in node props and instance-override props. |
| `style` | object | `{}` | inline declarations (§5.3 value grammar) |
| `dataSource` | data path | none | inherited by the subtree; a leading `.` makes it relative |
| `bindings` | binding[] | `[]` | sorted by `target`, one per target |
| `instance` | instance | none | present exactly when `type` is `Instance` |
| `children` | node[] | `[]` | order is layout and paint order. `Instance` nodes have no children; they use slots. |

**Binding**: `target` (`prop:<identifier>`, `style:<property>` or `class:<identifier>`), `path` (data path),
`mode` (`to-target` default, `two-way`, `to-source`, `once`), `converter` (local id of a pure code-behind function).
On an `Instance` node, `prop:<param>` targets a component parameter. What modes, converters and paths mean at run
time is specified in [ui-data-binding.md](ui-data-binding.md) (#289).

**Data path**: `.?seg(.seg|[index])*`, where `seg` matches `[A-Za-z_][A-Za-z0-9_]*`; `.` alone means the inherited
source itself.

**Instance**: `component` (dependency id of kind `component`; MUST NOT be the document's own id), `params`
(object), `overrides` (sorted by `target`), `slots` (slot name → node[]).

**Override**: `target` (node-id path through nested instances, `inner/leaf`), `props`, `style`, `addClasses`,
`removeClasses`. Removing an override is "reset to source".

**Component contract**: `params` and `events[].args` are `{name, type, default?}` in declaration order. `type`
is one of `bool int number string color asset list object`. `default` MUST fit the type, and `null` always fits.
`slots` are `{name, host}`, where `host` is the id of the node that receives slot content.

### 5.3 `styles/<id>.uss.json`

Fields: `id`, `variables` (`--name` → value), `customStates` (identifiers, not built-ins), and `rules[]`, each
`{selector, style, transitions[]}` in source order. A transition is `{property, duration, easing="linear",
delay=0}`, sorted by property. `property` may also be `all`.

**Selector grammar** (USS subset; matching and specificity belong to #287):

```
list       := selector ("," selector)*
selector   := compound (combinator compound)*
combinator := whitespace (descendant) | ">" (child)
compound   := "*" | Type? ("." ident | "#" ident | ":" state)+ | Type
Type       := [A-Z][A-Za-z0-9]*
state      := hover | active | focus | disabled | checked | a declared custom state
            | focus-visible | invalid          (built in; need the ui-input feature)
```

Limits: 1,024 characters and 32 compounds per selector.

**Values**: a JSON number is a length in logical px (or a plain number for unitless properties). A string is a
keyword, `N%`, `#RRGGBB` or `#RRGGBBAA`, `var(--token)`, or (asset properties) a dependency id or `none`.

| Kind | Properties |
| --- | --- |
| Keyword | `display` (flex, none); `position` (relative, absolute); `flex-direction`; `flex-wrap`; `justify-content`; `align-items`, `align-self`, `align-content`; `visibility`; `overflow` (visible, hidden; scroll with `ui-scroll`); `picking-mode` (position, ignore); `text-align`; `-sb-image-scale` (stretch, nine-slice, tile, integer); `-sb-sampling` (nearest, linear) |
| Length (number, `N%`, `auto`) | `flex-basis`, `width`, `height`, `min-width`, `min-height`, `max-width`, `max-height`, `margin-left`, `margin-top`, `margin-right`, `margin-bottom`, `left`, `top`, `right`, `bottom` |
| Length without `auto` | `padding-*`, `border-*-width`, `row-gap`, `column-gap`, `translate-x`, `translate-y`, `font-size`, `border-radius` |
| Number | `flex-grow`, `flex-shrink`, `aspect-ratio`, `scale`, `rotate` (degrees), `-sb-layer` (overlay layer, #287; readers that predate it preserve it with the unknown-property warning) |
| Number in [0, 1] | `opacity` |
| Color | `color`, `background-color`, `border-color`, `-sb-tint` |
| Asset | `background-image`, `font` |
| Custom | `--name`: any value |

Layout properties are style properties (Unity convention). They mean what `flex-1` (Yoga v3.2.1) means. What every
property does at runtime (cascade, layout, painting) is specified in [ui-runtime.md](ui-runtime.md).

### 5.4 `graphs/<id>.graph.json`

This file is the canonical source that #291 compiles to Lua; this contract stores structure only. Fields:
- `id`
- `variables[]`: `{name, type, default?}`, sorted by name
- `nodes[]`: `{id, kind, kindVersion=1, x=0, y=0, inputs{}, props{}}`, sorted by id. `kind` is
  `ns:dotted.name`. `inputs` holds literal values for unconnected input ports.
- `edges[]`: `{fromNode, fromPort, toNode, toPort}`, sorted by those four fields
- `functions[]`: `{id, inputs[], outputs[], nodes[], edges[]}`, sorted by id. Ports are `{name, type}`, where
  `type` is a value type or `exec`, in declaration order.

Node ids are unique per body (the event graph, or one function), and edges MUST connect existing nodes. Port
typing and synchronous-cycle rules are the compiler's. The node kinds, the checks and the generated Lua are specified
in [ui-graphs.md](ui-graphs.md) (#291). Editor annotations of a graph (comment frames, node groups) live apart from it
in `editor/graphs/<id>.layout.json`, so they never change the graph's source hash.

### 5.5 `animations/<id>.anim.json`

Fields:
- `id`
- `duration` (seconds)
- `loop` (`once` default, `loop`, `ping-pong`)
- `tracks[]`: `{target, property, keys[]}`, sorted by (target, property) and unique on that pair. `target` is a
  node id. `property` uses binding-target syntax (`style:opacity`).
- keys: `{time, value, easing="linear"}`, with strictly increasing times ≤ `duration`. `easing` shapes the segment
  to the next key.
- `events[]`: `{time, name}`, sorted by time then name

Easing names are `linear`, `ease-in`, `ease-out`, `ease-in-out` and `step`. They use the curves of engine
`format.oma.Easing`.

### 5.6 `dependencies.json`

`{"dependencies": [row…]}`, sorted by `id`. Row fields, in order:

| Field | Notes |
| --- | --- |
| `id` | logical id, unique |
| `kind` | `texture`, `sprites`, `image`, `component`, `stylesheet`, `script`, `font`, `sound` |
| `version` | the asset's own format version, optional |
| `sha256` | lowercase hex of the asset bytes |
| `size` | bytes, default 0 (= unknown) |
| `mode` | `shared` or `embedded` |
| `entry` | embedded only: `assets/…` in this archive; its bytes MUST match `sha256` and `size` |
| `sourceHint` | project-relative path the editor last resolved; MUST pass the §2.1 rules (no absolute machine paths). Never used by the game. |
| `requires` | ids this dependency needs (its embedding closure); MUST exist; cycles are `DEPENDENCY_CYCLE` |
| `optional` | default false |
| `fallback` | optional rows only; MUST exist |
| `license` | provenance note; fonts and sounds warn without one, and an export that embeds them fails without one |

An `assets/` entry no row references is preserved with an `ORPHAN_ENTRY` warning.

## 6. Upgrades

Upgrades are explicit, staged and in memory: raw entries → stage → … → decode and full validation → only then
write. In-place upgrades first save the exact bytes that were upgraded to `<file>.v<from>.bak` (or `.bak.1`,
`.bak.2`, ... so an older backup is never overwritten), then replace atomically. A failed stage writes nothing.

**0.1 → 1.0** (`DraftUpgradeStage`). Schema 0.1 is the frozen pre-release draft (the #283 spike shape):
- The manifest uses `id`/`name` → `documentId`/`displayName`, and gains `uiApi: 1` and
  `layoutSemantics: "flex-1"`.
- Nodes carry a flat camelCase `layout` object, which becomes kebab-case `style`:
  - `direction` → `flex-direction`; `justify` / `alignItems` / `alignSelf` / `alignContent` → the flexbox
    properties, with draft words `start`, `end`, `between`, `around`, `evenly` → `flex-start`, `flex-end`,
    `space-*`
  - `grow` / `shrink` / `basis` → `flex-*`; `gap` → `row-gap` + `column-gap`; boolean `wrap` → `flex-wrap`
  - `margin`, `padding` and `border` as `[left, top, right, bottom]` (`null` = unset) → per-side properties
  - `insets{left, top, right, bottom}` → the inset properties
- `script: "scripts/<id>.lua"` → `codeBehind: "<id>"`.

An unknown draft layout key or keyword is an error, because guessing would change geometry silently. Every other
unknown field is carried over.

## 7. SBUI

| Entry | Contents |
| --- | --- |
| `manifest.json` | §7.1 |
| `source/<name>.omui` | the canonical OMUI, **byte-verbatim**: the only editable tree |
| `assets/**` | dependencies collected at export |
| `derived/**` | derived caches (§7.3) |
| anything else | preserved verbatim |

### 7.1 `manifest.json`

Fields, in order:
- `format` (`"sbui"`), `schemaVersion` (`"1.0"`, the same negotiation as OMUI except that an older major is
  refused: re-export it from the source)
- `assetId`: the game-facing logical id; defaults to the document id
- `entry`: the source `documentId`
- `source{entry, digest, schemaVersion}`: `digest` is the §2.4 digest of the embedded OMUI's entries
- `uiApi`, `layoutSemantics`, `requires`, `hostApis`, `providers`: the **union** over the source and every
  component document that travels with the export. Union rules: highest `uiApi`; one shared `layoutSemantics`;
  every feature; per contract, the highest version, optional only if every document marks it optional.
- `dependencies[]` and `derived[]`, below

**Dependency rows** carry the source row's fields plus `location`, `entry` and `pack`. There is exactly one SBUI
row per source row (`INCONSISTENT_MANIFEST` otherwise; a repeated id is `DUPLICATE_ID`). `kind`, `version`,
`requires`, `optional`, `fallback` and `license` MUST equal the source row's; a shared row also keeps its `sha256`
and `size`.

| Source row | SBUI row | Resolution at runtime |
| --- | --- | --- |
| `embedded` | `embedded`, `location: source`, same `entry` and `sha256` | read `entry` inside `source/<name>.omui` |
| `shared`, collected at export | `embedded`, `location: sbui`, `entry: assets/<id with : → />[ext of sourceHint]`, `sha256` of the collected bytes | read `entry` from the SBUI |
| `shared`, not collected | `shared`, no `location` or `entry`, optional `pack` | the host's resource resolver by `id`, in the declared resource root or `pack` (#285); `sha256` is advisory drift detection |

**How a source reference resolves.** Take the id from the document, look it up in the SBUI dependency table, and
follow the row. A runtime never consults `sourceHint`, the source OMUI's own table, or a filesystem path.
Source precedence, resource packs, the convention layout and the export planner are specified in
[ui-asset-resolution.md](ui-asset-resolution.md).

### 7.2 Export and import

- **Default (shared) export** lists the shared resources that must ship with the SBUI.
- **Collect-all export** embeds every required shared dependency. A missing one blocks the export
  (`MISSING_ENTRY`). Fonts and sounds need a licence. A collected asset whose bytes differ from the recorded hash
  produces a warning, and the export records the actual hash.
- **Import** returns the embedded OMUI unchanged plus `editor/provenance.json`
  (`{importedFrom, sbuiSchemaVersion, sourceDigest, collected[]}`). Import never runs scripts.
- **Portable import** additionally turns collected rows into OMUI-embedded snapshots, so the document opens in an
  empty project.

### 7.3 Derived caches

Graph-generated Lua (`graph-lua`) and flattened images (`image`) are derived data, never edited. Row fields:
- `entry`: under `derived/`
- `kind`
- `source`: `graph:<id>` or `dependency:<id>`
- `sourceSha256`: the SHA-256 of the source graph's canonical entry bytes, or the dependency row's `sha256`
- `sha256`: the cache's own bytes
- `compiler`, `compilerVersion`

A cache is **stale** when `sourceSha256` differs from the current source, or when the host's compiler version for
that kind differs.
- The runtime policy (`REJECT`) fails the read with `STALE_DERIVED`.
- The editor policy (`REPORT`) warns and returns the list so the caches can be rebuilt.
- Writers MUST NOT emit stale caches.

There is never a second editable tree.

## 8. Diagnostics

Every failure is a `UiFormatException` that carries all `UiDiagnostic{severity, code, entry, pointer, message}`
records, where `pointer` is an RFC 6901 JSON pointer into `entry`. Codes are stable and append-only (see
`UiDiagnostic.Code`). Readers never return a partial document, and no other exception type escapes them on
malformed input.

## 9. Pack, unpack and the command line

`UiPacker.unpack` writes an archive's canonical entries as files, for Git review. `UiPacker.pack` reads a
directory, validates it like an archive and emits canonical archive bytes, so hand edits are normalized.

`pack` skips names that start with `.` and refuses symlinks. `unpack` refuses a non-empty target unless asked to
replace it, and the files appear all at once (staged in a sibling directory, then renamed; if the final rename
fails, the original directory is moved back).

Command line: `com.openmason.engine.format.uiarchive.UiArchiveTool`, with the commands `validate`, `pack`,
`unpack`, `upgrade`, `export` and `import`. Exit codes: 0 ok, 1 invalid input (diagnostics on stderr), 2 usage.

## Conformance fixtures

`openmason-engine/src/test/resources/ui/omui/`, pinned by `GoldenFixtureTest`. Regenerate with
`-Dui.fixtures.write=true` after an intentional change, then review the `pause_menu/` diff.

| File | What it pins |
| --- | --- |
| `stone_button.omui` | component: contract (params, event, slot), bindings, sheet with pseudo-states, variable and transition |
| `pause_menu.omui` | screen: instances with params, overrides, slot content and a converter binding; two style sheets (shared and local); graph with a function; Lua code-behind plus a shared Lua module; timeline clip; shared and embedded dependencies; editor metadata |
| `pause_menu/` | `pause_menu.omui` unpacked; `pack` MUST reproduce the archive byte for byte |
| `pause_menu.sbui` | export with a source-embedded component, shared rows and a `graph-lua` derived cache: real output of the #291 compiler (`omui-graphc` version `1`) |
| `pause_draft_v0_1.omui` / `.upgraded.omui` | the frozen draft and its exact 1.0 upgrade |

The malformed-input cases (truncation, traversal, duplicates, limits, cycles, hash and cache mismatches) are
generated by `OmuiMalformedTest` and `SbuiExportTest` rather than committed as files.
