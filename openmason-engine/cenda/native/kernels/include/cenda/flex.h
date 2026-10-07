/* Cenda flex — Yoga (facebook/yoga, MIT) flexbox layout behind a C ABI,
 * consumed by Java via FFM.
 *
 * Two entry styles share one style-record format:
 *
 *  - Stateless (`cf_layout`, the #283 spike): one downcall lays out a whole
 *    tree described as flat records and frees it again.
 *  - Retained (`cf_tree_*` / `cf_node_*`, #287): a native node tree that
 *    lives as long as a UI instance. Styles are pushed in batches; an
 *    unchanged record is skipped outright and a changed one only dirties
 *    Yoga when a property really differs, so Yoga's dirty flags decide how
 *    much of the tree a relayout touches.
 *
 * Record format: CF_STRIDE floats per node. NaN means "unset" for every
 * numeric field, which resolves to the `flex-1` defaults (column, shrink 0,
 * align-items stretch, align-content flex-start, border-box). Enum fields
 * use Yoga's own enum values. Lengths are points unless the field's bit is
 * set in CF_PCT_MASK (percent) or CF_AUTO_MASK (`auto`; basis, width,
 * height, margins and insets only). The older CF_WIDTH_PCT/CF_HEIGHT_PCT/
 * CF_POS_PCT fields still work and win over the masks.
 *
 * Text and other intrinsic sizes come back through one measure upcall per
 * measured leaf. Output rects are 4 floats (x, y, width, height) relative to
 * the layout root's origin.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CF_ABI_VERSION 2

enum {
    CF_PARENT = 0,        /* stateless only: parent record index; ignored for record 0 */
    CF_DISPLAY = 1,       /* 0 flex, 1 none */
    CF_POSITION_TYPE = 2, /* 0 relative, 1 absolute (YGPositionType - 1) */
    CF_DIRECTION = 3,     /* YGFlexDirection: 0 column, 1 column-reverse, 2 row, 3 row-reverse */
    CF_WRAP = 4,          /* YGWrap: 0 nowrap, 1 wrap, 2 wrap-reverse */
    CF_JUSTIFY = 5,       /* YGJustify */
    CF_ALIGN_ITEMS = 6,   /* YGAlign */
    CF_ALIGN_SELF = 7,    /* YGAlign (0 auto) */
    CF_ALIGN_CONTENT = 8, /* YGAlign */
    CF_GROW = 9,
    CF_SHRINK = 10,
    CF_BASIS = 11,
    CF_WIDTH = 12,
    CF_HEIGHT = 13,
    CF_WIDTH_PCT = 14,
    CF_HEIGHT_PCT = 15,
    CF_MIN_W = 16,
    CF_MIN_H = 17,
    CF_MAX_W = 18,
    CF_MAX_H = 19,
    CF_MARGIN = 20,       /* 4 floats: left, top, right, bottom */
    CF_PADDING = 24,      /* 4 floats */
    CF_BORDER = 28,       /* 4 floats */
    CF_POS = 32,          /* 4 floats: left, top, right, bottom */
    CF_GAP_ROW = 36,
    CF_GAP_COLUMN = 37,
    CF_ASPECT = 38,
    CF_MEASURE_ID = 39,   /* >= 0: leaf measured through the callback with this id */
    CF_POS_PCT = 40,      /* 4 floats: left, top, right, bottom (percent) */
    CF_PCT_MASK = 44,     /* CF_LEN_* bits whose value is a percentage */
    CF_AUTO_MASK = 45,    /* CF_LEN_* bits that are `auto` (value ignored) */
    CF_OVERFLOW = 46,     /* YGOverflow: 0 visible, 1 hidden, 2 scroll */
    CF_STRIDE = 48        /* 47 reserved (NaN) */
};

/* Bits of CF_PCT_MASK / CF_AUTO_MASK. A float holds 24 integer bits exactly. */
enum {
    CF_LEN_BASIS = 1 << 0,
    CF_LEN_WIDTH = 1 << 1,
    CF_LEN_HEIGHT = 1 << 2,
    CF_LEN_MIN_W = 1 << 3,
    CF_LEN_MIN_H = 1 << 4,
    CF_LEN_MAX_W = 1 << 5,
    CF_LEN_MAX_H = 1 << 6,
    CF_LEN_MARGIN = 1 << 7,   /* + edge: bits 7..10 */
    CF_LEN_PADDING = 1 << 11, /* + edge: bits 11..14 */
    CF_LEN_POS = 1 << 15,     /* + edge: bits 15..18 */
    CF_LEN_GAP_ROW = 1 << 19,
    CF_LEN_GAP_COLUMN = 1 << 20
};

/* Status codes of the retained API. */
enum {
    CF_OK = 0,
    CF_ERR_ARG = 1,       /* null pointer, bad count or size */
    CF_ERR_NODE = 2,      /* stale or unknown node handle */
    CF_ERR_TREE = 3       /* the edit would break the tree (already parented, cycle, measured parent) */
};

/* Measure upcall: modes are YGMeasureMode (0 undefined, 1 exactly, 2 at-most).
 * Writes the measured width/height to out_wh[0..1]. */
typedef void (*cf_measure_fn)(int32_t id, float width, int32_t width_mode,
                              float height, int32_t height_mode, float* out_wh);

/* Baseline upcall: distance from the top of a measured leaf of the given
 * laid-out size to its first text baseline. Drives `align-items: baseline`. */
typedef float (*cf_baseline_fn)(int32_t id, float width, float height);

int32_t cf_abi_version(void);

/* Stateless: lays out the tree. point_scale is Yoga's pixel-grid factor (0
 * disables rounding, 1 rounds to whole pixels). Returns 0, or nonzero on bad
 * input. */
int32_t cf_layout(const float* records, int32_t count, float width, float height,
                  float point_scale, cf_measure_fn measure, float* out);

/* ── Retained tree ───────────────────────────────────────────────────────── */

typedef struct cf_tree cf_tree;

/* A new, empty tree. `measure` serves every measured leaf of the tree and may
 * be null when no node sets CF_MEASURE_ID; `baseline`, when non-null, gives
 * every measured leaf a baseline (otherwise Yoga uses the leaf's height). */
cf_tree* cf_tree_new(float point_scale, cf_measure_fn measure, cf_baseline_fn baseline);

/* Frees the tree and every node in it. Null is ignored. */
void cf_tree_free(cf_tree* tree);

/* Number of live nodes. */
int32_t cf_tree_node_count(const cf_tree* tree);

/* Creates a detached node with default style. Returns its handle (>= 0), or
 * -1 on failure. Handles are reused after cf_node_free. */
int32_t cf_node_new(cf_tree* tree);

/* Detaches the node from its parent, detaches its children (they stay alive
 * and parentless) and frees it. */
int32_t cf_node_free(cf_tree* tree, int32_t node);

/* Inserts a parentless `child` under `parent` at `index` (clamped to the
 * child count; -1 appends). */
int32_t cf_node_insert(cf_tree* tree, int32_t parent, int32_t child, int32_t index);

/* Detaches `node` from its parent; a parentless node is left alone. */
int32_t cf_node_detach(cf_tree* tree, int32_t node);

/* Pushes styles: `records` holds `count` CF_STRIDE records, applied to
 * `nodes[i]` in order. CF_PARENT is ignored. Returns the first error; earlier
 * records stay applied. */
int32_t cf_nodes_set_style(cf_tree* tree, const int32_t* nodes, const float* records, int32_t count);

/* A measured leaf's content changed (text, image size): re-measure it on the
 * next layout. */
int32_t cf_node_mark_dirty(cf_tree* tree, int32_t node);

/* Lays out the subtree rooted at `root` inside width x height (NaN = unbounded).
 * Clean subtrees are skipped by Yoga. Returns how many nodes' parent-relative
 * rects changed (0 = nothing moved), or -CF_ERR_* on error. A node whose
 * parent moved but whose own parent-relative rect did not is not counted. */
int32_t cf_tree_layout(cf_tree* tree, int32_t root, float width, float height);

/* Reads rects for `nodes` relative to `root`'s origin into out (4 floats per
 * node). A node outside root's subtree is CF_ERR_TREE. */
int32_t cf_nodes_read(cf_tree* tree, int32_t root, const int32_t* nodes, int32_t count, float* out);

#ifdef __cplusplus
}
#endif
