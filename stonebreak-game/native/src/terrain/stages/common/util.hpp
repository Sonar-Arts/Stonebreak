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

} // namespace sbtg
