// Cenda flex — see include/cenda/flex.h.

#include "cenda/flex.h"

#include <yoga/Yoga.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

namespace {

constexpr YGEdge kEdges[4] = {YGEdgeLeft, YGEdgeTop, YGEdgeRight, YGEdgeBottom};

bool set(float v) { return !std::isnan(v); }

int as_int(float v) { return static_cast<int>(v); }

uint32_t mask(const float* r, int field) {
    const float v = r[field];
    return set(v) && v > 0.0f ? static_cast<uint32_t>(v) : 0u;
}

// Every setter is called with an explicit value (the default when the field is
// unset), so one function serves fresh nodes and retained restyles alike.
// Yoga compares before it dirties, so re-setting an equal value is free.
template <typename Points, typename Percent, typename Auto>
void length(float v, uint32_t bit, uint32_t pct, uint32_t autos, Points points, Percent percent, Auto automatic) {
    if (autos & bit) {
        automatic();
    } else if ((pct & bit) && set(v)) {
        percent(v);
    } else {
        points(set(v) ? v : YGUndefined);
    }
}

void apply_style(YGNodeRef n, const float* r) {
    const uint32_t pct = mask(r, CF_PCT_MASK);
    const uint32_t autos = mask(r, CF_AUTO_MASK);
    // Yoga's default basis/width/height is `auto`, not undefined: keep unset equal to fresh.
    uint32_t dims = autos;
    if (!set(r[CF_BASIS])) dims |= CF_LEN_BASIS;
    if (!set(r[CF_WIDTH])) dims |= CF_LEN_WIDTH;
    if (!set(r[CF_HEIGHT])) dims |= CF_LEN_HEIGHT;

    YGNodeStyleSetDisplay(n, as_int(r[CF_DISPLAY]) == 1 ? YGDisplayNone : YGDisplayFlex);
    YGNodeStyleSetPositionType(n, set(r[CF_POSITION_TYPE]) && as_int(r[CF_POSITION_TYPE]) == 1
                                      ? YGPositionTypeAbsolute : YGPositionTypeRelative);
    YGNodeStyleSetFlexDirection(n, set(r[CF_DIRECTION]) ? static_cast<YGFlexDirection>(as_int(r[CF_DIRECTION]))
                                                        : YGFlexDirectionColumn);
    YGNodeStyleSetFlexWrap(n, set(r[CF_WRAP]) ? static_cast<YGWrap>(as_int(r[CF_WRAP])) : YGWrapNoWrap);
    YGNodeStyleSetJustifyContent(n, set(r[CF_JUSTIFY]) ? static_cast<YGJustify>(as_int(r[CF_JUSTIFY]))
                                                       : YGJustifyFlexStart);
    YGNodeStyleSetAlignItems(n, set(r[CF_ALIGN_ITEMS]) ? static_cast<YGAlign>(as_int(r[CF_ALIGN_ITEMS]))
                                                       : YGAlignStretch);
    YGNodeStyleSetAlignSelf(n, set(r[CF_ALIGN_SELF]) ? static_cast<YGAlign>(as_int(r[CF_ALIGN_SELF]))
                                                     : YGAlignAuto);
    YGNodeStyleSetAlignContent(n, set(r[CF_ALIGN_CONTENT]) ? static_cast<YGAlign>(as_int(r[CF_ALIGN_CONTENT]))
                                                           : YGAlignFlexStart);
    YGNodeStyleSetOverflow(n, set(r[CF_OVERFLOW]) ? static_cast<YGOverflow>(as_int(r[CF_OVERFLOW]))
                                                  : YGOverflowVisible);
    YGNodeStyleSetFlexGrow(n, set(r[CF_GROW]) ? r[CF_GROW] : 0.0f);
    YGNodeStyleSetFlexShrink(n, set(r[CF_SHRINK]) ? r[CF_SHRINK] : 0.0f);

    length(r[CF_BASIS], CF_LEN_BASIS, pct, dims,
           [n](float v) { YGNodeStyleSetFlexBasis(n, v); },
           [n](float v) { YGNodeStyleSetFlexBasisPercent(n, v); },
           [n] { YGNodeStyleSetFlexBasisAuto(n); });
    if (set(r[CF_WIDTH_PCT])) {
        YGNodeStyleSetWidthPercent(n, r[CF_WIDTH_PCT]);
    } else {
        length(r[CF_WIDTH], CF_LEN_WIDTH, pct, dims,
               [n](float v) { YGNodeStyleSetWidth(n, v); },
               [n](float v) { YGNodeStyleSetWidthPercent(n, v); },
               [n] { YGNodeStyleSetWidthAuto(n); });
    }
    if (set(r[CF_HEIGHT_PCT])) {
        YGNodeStyleSetHeightPercent(n, r[CF_HEIGHT_PCT]);
    } else {
        length(r[CF_HEIGHT], CF_LEN_HEIGHT, pct, dims,
               [n](float v) { YGNodeStyleSetHeight(n, v); },
               [n](float v) { YGNodeStyleSetHeightPercent(n, v); },
               [n] { YGNodeStyleSetHeightAuto(n); });
    }
    // min/max have no `auto`; an auto bit means unset.
    const auto none = [] {};
    length(r[CF_MIN_W], CF_LEN_MIN_W, pct, 0u,
           [n](float v) { YGNodeStyleSetMinWidth(n, v); },
           [n](float v) { YGNodeStyleSetMinWidthPercent(n, v); }, none);
    length(r[CF_MIN_H], CF_LEN_MIN_H, pct, 0u,
           [n](float v) { YGNodeStyleSetMinHeight(n, v); },
           [n](float v) { YGNodeStyleSetMinHeightPercent(n, v); }, none);
    length(r[CF_MAX_W], CF_LEN_MAX_W, pct, 0u,
           [n](float v) { YGNodeStyleSetMaxWidth(n, v); },
           [n](float v) { YGNodeStyleSetMaxWidthPercent(n, v); }, none);
    length(r[CF_MAX_H], CF_LEN_MAX_H, pct, 0u,
           [n](float v) { YGNodeStyleSetMaxHeight(n, v); },
           [n](float v) { YGNodeStyleSetMaxHeightPercent(n, v); }, none);

    for (int e = 0; e < 4; ++e) {
        const YGEdge edge = kEdges[e];
        length(r[CF_MARGIN + e], CF_LEN_MARGIN << e, pct, autos,
               [n, edge](float v) { YGNodeStyleSetMargin(n, edge, v); },
               [n, edge](float v) { YGNodeStyleSetMarginPercent(n, edge, v); },
               [n, edge] { YGNodeStyleSetMarginAuto(n, edge); });
        length(r[CF_PADDING + e], CF_LEN_PADDING << e, pct, 0u,
               [n, edge](float v) { YGNodeStyleSetPadding(n, edge, v); },
               [n, edge](float v) { YGNodeStyleSetPaddingPercent(n, edge, v); }, none);
        YGNodeStyleSetBorder(n, edge, set(r[CF_BORDER + e]) ? r[CF_BORDER + e] : YGUndefined);
        if (set(r[CF_POS_PCT + e])) {
            YGNodeStyleSetPositionPercent(n, edge, r[CF_POS_PCT + e]);
        } else {
            length(r[CF_POS + e], CF_LEN_POS << e, pct, autos,
                   [n, edge](float v) { YGNodeStyleSetPosition(n, edge, v); },
                   [n, edge](float v) { YGNodeStyleSetPositionPercent(n, edge, v); },
                   [n, edge] { YGNodeStyleSetPositionAuto(n, edge); });
        }
    }
    length(r[CF_GAP_ROW], CF_LEN_GAP_ROW, pct, 0u,
           [n](float v) { YGNodeStyleSetGap(n, YGGutterRow, v); },
           [n](float v) { YGNodeStyleSetGapPercent(n, YGGutterRow, v); }, none);
    length(r[CF_GAP_COLUMN], CF_LEN_GAP_COLUMN, pct, 0u,
           [n](float v) { YGNodeStyleSetGap(n, YGGutterColumn, v); },
           [n](float v) { YGNodeStyleSetGapPercent(n, YGGutterColumn, v); }, none);
    YGNodeStyleSetAspectRatio(n, set(r[CF_ASPECT]) ? r[CF_ASPECT] : YGUndefined);
}

// ── stateless (#283 spike) ──────────────────────────────────────────────────

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

}  // namespace

// ── retained (#287) ─────────────────────────────────────────────────────────

struct cf_tree {
    struct Slot {
        YGNodeRef node = nullptr;
        float style[CF_STRIDE];
        float rect[4];  // parent-relative left, top, width, height after the last layout
        int32_t measure_id = -1;
    };

    YGConfigRef config = nullptr;
    cf_measure_fn measure = nullptr;
    cf_baseline_fn baseline = nullptr;
    std::vector<Slot> slots;
    std::vector<int32_t> free_list;
    int32_t live = 0;

    YGNodeRef get(int32_t handle) const {
        if (handle < 0 || static_cast<size_t>(handle) >= slots.size()) {
            return nullptr;
        }
        return slots[static_cast<size_t>(handle)].node;
    }
};

namespace {

YGSize retained_measure(YGNodeConstRef node, float width, YGMeasureMode width_mode,
                        float height, YGMeasureMode height_mode) {
    // The tree rides on the config context, the measure id on the node context,
    // so slot-vector growth never invalidates either.
    auto* node_ref = const_cast<YGNodeRef>(node);
    const auto* tree = static_cast<const cf_tree*>(YGConfigGetContext(YGNodeGetConfig(node_ref)));
    const auto id = static_cast<int32_t>(reinterpret_cast<intptr_t>(YGNodeGetContext(node)));
    float wh[2] = {0.0f, 0.0f};
    if (tree != nullptr && tree->measure != nullptr) {
        tree->measure(id, width, static_cast<int32_t>(width_mode), height,
                      static_cast<int32_t>(height_mode), wh);
    }
    return YGSize{wh[0], wh[1]};
}

float retained_baseline(YGNodeConstRef node, float width, float height) {
    auto* node_ref = const_cast<YGNodeRef>(node);
    const auto* tree = static_cast<const cf_tree*>(YGConfigGetContext(YGNodeGetConfig(node_ref)));
    const auto id = static_cast<int32_t>(reinterpret_cast<intptr_t>(YGNodeGetContext(node)));
    return tree != nullptr && tree->baseline != nullptr ? tree->baseline(id, width, height) : height;
}

bool same_record(const float* a, const float* b) {
    for (int i = 0; i < CF_STRIDE; ++i) {
        if (i == CF_PARENT) {
            continue;
        }
        const bool an = std::isnan(a[i]);
        const bool bn = std::isnan(b[i]);
        if (an != bn || (!an && a[i] != b[i])) {
            return false;
        }
    }
    return true;
}

bool is_ancestor(YGNodeRef maybe_ancestor, YGNodeRef node) {
    for (YGNodeRef p = node; p != nullptr; p = YGNodeGetParent(p)) {
        if (p == maybe_ancestor) {
            return true;
        }
    }
    return false;
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

cf_tree* cf_tree_new(float point_scale, cf_measure_fn measure, cf_baseline_fn baseline) {
    auto* tree = new (std::nothrow) cf_tree();
    if (tree == nullptr) {
        return nullptr;
    }
    tree->config = YGConfigNew();
    YGConfigSetPointScaleFactor(tree->config, point_scale);
    YGConfigSetContext(tree->config, tree);
    tree->measure = measure;
    tree->baseline = baseline;
    return tree;
}

void cf_tree_free(cf_tree* tree) {
    if (tree == nullptr) {
        return;
    }
    // Detach everything first so YGNodeFree never walks into a freed child.
    for (auto& slot : tree->slots) {
        if (slot.node != nullptr) {
            YGNodeRemoveAllChildren(slot.node);
        }
    }
    for (auto& slot : tree->slots) {
        if (slot.node != nullptr) {
            YGNodeFree(slot.node);
        }
    }
    YGConfigFree(tree->config);
    delete tree;
}

int32_t cf_tree_node_count(const cf_tree* tree) { return tree == nullptr ? 0 : tree->live; }

int32_t cf_node_new(cf_tree* tree) {
    if (tree == nullptr) {
        return -1;
    }
    int32_t handle;
    if (!tree->free_list.empty()) {
        handle = tree->free_list.back();
        tree->free_list.pop_back();
    } else {
        handle = static_cast<int32_t>(tree->slots.size());
        tree->slots.emplace_back();
    }
    auto& slot = tree->slots[static_cast<size_t>(handle)];
    slot.node = YGNodeNewWithConfig(tree->config);
    slot.measure_id = -1;
    for (float& f : slot.rect) {
        f = std::nanf("");
    }
    for (float& f : slot.style) {
        f = std::nanf("");
    }
    slot.style[CF_DISPLAY] = 0.0f;
    slot.style[CF_POSITION_TYPE] = 0.0f;
    apply_style(slot.node, slot.style);
    ++tree->live;
    return handle;
}

int32_t cf_node_free(cf_tree* tree, int32_t node) {
    YGNodeRef n = tree == nullptr ? nullptr : tree->get(node);
    if (n == nullptr) {
        return CF_ERR_NODE;
    }
    if (YGNodeRef parent = YGNodeGetParent(n)) {
        YGNodeRemoveChild(parent, n);
    }
    YGNodeRemoveAllChildren(n);
    YGNodeFree(n);
    tree->slots[static_cast<size_t>(node)].node = nullptr;
    tree->free_list.push_back(node);
    --tree->live;
    return CF_OK;
}

int32_t cf_node_insert(cf_tree* tree, int32_t parent, int32_t child, int32_t index) {
    YGNodeRef p = tree == nullptr ? nullptr : tree->get(parent);
    YGNodeRef c = tree == nullptr ? nullptr : tree->get(child);
    if (p == nullptr || c == nullptr) {
        return CF_ERR_NODE;
    }
    if (YGNodeGetParent(c) != nullptr || is_ancestor(c, p) || YGNodeHasMeasureFunc(p)) {
        return CF_ERR_TREE;
    }
    const auto count = static_cast<int32_t>(YGNodeGetChildCount(p));
    const int32_t at = index < 0 || index > count ? count : index;
    YGNodeInsertChild(p, c, static_cast<size_t>(at));
    return CF_OK;
}

int32_t cf_node_detach(cf_tree* tree, int32_t node) {
    YGNodeRef n = tree == nullptr ? nullptr : tree->get(node);
    if (n == nullptr) {
        return CF_ERR_NODE;
    }
    if (YGNodeRef parent = YGNodeGetParent(n)) {
        YGNodeRemoveChild(parent, n);
    }
    return CF_OK;
}

int32_t cf_nodes_set_style(cf_tree* tree, const int32_t* nodes, const float* records, int32_t count) {
    if (tree == nullptr || nodes == nullptr || records == nullptr || count < 0) {
        return CF_ERR_ARG;
    }
    for (int32_t i = 0; i < count; ++i) {
        YGNodeRef n = tree->get(nodes[i]);
        if (n == nullptr) {
            return CF_ERR_NODE;
        }
        auto& slot = tree->slots[static_cast<size_t>(nodes[i])];
        const float* r = records + static_cast<size_t>(i) * CF_STRIDE;
        if (same_record(slot.style, r)) {
            continue;
        }
        const int32_t measure_id = set(r[CF_MEASURE_ID]) ? as_int(r[CF_MEASURE_ID]) : -1;
        if (measure_id >= 0 && YGNodeGetChildCount(n) > 0) {
            return CF_ERR_TREE; // Yoga only measures leaves
        }
        std::memcpy(slot.style, r, sizeof slot.style);
        apply_style(n, r);
        if (measure_id != slot.measure_id) {
            slot.measure_id = measure_id;
            if (measure_id >= 0) {
                YGNodeSetContext(n, reinterpret_cast<void*>(static_cast<intptr_t>(measure_id)));
                YGNodeSetMeasureFunc(n, retained_measure);
                YGNodeSetBaselineFunc(n, tree->baseline != nullptr ? retained_baseline : nullptr);
            } else {
                YGNodeSetMeasureFunc(n, nullptr);
                YGNodeSetBaselineFunc(n, nullptr);
                YGNodeSetContext(n, nullptr);
            }
        }
    }
    return CF_OK;
}

int32_t cf_node_mark_dirty(cf_tree* tree, int32_t node) {
    YGNodeRef n = tree == nullptr ? nullptr : tree->get(node);
    if (n == nullptr) {
        return CF_ERR_NODE;
    }
    // Yoga only allows (and only needs) dirtying measured leaves.
    if (YGNodeHasMeasureFunc(n)) {
        YGNodeMarkDirty(n);
    }
    return CF_OK;
}

int32_t cf_tree_layout(cf_tree* tree, int32_t root, float width, float height) {
    YGNodeRef r = tree == nullptr ? nullptr : tree->get(root);
    if (r == nullptr) {
        return -CF_ERR_NODE;
    }
    YGNodeCalculateLayout(r, width, height, YGDirectionLTR);
    // Yoga flags every node it visited (the root always), cached or not; only a
    // rect that really moved counts as changed.
    int32_t changed = 0;
    for (auto& slot : tree->slots) {
        if (slot.node == nullptr || !YGNodeGetHasNewLayout(slot.node)) {
            continue;
        }
        YGNodeSetHasNewLayout(slot.node, false);
        const float now[4] = {YGNodeLayoutGetLeft(slot.node), YGNodeLayoutGetTop(slot.node),
                              YGNodeLayoutGetWidth(slot.node), YGNodeLayoutGetHeight(slot.node)};
        if (std::memcmp(now, slot.rect, sizeof now) != 0) {
            std::memcpy(slot.rect, now, sizeof now);
            ++changed;
        }
    }
    return changed;
}

int32_t cf_nodes_read(cf_tree* tree, int32_t root, const int32_t* nodes, int32_t count, float* out) {
    if (tree == nullptr || nodes == nullptr || out == nullptr || count < 0) {
        return CF_ERR_ARG;
    }
    YGNodeRef r = tree->get(root);
    if (r == nullptr) {
        return CF_ERR_NODE;
    }
    for (int32_t i = 0; i < count; ++i) {
        YGNodeRef n = tree->get(nodes[i]);
        if (n == nullptr) {
            return CF_ERR_NODE;
        }
        float x = 0.0f;
        float y = 0.0f;
        YGNodeRef p = n;
        for (; p != nullptr && p != r; p = YGNodeGetParent(p)) {
            x += YGNodeLayoutGetLeft(p);
            y += YGNodeLayoutGetTop(p);
        }
        if (p != r) {
            return CF_ERR_TREE;
        }
        float* o = out + static_cast<size_t>(i) * 4;
        o[0] = x;
        o[1] = y;
        o[2] = YGNodeLayoutGetWidth(n);
        o[3] = YGNodeLayoutGetHeight(n);
    }
    return CF_OK;
}

}  // extern "C"
