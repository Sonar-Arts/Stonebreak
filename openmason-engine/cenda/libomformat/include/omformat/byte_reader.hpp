#pragma once

#include <bit>
#include <cstddef>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>

namespace cenda::omformat {

class bounds_error final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// Bounds-checked forward cursor over a read-only byte buffer. Every read
// throws bounds_error instead of walking off the end, so parsers built on it
// are safe against truncated/corrupt archives by construction. Both
// endiannesses are offered; each format parser commits to the variant that
// matches its Java serializer's wire layout.
class ByteReader {
public:
    explicit ByteReader(std::span<const std::byte> data) : data_(data) {}

    [[nodiscard]] std::size_t position() const noexcept { return pos_; }
    [[nodiscard]] std::size_t remaining() const noexcept { return data_.size() - pos_; }

    std::uint8_t u8() { return std::to_integer<std::uint8_t>(take(1)[0]); }

    std::uint16_t u16_le() { return load<std::uint16_t, false>(); }
    std::uint16_t u16_be() { return load<std::uint16_t, true>(); }
    std::uint32_t u32_le() { return load<std::uint32_t, false>(); }
    std::uint32_t u32_be() { return load<std::uint32_t, true>(); }
    std::uint64_t u64_le() { return load<std::uint64_t, false>(); }
    std::uint64_t u64_be() { return load<std::uint64_t, true>(); }

    std::int32_t i32_le() { return static_cast<std::int32_t>(u32_le()); }
    std::int32_t i32_be() { return static_cast<std::int32_t>(u32_be()); }
    std::int64_t i64_le() { return static_cast<std::int64_t>(u64_le()); }
    std::int64_t i64_be() { return static_cast<std::int64_t>(u64_be()); }

    float f32_le() { return std::bit_cast<float>(u32_le()); }
    float f32_be() { return std::bit_cast<float>(u32_be()); }
    double f64_le() { return std::bit_cast<double>(u64_le()); }
    double f64_be() { return std::bit_cast<double>(u64_be()); }

    std::span<const std::byte> bytes(std::size_t n) { return take(n); }
    void skip(std::size_t n) { take(n); }

    std::string utf8(std::size_t n) {
        const auto b = take(n);
        return {reinterpret_cast<const char*>(b.data()), b.size()};
    }

private:
    template <typename T, bool BigEndian>
    T load() {
        const auto b = take(sizeof(T));
        T value{};
        for (std::size_t i = 0; i < sizeof(T); ++i) {
            const std::size_t shift = (BigEndian ? sizeof(T) - 1 - i : i) * 8;
            value = static_cast<T>(
                value | static_cast<T>(static_cast<T>(std::to_integer<std::uint8_t>(b[i])) << shift));
        }
        return value;
    }

    std::span<const std::byte> take(std::size_t n) {
        if (n > remaining()) {
            throw bounds_error("ByteReader: need " + std::to_string(n) + " byte(s) at offset " +
                               std::to_string(pos_) + ", only " + std::to_string(remaining()) +
                               " remain");
        }
        const auto out = data_.subspan(pos_, n);
        pos_ += n;
        return out;
    }

    std::span<const std::byte> data_;
    std::size_t pos_ = 0;
};

} // namespace cenda::omformat
