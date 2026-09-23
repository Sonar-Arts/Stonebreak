/* river_octant.hpp — the one statement of what a river flow octant means.
 *
 * `ck_carve_water`'s `out_river_flow` plane reports which way a river runs as
 * an octant 0..7 of (dx, dz): 0 = +x, then counter-clockwise through +z in
 * eighths of a turn. The kernel quantises a route's tangent to it, §2d steps
 * along it to hold "a river never runs uphill", and the test steps along it to
 * check that — so the quantiser and the step table live here, once, rather
 * than as copies that could drift apart in the same direction and still agree
 * with each other. (The Java side stores the octant opaquely; `water.frag`
 * turns it back into an angle with the same convention.)
 */
#pragma once

#include <cmath>
#include <cstdint>

namespace cenda::river {

/* One column's step along octant k. */
inline constexpr int OCTANT_DX[8] = {1, 1, 0, -1, -1, -1, 0, 1};
inline constexpr int OCTANT_DZ[8] = {0, 1, 1, 1, 0, -1, -1, -1};

/**
 * The octant of (dx, dz) a reach runs toward.
 *
 * Eight is what the renderer can use and what a water surface can show. The
 * quantisation is done in the kernel rather than on the Java side because the
 * direction is a property of the route, and the route is only in scope there.
 */
inline int8_t riverOctant(float dx, float dz) {
    /* [-pi, pi] -> [0, 8), rounded to the nearest octant and wrapped. */
    const float turns = std::atan2(dz, dx) * (4.0f / 3.14159265358979323846f);
    return static_cast<int8_t>(static_cast<int>(std::lround(turns)) & 7);
}

} // namespace cenda::river
