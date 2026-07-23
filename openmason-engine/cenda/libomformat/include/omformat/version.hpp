#pragma once

#include <string_view>

namespace cenda::omformat {

// Library version, independent of the wire-format versions it implements
// (SBO 1.7, SBE 1.4, OMO 1.7, OMANIM 1.1, ...).
[[nodiscard]] std::string_view library_version() noexcept;

} // namespace cenda::omformat
