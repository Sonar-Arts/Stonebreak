// Shared stage helpers (stages/common): Python integer semantics, argument checks, soft_cap_peaks.
#pragma once

#include "stages/common/util.hpp"

#include <cmath>

namespace sbtg {

// generator.soft_cap_peaks (PEAK_KNEE_M / PEAK_SPAN_M from the manifest).
Tensor soft_cap_peaks(Gen& g, const Tensor& e);

} // namespace sbtg
