// Plain-assert checks for the retained Yoga tree (#287): stateless and
// retained layouts agree, unchanged styles skip relayout, restyles reset to
// defaults, tree edits are validated, and measured leaves re-measure only
// when dirtied. The Java side re-tests the same contract through FFM.

#include "cenda/flex.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

int failures = 0;
int measure_calls = 0;
float measured_width = 40.0f;

void check(bool ok, const char* what) {
    if (!ok) {
        ++failures;
        std::fprintf(stderr, "FAIL: %s\n", what);
    }
}

bool near(float a, float b) { return std::fabs(a - b) < 0.001f; }

void measure(int32_t, float, int32_t, float, int32_t, float* out_wh) {
    ++measure_calls;
    out_wh[0] = measured_width;
    out_wh[1] = 10.0f;
}

std::vector<float> record() {
    std::vector<float> r(CF_STRIDE, std::nanf(""));
    r[CF_DISPLAY] = 0.0f;
    r[CF_POSITION_TYPE] = 0.0f;
    return r;
}

void test_abi() { check(cf_abi_version() == CF_ABI_VERSION && CF_ABI_VERSION == 2, "abi version"); }

// A 200x100 row, padding 10, with a fixed 50-wide child, a grow child and an
// absolute child anchored bottom-right.
void build(std::vector<float>& root, std::vector<float>& fixed, std::vector<float>& grow,
           std::vector<float>& abs) {
    root = record();
    root[CF_WIDTH] = 200.0f;
    root[CF_HEIGHT] = 100.0f;
    root[CF_DIRECTION] = 2.0f;
    for (size_t e = 0; e < 4; ++e) root[CF_PADDING + e] = 10.0f;
    fixed = record();
    fixed[CF_WIDTH] = 50.0f;
    grow = record();
    grow[CF_GROW] = 1.0f;
    abs = record();
    abs[CF_POSITION_TYPE] = 1.0f;
    abs[CF_WIDTH] = 20.0f;
    abs[CF_HEIGHT] = 20.0f;
    abs[CF_POS + 2] = 5.0f;
    abs[CF_POS + 3] = 5.0f;
}

void test_retained_matches_stateless() {
    std::vector<float> root, fixed, grow, abs;
    build(root, fixed, grow, abs);
    std::vector<float> flat;
    for (auto* r : {&root, &fixed, &grow, &abs}) flat.insert(flat.end(), r->begin(), r->end());
    for (int i = 1; i < 4; ++i) flat[static_cast<size_t>(i) * CF_STRIDE + CF_PARENT] = 0.0f;
    std::vector<float> expect(16);
    check(cf_layout(flat.data(), 4, NAN, NAN, 1.0f, nullptr, expect.data()) == 0, "stateless layout");

    cf_tree* t = cf_tree_new(1.0f, nullptr, nullptr);
    int32_t ids[4];
    for (int32_t& id : ids) id = cf_node_new(t);
    for (int i = 1; i < 4; ++i) check(cf_node_insert(t, ids[0], ids[i], -1) == CF_OK, "insert");
    check(cf_nodes_set_style(t, ids, flat.data(), 4) == CF_OK, "set styles");
    check(cf_tree_layout(t, ids[0], NAN, NAN) == 4, "first layout touches every node");
    std::vector<float> got(16);
    check(cf_nodes_read(t, ids[0], ids, 4, got.data()) == CF_OK, "read");
    bool same = true;
    for (int i = 0; i < 16; ++i) same = same && near(got[static_cast<size_t>(i)], expect[static_cast<size_t>(i)]);
    check(same, "retained rects equal stateless rects");
    check(near(got[4], 10) && near(got[6], 50) && near(got[8], 60) && near(got[10], 130), "row geometry");
    check(near(got[12], 175) && near(got[13], 75), "absolute anchored bottom-right");

    // Re-pushing identical styles and relaying out does no work.
    check(cf_nodes_set_style(t, ids, flat.data(), 4) == CF_OK, "same styles");
    check(cf_tree_layout(t, ids[0], NAN, NAN) == 0, "unchanged tree is not relaid out");

    // display:none on the fixed child reflows the grow child.
    std::vector<float> hidden = fixed;
    hidden[CF_DISPLAY] = 1.0f;
    check(cf_nodes_set_style(t, &ids[1], hidden.data(), 1) == CF_OK, "hide");
    check(cf_tree_layout(t, ids[0], NAN, NAN) > 0, "hide relayouts");
    cf_nodes_read(t, ids[0], &ids[2], 1, got.data());
    check(near(got[0], 10) && near(got[2], 180), "collapse reflows sibling");

    // Back to unset: the field resets to the default instead of keeping the old value.
    std::vector<float> plain = record();
    check(cf_nodes_set_style(t, &ids[0], plain.data(), 1) == CF_OK, "reset root");
    cf_tree_layout(t, ids[0], 300.0f, NAN);
    cf_nodes_read(t, ids[0], &ids[2], 1, got.data());
    check(near(got[0], 0) && near(got[2], 300), "reset restores column/stretch defaults");
    cf_tree_free(t);
}

void test_masks() {
    cf_tree* t = cf_tree_new(1.0f, nullptr, nullptr);
    const int32_t root = cf_node_new(t);
    const int32_t child = cf_node_new(t);
    cf_node_insert(t, root, child, 0);
    std::vector<float> r = record();
    r[CF_WIDTH] = 400.0f;
    r[CF_HEIGHT] = 200.0f;
    std::vector<float> c = record();
    c[CF_WIDTH] = 25.0f;
    c[CF_HEIGHT] = 50.0f;
    c[CF_MARGIN + 0] = 0.0f;
    c[CF_PCT_MASK] = static_cast<float>(CF_LEN_WIDTH | CF_LEN_HEIGHT);
    c[CF_AUTO_MASK] = static_cast<float>((CF_LEN_MARGIN << 0) | (CF_LEN_MARGIN << 2));
    const int32_t both[2] = {root, child};
    std::vector<float> flat(r);
    flat.insert(flat.end(), c.begin(), c.end());
    cf_nodes_set_style(t, both, flat.data(), 2);
    cf_tree_layout(t, root, NAN, NAN);
    float out[4];
    cf_nodes_read(t, root, &child, 1, out);
    check(near(out[2], 100) && near(out[3], 100), "percent width/height");
    check(near(out[0], 150), "auto margins centre horizontally");
    cf_tree_free(t);
}

void test_tree_edits() {
    cf_tree* t = cf_tree_new(1.0f, nullptr, nullptr);
    const int32_t a = cf_node_new(t);
    const int32_t b = cf_node_new(t);
    const int32_t c = cf_node_new(t);
    check(cf_node_insert(t, a, b, -1) == CF_OK, "a<-b");
    check(cf_node_insert(t, b, c, -1) == CF_OK, "b<-c");
    check(cf_node_insert(t, c, a, -1) == CF_ERR_TREE, "cycle refused");
    check(cf_node_insert(t, a, c, -1) == CF_ERR_TREE, "double parent refused");
    check(cf_node_insert(t, a, 99, -1) == CF_ERR_NODE, "unknown handle refused");
    float out[4];
    check(cf_nodes_read(t, b, &a, 1, out) == CF_ERR_TREE, "read outside subtree refused");
    check(cf_node_free(t, b) == CF_OK, "free middle node");
    check(cf_tree_node_count(t) == 2, "live count");
    check(cf_node_insert(t, a, c, -1) == CF_OK, "orphaned child can be reparented");
    const int32_t d = cf_node_new(t);
    check(d == b, "handle reused");
    check(cf_node_free(t, b) == CF_OK && cf_node_free(t, b) == CF_ERR_NODE, "double free refused");
    cf_tree_free(t);
}

void test_measure() {
    cf_tree* t = cf_tree_new(1.0f, measure, nullptr);
    const int32_t root = cf_node_new(t);
    const int32_t label = cf_node_new(t);
    cf_node_insert(t, root, label, 0);
    std::vector<float> r = record();
    r[CF_WIDTH] = 300.0f;
    r[CF_ALIGN_ITEMS] = 1.0f; // flex-start: the label keeps its measured width
    std::vector<float> l = record();
    l[CF_MEASURE_ID] = 7.0f;
    const int32_t both[2] = {root, label};
    std::vector<float> flat(r);
    flat.insert(flat.end(), l.begin(), l.end());
    cf_nodes_set_style(t, both, flat.data(), 2);
    measure_calls = 0;
    cf_tree_layout(t, root, NAN, NAN);
    float out[4];
    cf_nodes_read(t, root, &label, 1, out);
    check(measure_calls > 0 && near(out[2], 40) && near(out[3], 10), "measured leaf");
    measure_calls = 0;
    cf_tree_layout(t, root, NAN, NAN);
    check(measure_calls == 0, "clean leaf is not re-measured");
    measured_width = 90.0f;
    cf_node_mark_dirty(t, label);
    cf_tree_layout(t, root, NAN, NAN);
    cf_nodes_read(t, root, &label, 1, out);
    check(measure_calls > 0 && near(out[2], 90), "dirtied leaf re-measures");
    const int32_t extra = cf_node_new(t);
    check(cf_node_insert(t, label, extra, -1) == CF_ERR_TREE, "measured leaf cannot take children");
    cf_tree_free(t);
}

float baseline(int32_t id, float, float) { return id == 1 ? 12.0f : 4.0f; }

void test_baseline() {
    cf_tree* t = cf_tree_new(1.0f, measure, baseline);
    const int32_t row = cf_node_new(t);
    const int32_t a = cf_node_new(t);
    const int32_t b = cf_node_new(t);
    cf_node_insert(t, row, a, -1);
    cf_node_insert(t, row, b, -1);
    std::vector<float> r = record();
    r[CF_DIRECTION] = 2.0f;
    r[CF_ALIGN_ITEMS] = 5.0f; // baseline
    std::vector<float> la = record();
    la[CF_MEASURE_ID] = 1.0f;
    std::vector<float> lb = record();
    lb[CF_MEASURE_ID] = 2.0f;
    std::vector<float> flat(r);
    flat.insert(flat.end(), la.begin(), la.end());
    flat.insert(flat.end(), lb.begin(), lb.end());
    const int32_t ids[3] = {row, a, b};
    cf_nodes_set_style(t, ids, flat.data(), 3);
    cf_tree_layout(t, row, NAN, NAN);
    float out[8];
    cf_nodes_read(t, row, &ids[1], 2, out);
    // Baselines 12 and 4 line up: b sits 8 lower than a.
    check(near(out[5] - out[1], 8.0f), "baseline alignment uses the baseline upcall");
    cf_tree_free(t);
}

}  // namespace

int main() {
    test_abi();
    test_retained_matches_stateless();
    test_masks();
    test_tree_edits();
    test_measure();
    test_baseline();
    if (failures == 0) {
        std::puts("flex_host: all checks passed");
    }
    return failures == 0 ? 0 : 1;
}
