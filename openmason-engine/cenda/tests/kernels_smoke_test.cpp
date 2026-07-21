#include "cenda/kernels.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

} // namespace

int main() {
    check(ck_abi_version() == CK_ABI_VERSION, "abi version matches header");
    std::printf("kernels_smoke: SIMD level = %s\n", ck_simd_level());

    void* node = ck_noise_simplex_fbm(4, 2.0f, 0.5f, 0.01f);
    check(node != nullptr, "simplex fbm node created");
    check(ck_noise_simplex_fbm(0, 2.0f, 0.5f, 0.01f) == nullptr, "octaves<1 rejected");

    constexpr int32_t w = 16, h = 16;
    std::array<float, w * h> a{}, b{}, c{};

    check(ck_gen_grid_2d(node, a.data(), 0.0f, 0.0f, w, h, 1.0f, 1.0f, 1337) == 0, "2d fill ok");
    check(ck_gen_grid_2d(node, b.data(), 0.0f, 0.0f, w, h, 1.0f, 1.0f, 1337) == 0, "2d refill ok");
    check(ck_gen_grid_2d(node, c.data(), 0.0f, 0.0f, w, h, 1.0f, 1.0f, 42) == 0, "2d other-seed ok");

    bool deterministic = true, seed_matters = false, finite = true, varied = false;
    for (int i = 0; i < w * h; ++i) {
        deterministic &= (a[static_cast<std::size_t>(i)] == b[static_cast<std::size_t>(i)]);
        seed_matters |= (a[static_cast<std::size_t>(i)] != c[static_cast<std::size_t>(i)]);
        finite &= std::isfinite(a[static_cast<std::size_t>(i)]);
        varied |= (a[static_cast<std::size_t>(i)] != a[0]);
    }
    check(deterministic, "same seed => identical grid");
    check(seed_matters, "different seed => different grid");
    check(finite, "all samples finite");
    check(varied, "grid is not constant");

    std::array<float, 8 * 8 * 8> vol{};
    check(ck_gen_grid_3d(node, vol.data(), -4.0f, 0.0f, -4.0f, 8, 8, 8, 1.0f, 1.0f, 1.0f, 7) == 0,
          "3d fill ok");
    check(ck_gen_grid_3d(node, nullptr, 0.0f, 0.0f, 0.0f, 8, 8, 8, 1.0f, 1.0f, 1.0f, 7) != 0,
          "null out rejected");
    check(ck_gen_grid_2d(nullptr, a.data(), 0.0f, 0.0f, w, h, 1.0f, 1.0f, 1) != 0,
          "null node rejected");

    check(ck_noise_from_encoded_tree("not-a-real-tree!!") == nullptr, "garbage tree rejected");
    check(ck_noise_from_encoded_tree(nullptr) == nullptr, "null tree rejected");

    ck_noise_destroy(node);
    ck_noise_destroy(nullptr); // must be safe

    if (failures == 0) {
        std::puts("kernels_smoke: all checks passed");
        return EXIT_SUCCESS;
    }
    return EXIT_FAILURE;
}
