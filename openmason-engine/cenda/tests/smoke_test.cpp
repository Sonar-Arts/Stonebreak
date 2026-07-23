#include "omformat/byte_reader.hpp"
#include "omformat/version.hpp"

#include <array>
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

std::array<std::byte, 8> demo_bytes() {
    return {std::byte{0x01}, std::byte{0x02}, std::byte{0x03}, std::byte{0x04},
            std::byte{0x4F}, std::byte{0x4D}, std::byte{0x4F}, std::byte{0x00}};
}

} // namespace

int main() {
    using cenda::omformat::bounds_error;
    using cenda::omformat::ByteReader;

    check(!cenda::omformat::library_version().empty(), "library_version non-empty");

    const auto data = demo_bytes();

    {
        ByteReader r{data};
        check(r.u16_be() == 0x0102, "u16_be");
        check(r.u16_le() == 0x0403, "u16_le");
        check(r.utf8(3) == "OMO", "utf8");
        check(r.u8() == 0x00, "u8");
        check(r.remaining() == 0, "fully consumed");
    }

    {
        ByteReader r{data};
        check(r.u32_be() == 0x01020304u, "u32_be");
        check(r.position() == 4, "position after u32");
    }

    {
        // 0x3FC00000 big-endian == 1.5f
        const std::array<std::byte, 4> f{std::byte{0x3F}, std::byte{0xC0}, std::byte{0x00},
                                         std::byte{0x00}};
        ByteReader r{f};
        check(r.f32_be() == 1.5f, "f32_be");
    }

    {
        ByteReader r{data};
        r.skip(6);
        bool threw = false;
        try {
            (void)r.u32_be();
        } catch (const bounds_error&) {
            threw = true;
        }
        check(threw, "bounds_error on over-read");
        check(r.position() == 6, "position unchanged after failed read");
    }

    if (failures == 0) {
        std::puts("omformat_smoke: all checks passed");
        return EXIT_SUCCESS;
    }
    return EXIT_FAILURE;
}
