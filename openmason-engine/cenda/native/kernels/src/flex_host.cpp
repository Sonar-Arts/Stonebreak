// Cenda flex — see include/cenda/flex.h.

#include "cenda/flex.h"

#include <yoga/Yoga.h>

#include <cmath>
#include <vector>

namespace {

struct MeasureCtx {
    cf_measure_fn fn;
    int32_t id;
};

YGSize measure_trampoline(YGNodeConstRef node, float width, YGMeasureMode width_mode,
                          float height, YGMeasureMode height_mode) {
    const auto* ctx = static_cast<const MeasureCtx*>(YGNodeGetContext(node));
    float wh[2] = {0.0f, 0.0f};
    ctx->fn(ctx->id, width, static_cast<int32_t>(width_mode), height,
            static_cast<int32_t>(height_mode), wh);
    return YGSize{wh[0], wh[1]};
}

bool set(float v) { return !std::isnan(v); }

int as_int(float v) { return static_cast<int>(v); }

void apply_style(YGNodeRef n, const float* r) {
    YGNodeStyleSetDisplay(n, as_int(r[CF_DISPLAY]) == 1 ? YGDisplayNone : YGDisplayFlex);
    YGNodeStyleSetPositionType(n, as_int(r[CF_POSITION_TYPE]) == 1 ? YGPositionTypeAbsolute
                                                                  : YGPositionTypeRelative);
    if (set(r[CF_DIRECTION])) YGNodeStyleSetFlexDirection(n, static_cast<YGFlexDirection>(as_int(r[CF_DIRECTION])));
    if (set(r[CF_WRAP])) YGNodeStyleSetFlexWrap(n, static_cast<YGWrap>(as_int(r[CF_WRAP])));
    if (set(r[CF_JUSTIFY])) YGNodeStyleSetJustifyContent(n, static_cast<YGJustify>(as_int(r[CF_JUSTIFY])));
    if (set(r[CF_ALIGN_ITEMS])) YGNodeStyleSetAlignItems(n, static_cast<YGAlign>(as_int(r[CF_ALIGN_ITEMS])));
    if (set(r[CF_ALIGN_SELF])) YGNodeStyleSetAlignSelf(n, static_cast<YGAlign>(as_int(r[CF_ALIGN_SELF])));
    if (set(r[CF_ALIGN_CONTENT])) YGNodeStyleSetAlignContent(n, static_cast<YGAlign>(as_int(r[CF_ALIGN_CONTENT])));
    if (set(r[CF_GROW])) YGNodeStyleSetFlexGrow(n, r[CF_GROW]);
    if (set(r[CF_SHRINK])) YGNodeStyleSetFlexShrink(n, r[CF_SHRINK]);
    if (set(r[CF_BASIS])) YGNodeStyleSetFlexBasis(n, r[CF_BASIS]);
    if (set(r[CF_WIDTH])) YGNodeStyleSetWidth(n, r[CF_WIDTH]);
    if (set(r[CF_HEIGHT])) YGNodeStyleSetHeight(n, r[CF_HEIGHT]);
    if (set(r[CF_WIDTH_PCT])) YGNodeStyleSetWidthPercent(n, r[CF_WIDTH_PCT]);
    if (set(r[CF_HEIGHT_PCT])) YGNodeStyleSetHeightPercent(n, r[CF_HEIGHT_PCT]);
    if (set(r[CF_MIN_W])) YGNodeStyleSetMinWidth(n, r[CF_MIN_W]);
    if (set(r[CF_MIN_H])) YGNodeStyleSetMinHeight(n, r[CF_MIN_H]);
    if (set(r[CF_MAX_W])) YGNodeStyleSetMaxWidth(n, r[CF_MAX_W]);
    if (set(r[CF_MAX_H])) YGNodeStyleSetMaxHeight(n, r[CF_MAX_H]);
    const YGEdge edges[4] = {YGEdgeLeft, YGEdgeTop, YGEdgeRight, YGEdgeBottom};
    for (int e = 0; e < 4; ++e) {
        if (set(r[CF_MARGIN + e])) YGNodeStyleSetMargin(n, edges[e], r[CF_MARGIN + e]);
        if (set(r[CF_PADDING + e])) YGNodeStyleSetPadding(n, edges[e], r[CF_PADDING + e]);
        if (set(r[CF_BORDER + e])) YGNodeStyleSetBorder(n, edges[e], r[CF_BORDER + e]);
        if (set(r[CF_POS + e])) YGNodeStyleSetPosition(n, edges[e], r[CF_POS + e]);
        if (set(r[CF_POS_PCT + e])) YGNodeStyleSetPositionPercent(n, edges[e], r[CF_POS_PCT + e]);
    }
    if (set(r[CF_GAP_ROW])) YGNodeStyleSetGap(n, YGGutterRow, r[CF_GAP_ROW]);
    if (set(r[CF_GAP_COLUMN])) YGNodeStyleSetGap(n, YGGutterColumn, r[CF_GAP_COLUMN]);
    if (set(r[CF_ASPECT])) YGNodeStyleSetAspectRatio(n, r[CF_ASPECT]);
}

}  // namespace

extern "C" {

int32_t cf_abi_version(void) { return CF_ABI_VERSION; }

int32_t cf_layout(const float* records, int32_t count, float width, float height,
                  float point_scale, cf_measure_fn measure, float* out) {
    if (records == nullptr || out == nullptr || count <= 0) {
        return 1;
    }
    const auto n = static_cast<size_t>(count);
    YGConfigRef config = YGConfigNew();
    YGConfigSetPointScaleFactor(config, point_scale);
    std::vector<YGNodeRef> nodes(n);
    std::vector<MeasureCtx> contexts(n);
    for (size_t i = 0; i < n; ++i) {
        const float* r = records + i * CF_STRIDE;
        nodes[i] = YGNodeNewWithConfig(config);
        apply_style(nodes[i], r);
        if (set(r[CF_MEASURE_ID]) && measure != nullptr) {
            contexts[i] = MeasureCtx{measure, as_int(r[CF_MEASURE_ID])};
            YGNodeSetContext(nodes[i], &contexts[i]);
            YGNodeSetMeasureFunc(nodes[i], measure_trampoline);
        }
        if (i > 0) {
            const int parent = as_int(r[CF_PARENT]);
            if (parent < 0 || static_cast<size_t>(parent) >= i) {
                YGNodeFreeRecursive(nodes[0]);
                YGConfigFree(config);
                return 2;
            }
            YGNodeRef p = nodes[static_cast<size_t>(parent)];
            YGNodeInsertChild(p, nodes[i], YGNodeGetChildCount(p));
        }
    }
    YGNodeCalculateLayout(nodes[0], width, height, YGDirectionLTR);
    std::vector<float> abs_xy(n * 2, 0.0f);
    for (size_t i = 0; i < n; ++i) {
        float x = YGNodeLayoutGetLeft(nodes[i]);
        float y = YGNodeLayoutGetTop(nodes[i]);
        if (i > 0) {
            const auto parent = static_cast<size_t>(as_int(records[i * CF_STRIDE + CF_PARENT]));
            x += abs_xy[parent * 2];
            y += abs_xy[parent * 2 + 1];
        }
        abs_xy[i * 2] = x;
        abs_xy[i * 2 + 1] = y;
        out[i * 4] = x;
        out[i * 4 + 1] = y;
        out[i * 4 + 2] = YGNodeLayoutGetWidth(nodes[i]);
        out[i * 4 + 3] = YGNodeLayoutGetHeight(nodes[i]);
    }
    YGNodeFreeRecursive(nodes[0]);
    YGConfigFree(config);
    return 0;
}

}  // extern "C"
