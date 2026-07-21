/* Native chunk mesher: culling + per-vertex lighting for standard cube blocks.
 * Semantics are a 1:1 port of the Java MmsCcoAdapter cube path and
 * VertexLightSampler (smooth + flat modes) — the Java parity test compares
 * output bit-for-bit, so any change here must mirror the Java sampler exactly.
 * No libm is used; float ops are portable with -ffp-contract=off.
 *
 * Two culling implementations produce BYTE-IDENTICAL output (same quads, same
 * order, same floats — enforced by the C++ scalar-vs-bitmask memcmp fuzz test):
 *  - scalar: the original per-cell 6-probe loop (CENDA_MESHER_IMPL=scalar).
 *  - bitmask (default): one linear classification sweep builds z-packed
 *    uint16 row masks (bit = lz, row = lx*256 + ly), then all six face-cull
 *    masks per row are a couple of ANDs/shifts; tzcnt iteration over the set
 *    bits skips empty/buried cells without touching them. Only face EXISTENCE
 *    is bitmasked — per-corner lighting runs unchanged on emitted faces, and
 *    transparent cubes (per-id `adj != id` rule, not class-maskable) fall back
 *    to the scalar probe per cell. Portable <bit> ops only, no intrinsics. */
#include "cenda/kernels.h"

#include <bit>
#include <cstddef>
#include <cstdlib>
#include <cstring>

namespace {

constexpr int CS = 16;   // chunk size
constexpr int WH = 256;  // world height

// MmsCuboidGenerator.FACE_VERTEX_OFFSETS, verbatim.
constexpr int FACE_OFFSETS[6][4][3] = {
    {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}}, // top (+y)
    {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, // bottom (-y)
    {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}, // north (-z)
    {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}, // south (+z)
    {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}, // east (+x)
    {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, // west (-x)
};

constexpr int FACE_DX[6] = {0, 0, 0, 0, 1, -1};
constexpr int FACE_DY[6] = {1, -1, 0, 0, 0, 0};
constexpr int FACE_DZ[6] = {0, 0, -1, 1, 0, 0};

// VertexLightSampler.sampleAoFactor tables: normal offset + two tangents.
constexpr int AO_N[6][3]  = {{0,0,0},  {0,-1,0}, {0,0,-1}, {0,0,0},  {0,0,0},  {-1,0,0}};
constexpr int AO_T1[6][3] = {{-1,0,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {0,-1,0}, {0,-1,0}};
constexpr int AO_T2[6][3] = {{0,0,-1}, {0,0,-1}, {0,-1,0}, {0,-1,0}, {0,0,-1}, {0,0,-1}};

constexpr float AO_PER_NEIGHBOR = 0.13f;

struct MeshInput {
    const int16_t* blocks;
    const uint8_t* cls;
    int32_t clsLen;
    int32_t airId;
    const int16_t* planeXn;
    const int16_t* planeXp;
    const int16_t* planeZn;
    const int16_t* planeZp;
    const int16_t* cornerNn;
    const int16_t* cornerPn;
    const int16_t* cornerNp;
    const int16_t* cornerPp;
    const int16_t* heights;

    [[nodiscard]] uint8_t classOf(int32_t id) const {
        return (id >= 0 && id < clsLen) ? cls[id] : 0;
    }

    // Axis-aligned neighbor id for culling; unloaded neighbor/world edge = AIR.
    [[nodiscard]] int32_t adjacentId(int x, int y, int z) const {
        if (y < 0 || y >= WH) return airId;
        if (x >= 0 && x < CS && z >= 0 && z < CS) {
            return blocks[(y * CS + z) * CS + x];
        }
        if (x < 0)  return planeXn ? planeXn[y * CS + z] : airId;
        if (x >= CS) return planeXp ? planeXp[y * CS + z] : airId;
        if (z < 0)  return planeZn ? planeZn[y * CS + x] : airId;
        return planeZp ? planeZp[y * CS + x] : airId;
    }

    // WorldLightingContext.isSolidAt: unloaded chunk / out-of-height = false.
    [[nodiscard]] bool isSolidAt(int x, int y, int z) const {
        if (y < 0 || y >= WH) return false;
        const bool xn = x < 0, xp = x >= CS, zn = z < 0, zp = z >= CS;
        const int16_t* col = nullptr;
        if (!xn && !xp && !zn && !zp) {
            return (classOf(blocks[(y * CS + z) * CS + x]) & CK_CLASS_OPAQUE_LIGHT) != 0;
        }
        if (xn && zn) col = cornerNn;
        else if (xp && zn) col = cornerPn;
        else if (xn && zp) col = cornerNp;
        else if (xp && zp) col = cornerPp;
        if ((xn || xp) && (zn || zp)) {
            // Diagonal probe: corner column or nothing — falling through to a
            // face plane here would index it out of range.
            return col != nullptr && (classOf(col[y]) & CK_CLASS_OPAQUE_LIGHT) != 0;
        }
        if (xn || xp) {
            const int16_t* plane = xn ? planeXn : planeXp;
            return plane != nullptr && (classOf(plane[y * CS + z]) & CK_CLASS_OPAQUE_LIGHT) != 0;
        }
        const int16_t* plane = zn ? planeZn : planeZp;
        return plane != nullptr && (classOf(plane[y * CS + x]) & CK_CLASS_OPAQUE_LIGHT) != 0;
    }

    // heights grid lookup, local coords in [-1, 16].
    [[nodiscard]] int columnHeight(int lx, int lz) const {
        return heights[(lz + 1) * 18 + (lx + 1)];
    }
};

// VertexLightSampler.sampleSkyFactor with SKY_FLOOR = 0.
float skyFactor(const MeshInput& in, int ivx, int ivy, int ivz, int face, bool smooth) {
    int lit = 0;
    int sampled = 0;
    const int lo = smooth ? -1 : 0;
    for (int a = lo; a <= 0; a++) {
        for (int b = lo; b <= 0; b++) {
            int cx, cy, cz;
            switch (face) {
                case 0: cx = ivx + a; cy = ivy;     cz = ivz + b; break;
                case 1: cx = ivx + a; cy = ivy - 1; cz = ivz + b; break;
                case 2: cx = ivx + a; cy = ivy + b; cz = ivz - 1; break;
                case 3: cx = ivx + a; cy = ivy + b; cz = ivz;     break;
                case 4: cx = ivx;     cy = ivy + a; cz = ivz + b; break;
                default: cx = ivx - 1; cy = ivy + a; cz = ivz + b; break;
            }
            // Columns can only be probed at [-1,16] — vertex coords are [0,16]
            // and a,b are non-positive, matching the Java sampler's reach.
            int h = in.columnHeight(cx, cz);
            if (h < 0) continue;
            sampled++;
            if (cy >= h) lit++;
        }
    }
    if (sampled == 0) return 1.0f;
    return static_cast<float>(lit) / static_cast<float>(sampled);
}

float aoFactor(const MeshInput& in, int ivx, int ivy, int ivz, int face) {
    const int* n = AO_N[face];
    const int* t1 = AO_T1[face];
    const int* t2 = AO_T2[face];
    const bool side1 = in.isSolidAt(ivx + n[0] + t1[0], ivy + n[1] + t1[1], ivz + n[2] + t1[2]);
    const bool side2 = in.isSolidAt(ivx + n[0] + t2[0], ivy + n[1] + t2[1], ivz + n[2] + t2[2]);
    const bool corner = in.isSolidAt(ivx + n[0] + t1[0] + t2[0], ivy + n[1] + t1[1] + t2[1],
                                     ivz + n[2] + t1[2] + t2[2]);
    int count = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
    if (side1 && side2) count = 3;
    return 1.0f - AO_PER_NEIGHBOR * static_cast<float>(count);
}

/* The exact scalar cull rule, shared by the scalar path and the bitmask
 * path's transparent-cube cells (whose `adj != id` test is per-id). */
bool renderFace(const MeshInput& in, int32_t id, bool selfTransparent,
                int lx, int ly, int lz, int face) {
    const int32_t adj = in.adjacentId(lx + FACE_DX[face], ly + FACE_DY[face],
                                      lz + FACE_DZ[face]);
    if (adj == in.airId) return true;
    if (selfTransparent) return adj != id;
    return (in.classOf(adj) & CK_CLASS_TRANSPARENT) != 0;
}

struct EmitState {
    float* out;
    int32_t cap;
    int32_t quads = 0;
    int32_t needed = 0;
};

void emitQuad(const MeshInput& in, EmitState& es, int lx, int ly, int lz,
              int face, int32_t id, bool smooth) {
    es.needed++;
    if (es.quads >= es.cap) {
        return; // keep counting so the caller can retry sized
    }
    float* q = es.out + static_cast<ptrdiff_t>(es.quads) * 9;
    q[0] = static_cast<float>(lx);
    q[1] = static_cast<float>(ly);
    q[2] = static_cast<float>(lz);
    q[3] = static_cast<float>(face);
    q[4] = static_cast<float>(id);
    for (int c = 0; c < 4; c++) {
        const int ivx = lx + FACE_OFFSETS[face][c][0];
        const int ivy = ly + FACE_OFFSETS[face][c][1];
        const int ivz = lz + FACE_OFFSETS[face][c][2];
        float light = skyFactor(in, ivx, ivy, ivz, face, smooth);
        if (smooth) {
            light *= aoFactor(in, ivx, ivy, ivz, face);
        }
        q[5 + c] = light;
    }
    es.quads++;
}

/* ─────────────────────────── scalar path ─────────────────────────── */

int32_t meshScalar(const MeshInput& in, int max_y, bool smooth,
                   float* out_quads, int32_t cap_quads) {
    EmitState es{out_quads, cap_quads};
    for (int lx = 0; lx < CS; lx++) {
        for (int ly = 0; ly <= max_y; ly++) {
            for (int lz = 0; lz < CS; lz++) {
                const int32_t id = in.blocks[(ly * CS + lz) * CS + lx];
                const uint8_t cls = in.classOf(id);
                if ((cls & CK_CLASS_CUBE) == 0) {
                    continue;
                }
                const bool selfTransparent = (cls & CK_CLASS_TRANSPARENT) != 0;
                for (int face = 0; face < 6; face++) {
                    if (renderFace(in, id, selfTransparent, lx, ly, lz, face)) {
                        emitQuad(in, es, lx, ly, lz, face, id, smooth);
                    }
                }
            }
        }
    }
    return (es.needed > es.cap) ? -es.needed : es.quads;
}

/* ─────────────────────────── bitmask path ───────────────────────────
 *
 * Row layout: uint16 per (lx, ly), bit = lz. All six neighbor directions are
 * one row lookup (±x: adjacent lx column or a border table; ±y: adjacent ly
 * row or all-air 0xFFFF at the world caps; ±z: a 1-bit shift of the row with
 * the border bit injected). Ascending (lx, ly, tzcnt-lz, face) iteration
 * reproduces the scalar emission order exactly. */

struct Masks {
    uint16_t opq[CS * WH];   // opaque cubes (CUBE set, TRANSPARENT clear)
    uint16_t tcube[CS * WH]; // transparent cubes (CUBE and TRANSPARENT)
    uint16_t n[CS * WH];     // "renderable-against": air OR transparent class
    uint16_t pxnN[WH];       // N-predicate of plane_xn, bit = z (NULL plane = air)
    uint16_t pxpN[WH];
    uint16_t znN[WH];        // N-predicate of plane_zn, bit = x
    uint16_t zpN[WH];
};

/* One linear sweep over blocks[] in memory order classifies every cell once.
 * Build range must reach maxYb = min(max_y+1, 255): cells above max_y are
 * never emitted but ARE read as +y neighbors (the scalar path probes real
 * block data there). */
void buildMasks(const MeshInput& in, int maxYb, Masks& m) {
    std::memset(m.opq, 0, sizeof(m.opq));
    std::memset(m.tcube, 0, sizeof(m.tcube));
    std::memset(m.n, 0, sizeof(m.n));

    for (int y = 0; y <= maxYb; y++) {
        for (int z = 0; z < CS; z++) {
            const int16_t* row = in.blocks + static_cast<ptrdiff_t>(y * CS + z) * CS;
            const auto zbit = static_cast<uint16_t>(1u << z);
            for (int x = 0; x < CS; x++) {
                const int32_t id = row[x];
                if (id == in.airId) {
                    m.n[x * WH + y] = static_cast<uint16_t>(m.n[x * WH + y] | zbit);
                    continue;
                }
                const uint8_t c = in.classOf(id);
                if ((c & CK_CLASS_TRANSPARENT) != 0) {
                    m.n[x * WH + y] = static_cast<uint16_t>(m.n[x * WH + y] | zbit);
                    if ((c & CK_CLASS_CUBE) != 0) {
                        m.tcube[x * WH + y] = static_cast<uint16_t>(m.tcube[x * WH + y] | zbit);
                    }
                } else if ((c & CK_CLASS_CUBE) != 0) {
                    m.opq[x * WH + y] = static_cast<uint16_t>(m.opq[x * WH + y] | zbit);
                }
            }
        }
    }

    for (int y = 0; y < WH; y++) {
        uint16_t xn = 0, xp = 0, zn = 0, zp = 0;
        if (y <= maxYb) {
            if (in.planeXn == nullptr) xn = 0xFFFF;
            if (in.planeXp == nullptr) xp = 0xFFFF;
            if (in.planeZn == nullptr) zn = 0xFFFF;
            if (in.planeZp == nullptr) zp = 0xFFFF;
            for (int i = 0; i < CS; i++) {
                const auto bit = static_cast<uint16_t>(1u << i);
                if (in.planeXn != nullptr) {
                    const int32_t id = in.planeXn[y * CS + i]; // i = z
                    if (id == in.airId || (in.classOf(id) & CK_CLASS_TRANSPARENT) != 0) {
                        xn = static_cast<uint16_t>(xn | bit);
                    }
                }
                if (in.planeXp != nullptr) {
                    const int32_t id = in.planeXp[y * CS + i];
                    if (id == in.airId || (in.classOf(id) & CK_CLASS_TRANSPARENT) != 0) {
                        xp = static_cast<uint16_t>(xp | bit);
                    }
                }
                if (in.planeZn != nullptr) {
                    const int32_t id = in.planeZn[y * CS + i]; // i = x
                    if (id == in.airId || (in.classOf(id) & CK_CLASS_TRANSPARENT) != 0) {
                        zn = static_cast<uint16_t>(zn | bit);
                    }
                }
                if (in.planeZp != nullptr) {
                    const int32_t id = in.planeZp[y * CS + i];
                    if (id == in.airId || (in.classOf(id) & CK_CLASS_TRANSPARENT) != 0) {
                        zp = static_cast<uint16_t>(zp | bit);
                    }
                }
            }
        }
        m.pxnN[y] = xn;
        m.pxpN[y] = xp;
        m.znN[y] = zn;
        m.zpN[y] = zp;
    }
}

int32_t meshBitmask(const MeshInput& in, int max_y, bool smooth, const Masks& m,
                    float* out_quads, int32_t cap_quads) {
    EmitState es{out_quads, cap_quads};
    for (int lx = 0; lx < CS; lx++) {
        const uint16_t* selfNCol = &m.n[lx * WH];
        const uint16_t* westN = (lx > 0) ? &m.n[(lx - 1) * WH] : m.pxnN;
        const uint16_t* eastN = (lx < CS - 1) ? &m.n[(lx + 1) * WH] : m.pxpN;
        for (int ly = 0; ly <= max_y; ly++) {
            const uint16_t opq = m.opq[lx * WH + ly];
            const uint16_t tc = m.tcube[lx * WH + ly];
            if ((opq | tc) == 0) {
                continue; // all-air / non-cube row: 2 loads and done
            }
            const uint16_t selfN = selfNCol[ly];
            const uint16_t fTop = static_cast<uint16_t>(
                opq & ((ly < WH - 1) ? selfNCol[ly + 1] : uint16_t{0xFFFF}));
            const uint16_t fBottom = static_cast<uint16_t>(
                opq & ((ly > 0) ? selfNCol[ly - 1] : uint16_t{0xFFFF}));
            const uint16_t znBit = static_cast<uint16_t>((m.znN[ly] >> lx) & 1u);
            const uint16_t zpBit = static_cast<uint16_t>((m.zpN[ly] >> lx) & 1u);
            const uint16_t fNorth = static_cast<uint16_t>(
                opq & static_cast<uint16_t>((selfN << 1) | znBit));
            const uint16_t fSouth = static_cast<uint16_t>(
                opq & static_cast<uint16_t>((selfN >> 1) | (zpBit << 15)));
            const uint16_t fEast = static_cast<uint16_t>(opq & eastN[ly]);
            const uint16_t fWest = static_cast<uint16_t>(opq & westN[ly]);

            uint32_t any = static_cast<uint32_t>(
                fTop | fBottom | fNorth | fSouth | fEast | fWest | tc);
            while (any != 0) {
                const int lz = std::countr_zero(any);
                any &= any - 1;
                const int32_t id = in.blocks[(ly * CS + lz) * CS + lx];
                if (((tc >> lz) & 1u) != 0) {
                    // Transparent cube: the per-id adj != id rule needs real probes.
                    for (int face = 0; face < 6; face++) {
                        if (renderFace(in, id, true, lx, ly, lz, face)) {
                            emitQuad(in, es, lx, ly, lz, face, id, smooth);
                        }
                    }
                } else {
                    const uint16_t faceBits[6] = {fTop, fBottom, fNorth, fSouth, fEast, fWest};
                    for (int face = 0; face < 6; face++) {
                        if (((faceBits[face] >> lz) & 1u) != 0) {
                            emitQuad(in, es, lx, ly, lz, face, id, smooth);
                        }
                    }
                }
            }
        }
    }
    return (es.needed > es.cap) ? -es.needed : es.quads;
}

} // namespace

extern "C" int32_t ck_mesh_chunk(const int16_t* blocks,
                                 const uint8_t* class_table, int32_t class_table_len,
                                 int32_t air_id,
                                 const int16_t* plane_xn, const int16_t* plane_xp,
                                 const int16_t* plane_zn, const int16_t* plane_zp,
                                 const int16_t* corner_nn, const int16_t* corner_pn,
                                 const int16_t* corner_np, const int16_t* corner_pp,
                                 const int16_t* heights,
                                 int32_t max_y, int32_t smooth_lighting,
                                 float* out_quads, int32_t cap_quads) {
    if (blocks == nullptr || class_table == nullptr || heights == nullptr || out_quads == nullptr) {
        return -1;
    }
    if (max_y >= WH) max_y = WH - 1;
    const bool smooth = smooth_lighting != 0;

    const MeshInput in{blocks, class_table, class_table_len, air_id,
                       plane_xn, plane_xp, plane_zn, plane_zp,
                       corner_nn, corner_pn, corner_np, corner_pp, heights};

    const char* impl = std::getenv("CENDA_MESHER_IMPL");
    if (impl != nullptr && std::strcmp(impl, "scalar") == 0) {
        return meshScalar(in, max_y, smooth, out_quads, cap_quads);
    }

    Masks m;
    buildMasks(in, (max_y + 1 < WH) ? max_y + 1 : WH - 1, m);
    return meshBitmask(in, max_y, smooth, m, out_quads, cap_quads);
}
