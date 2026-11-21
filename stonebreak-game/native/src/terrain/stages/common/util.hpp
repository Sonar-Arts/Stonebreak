// Helpers every stage uses: Python's integer division semantics and argument unpacking.
#pragma once

#include "gen/gen.hpp"

namespace sbtg {

// Python `a // b` (floor) and `-(-a // b)` (ceil) for b > 0.
inline int64_t floordiv(int64_t a, int64_t b) { return a / b - ((a % b != 0) && ((a < 0) != (b < 0)) ? 1 : 0); }
inline int64_t ceildiv(int64_t a, int64_t b) { return -floordiv(-a, b); }
// math.floor(a / b) for ints (Python true division then floor: same as floordiv for exact ints).
inline int64_t floor_div_true(int64_t a, int64_t b) { return floordiv(a, b); }

inline void need(const Args& a, size_t ni, size_t nf, size_t nt, const char* method) {
    if (a.i.size() != ni || a.f.size() != nf || a.t.size() != nt)
        throw std::invalid_argument(std::string(method) + ": expects " + std::to_string(ni) + " ints, " +
                                    std::to_string(nf) + " floats, " + std::to_string(nt) + " tensors");
}

// Channel 0 (height) of generator._planner_cells(ci0, cj0, hc, wc) as (hc, wc): bit for bit the same numbers, from the
// height-only planner window tier when the native generator owns the planner methods (agent PX, 2026-11-19: every
// native consumer except the "cells" method reads only the height, so the window cache keeps 1 of 11 channels and holds
// 11x more windows - far-LOD overview tiles re-ran thousands of evicted planner windows), else from _planner_cells.
Tensor planner_height(Gen& g, int64_t ci0, int64_t cj0, int64_t hc, int64_t wc);

} // namespace sbtg
