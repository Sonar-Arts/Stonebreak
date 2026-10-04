/* Cenda flex — Yoga (facebook/yoga, MIT) flexbox layout behind a batched C
 * ABI, consumed by Java via FFM.
 *
 * #283 layout spike: one downcall lays out a whole tree described as flat
 * float records, so the FFM crossing cost is per layout, not per style
 * property. Text and other intrinsic sizes come back through one measure
 * upcall per measured leaf.
 *
 * Record format: `count` records of CF_STRIDE floats, parents before
 * children (record 0 is the root). NaN means "unset/auto" for every numeric
 * field. Enum fields use Yoga's own enum values. Output: 4 floats per record
 * (x, y, width, height), relative to the root's origin.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CF_ABI_VERSION 1

enum {
    CF_PARENT = 0,        /* parent record index; ignored for record 0 */
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
    CF_POS = 32,          /* 4 floats: left, top, right, bottom (points) */
    CF_GAP_ROW = 36,
    CF_GAP_COLUMN = 37,
    CF_ASPECT = 38,
    CF_MEASURE_ID = 39,   /* >= 0: leaf measured through the callback with this id */
    CF_POS_PCT = 40,      /* 4 floats: left, top, right, bottom (percent) */
    CF_STRIDE = 44
};

/* Measure upcall: modes are YGMeasureMode (0 undefined, 1 exactly, 2 at-most).
 * Writes the measured width/height to out_wh[0..1]. */
typedef void (*cf_measure_fn)(int32_t id, float width, int32_t width_mode,
                              float height, int32_t height_mode, float* out_wh);

int32_t cf_abi_version(void);

/* Lays out the tree. point_scale is Yoga's pixel-grid factor (0 disables
 * rounding, 1 rounds to whole pixels). Returns 0, or nonzero on bad input. */
int32_t cf_layout(const float* records, int32_t count, float width, float height,
                  float point_scale, cf_measure_fn measure, float* out);

#ifdef __cplusplus
}
#endif
