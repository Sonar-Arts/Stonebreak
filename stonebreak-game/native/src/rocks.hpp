/* Rock shapes for the boulder feature (boulders.cpp): a voxel shape type, the procedural shape source, and the
 * SBRB rock shape bank (an offline-baked set of shapes, e.g. from a generative model trained on rock scans).
 *
 * SBRB v1 (little-endian, no padding):
 *   header 32 bytes: "SBRB", u16 version = 1, u16 header_size = 32, u32 shape_count, u32 flags (bit0: some
 *     shapes carry a material plane), u32 bank_id, u32 reserved, u64 FNV-1a 64 of every byte after the header.
 *   shape table, shape_count x 16 bytes: u8 size_class (0 pebble .. 4 erratic), u8 dx, u8 dy (UP), u8 dz,
 *     u8 sink (rows meant below ground), u8 flags (bit0 flat top, bit1 material plane, bit2 slab-like),
 *     u16 reserved, u32 payload offset (from file start), u32 payload length.
 *   payload: occupancy bits, index (y * dz + z) * dx + x, LSB-first, ceil(n / 8) bytes; with flags bit1 a 2-bit
 *     material plane in the same order, LSB-first, ceil(n / 4) bytes (0 body, 1 moss hint, 2 rough hint, 3 = 0).
 *   rules: dx, dz in 1..15, dy in 1..20, one 6-connected component, bottom row non-empty, sink < dy.
 * A bank is registered once per process under its id (sbw_register_rock_bank) and selected per world by
 * sbw_gen_config.rock_bank: the shapes are part of a world's identity, so a changed bank needs a new id. */
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace sbw {

/* size classes (by the shape's largest horizontal extent) */
enum RockClass : uint8_t { ROCK_PEBBLE = 0, ROCK_SMALL = 1, ROCK_MEDIUM = 2, ROCK_LARGE = 3, ROCK_ERRATIC = 4 };
constexpr int ROCK_CLASSES = 5;
constexpr int ROCK_MAX_DXZ = 15;  /* every cell within 7 columns of the anchor (feature reach rule) */
constexpr int ROCK_MAX_DY = 20;

/* material hints per voxel */
enum RockHint : uint8_t { HINT_BODY = 0, HINT_MOSS = 1, HINT_ROUGH = 2 };

struct RockShape {
    uint8_t cls = ROCK_SMALL;
    uint8_t dx = 1, dy = 1, dz = 1;
    uint8_t sink = 0;
    uint8_t flags = 0;           /* SBRB shape flags */
    std::vector<uint8_t> occ;    /* one byte per voxel (0/1), index (y * dz + z) * dx + x */
    std::vector<uint8_t> hint;   /* empty = all body; else one RockHint per voxel */
    [[nodiscard]] int index(int x, int y, int z) const { return (y * dz + z) * dx + x; }
    [[nodiscard]] bool at(int x, int y, int z) const { return occ[static_cast<size_t>(index(x, y, z))] != 0; }
    [[nodiscard]] uint8_t hintAt(int x, int y, int z) const {
        return hint.empty() ? static_cast<uint8_t>(HINT_BODY) : hint[static_cast<size_t>(index(x, y, z))];
    }
    [[nodiscard]] int volume() const;
};

constexpr uint8_t ROCK_FLAG_FLAT_TOP = 0x1, ROCK_FLAG_MATERIAL = 0x2, ROCK_FLAG_SLAB = 0x4;

/* A procedural rock: a noise-deformed, randomly oriented ellipsoid (lobes, flats, rough voxels, optional flat
 * top), trimmed to its largest 6-connected component, for size class `cls`; pure function of (cls, seed). */
RockShape proceduralRock(int cls, uint64_t seed);

/* An SBRB bank: shapes grouped by size class. */
class RockBank {
public:
    uint32_t id = 0;
    uint64_t hash = 0;
    std::vector<RockShape> byClass[ROCK_CLASSES];
    [[nodiscard]] size_t count() const;
    /* a shape of class cls picked by `h`, or null when the bank has none of that class */
    [[nodiscard]] const RockShape* pick(int cls, uint64_t h) const {
        const auto& v = byClass[cls];
        return v.empty() ? nullptr : &v[static_cast<size_t>(h % v.size())];
    }
};

/* Parses and validates an SBRB image; null + error message on failure. */
std::shared_ptr<const RockBank> parseRockBank(const uint8_t* data, size_t len, std::string& error);
/* Serializes shapes into an SBRB image (the baker; tests and the placeholder tool use it). */
std::vector<uint8_t> bakeRockBank(uint32_t bankId, const std::vector<RockShape>& shapes);
/* The placeholder bank shipped before the learned one: procedural shapes, `perClass` per class 2..4. */
std::vector<RockShape> placeholderShapes(int perClass, uint64_t seed);

/* Process-wide bank registry (immutable banks, thread-safe). */
bool registerRockBank(uint32_t id, const uint8_t* data, size_t len, std::string& error);
std::shared_ptr<const RockBank> findRockBank(uint32_t id);

} // namespace sbw
