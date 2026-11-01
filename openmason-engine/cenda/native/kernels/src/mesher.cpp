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
 *    uint16 row masks (bit = lz, row = lx*wh + ly), then all six face-cull
 *    masks per row are a couple of ANDs/shifts; tzcnt iteration over the set
 *    bits skips empty/buried cells without touching them. Only face EXISTENCE
 *    is bitmasked — per-corner lighting runs unchanged on emitted faces, and
 *    transparent cubes (per-id `adj != id` part of the rule is not class-maskable) fall back
 *    to the scalar probe per cell. Portable <bit> ops only, no intrinsics.
 *
 * Dimensions are the caller's (#385): chunk_size 1..CK_MESH_MAX_CHUNK_SIZE (a
 * row mask holds one bit per lz, so 16 at most) and any positive world_height;
 * the row masks live in per-thread buffers grown to the largest column seen. */
#include "cenda/kernels.h"

#include <algorithm>
#include <bit>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

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
    int cs;   // chunk size (x and z)
    int wh;   // world height

    [[nodiscard]] uint8_t classOf(int32_t id) const {
        return (id >= 0 && id < clsLen) ? cls[id] : 0;
    }

    // Axis-aligned neighbor id for culling; unloaded neighbor/world edge = AIR.
    [[nodiscard]] int32_t adjacentId(int x, int y, int z) const {
        if (y < 0 || y >= wh) return airId;
        if (x >= 0 && x < cs && z >= 0 && z < cs) {
            return blocks[(y * cs + z) * cs + x];
        }
        if (x < 0)  return planeXn ? planeXn[y * cs + z] : airId;
        if (x >= cs) return planeXp ? planeXp[y * cs + z] : airId;
        if (z < 0)  return planeZn ? planeZn[y * cs + x] : airId;
        return planeZp ? planeZp[y * cs + x] : airId;
    }

    // WorldLightingContext.isSolidAt: unloaded chunk / out-of-height = false.
    [[nodiscard]] bool isSolidAt(int x, int y, int z) const {
        if (y < 0 || y >= wh) return false;
        const bool xn = x < 0, xp = x >= cs, zn = z < 0, zp = z >= cs;
        const int16_t* col = nullptr;
        if (!xn && !xp && !zn && !zp) {
            return (classOf(blocks[(y * cs + z) * cs + x]) & CK_CLASS_OPAQUE_LIGHT) != 0;
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
            return plane != nullptr && (classOf(plane[y * cs + z]) & CK_CLASS_OPAQUE_LIGHT) != 0;
        }
        const int16_t* plane = zn ? planeZn : planeZp;
        return plane != nullptr && (classOf(plane[y * cs + x]) & CK_CLASS_OPAQUE_LIGHT) != 0;
    }

    // heights grid lookup, local coords in [-1, cs]: a (cs+2)^2 grid.
    [[nodiscard]] int columnHeight(int lx, int lz) const {
        return heights[(lz + 1) * (cs + 2) + (lx + 1)];
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
            // Columns can only be probed at [-1,cs] — vertex coords are [0,cs]
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
 * path's transparent-cube cells (whose `adj != id` test is per-id).
 * A transparent cube (leaves) culls against the same id AND against any
 * non-transparent neighbour: an opaque block covers the shared plane, and
 * emitting the hidden leaf face only z-fights the opaque block's own face
 * (which IS emitted, since it borders a transparent cell). Shaped stamps are
 * flagged transparent by the class table, so a leaf beside a stair still
 * renders. Mirrors MmsCcoAdapter.shouldRenderAgainst — keep in lockstep. */
bool renderFace(const MeshInput& in, int32_t id, bool selfTransparent,
                int lx, int ly, int lz, int face) {
    const int32_t adj = in.adjacentId(lx + FACE_DX[face], ly + FACE_DY[face],
                                      lz + FACE_DZ[face]);
    if (adj == in.airId) return true;
    const bool adjTransparent = (in.classOf(adj) & CK_CLASS_TRANSPARENT) != 0;
    if (selfTransparent) return adj != id && adjTransparent;
    return adjTransparent;
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
    const int cs = in.cs;
    for (int lx = 0; lx < cs; lx++) {
        for (int ly = 0; ly <= max_y; ly++) {
            for (int lz = 0; lz < cs; lz++) {
                const int32_t id = in.blocks[(ly * cs + lz) * cs + lx];
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
 * row or all-air (every lz bit set) at the world caps; ±z: a 1-bit shift of the
 * row with the border bit injected). Ascending (lx, ly, tzcnt-lz, face)
 * iteration reproduces the scalar emission order exactly. */

struct Masks {
    std::vector<uint16_t> opq;   // [cs*wh] opaque cubes (CUBE set, TRANSPARENT clear)
    std::vector<uint16_t> tcube; // [cs*wh] transparent cubes (CUBE and TRANSPARENT)
    std::vector<uint16_t> n;     // [cs*wh] "renderable-against": air OR transparent class
    std::vector<uint16_t> pxnN;  // [wh] N-predicate of plane_xn, bit = z (NULL plane = air)
    std::vector<uint16_t> pxpN;
    std::vector<uint16_t> znN;   // [wh] N-predicate of plane_zn, bit = x
    std::vector<uint16_t> zpN;
    uint16_t full = 0;           // every lz bit of a row: (1 << cs) - 1

    /* Sizes the buffers for a cs x wh column (grow only) and zeroes the cell masks. */
    void prepare(int cs, int wh) {
        const auto cells = static_cast<size_t>(cs) * static_cast<size_t>(wh);
        for (auto* v : {&opq, &tcube, &n}) {
            if (v->size() < cells) v->resize(cells);
            std::fill(v->begin(), v->begin() + static_cast<ptrdiff_t>(cells), uint16_t{0});
        }
        for (auto* v : {&pxnN, &pxpN, &znN, &zpN}) {
            if (v->size() < static_cast<size_t>(wh)) v->resize(static_cast<size_t>(wh));
        }
        full = static_cast<uint16_t>((1u << cs) - 1u);
    }
};

/* One linear sweep over blocks[] in memory order classifies every cell once.
 * Build range must reach maxYb = min(max_y+1, wh-1): cells above max_y are
 * never emitted but ARE read as +y neighbors (the scalar path probes real
 * block data there). */
void buildMasks(const MeshInput& in, int maxYb, Masks& m) {
    const int CS = in.cs;
    const int WH = in.wh;
    m.prepare(CS, WH);
    uint16_t* const opq = m.opq.data();
    uint16_t* const tcube = m.tcube.data();
    uint16_t* const nm = m.n.data();

    for (int y = 0; y <= maxYb; y++) {
        for (int z = 0; z < CS; z++) {
            const int16_t* row = in.blocks + static_cast<ptrdiff_t>(y * CS + z) * CS;
            const auto zbit = static_cast<uint16_t>(1u << z);
            for (int x = 0; x < CS; x++) {
                const int32_t id = row[x];
                if (id == in.airId) {
                    nm[x * WH + y] = static_cast<uint16_t>(nm[x * WH + y] | zbit);
                    continue;
                }
                const uint8_t c = in.classOf(id);
                if ((c & CK_CLASS_TRANSPARENT) != 0) {
                    nm[x * WH + y] = static_cast<uint16_t>(nm[x * WH + y] | zbit);
                    if ((c & CK_CLASS_CUBE) != 0) {
                        tcube[x * WH + y] = static_cast<uint16_t>(tcube[x * WH + y] | zbit);
                    }
                } else if ((c & CK_CLASS_CUBE) != 0) {
                    opq[x * WH + y] = static_cast<uint16_t>(opq[x * WH + y] | zbit);
                }
            }
        }
    }

    for (int y = 0; y < WH; y++) {
        uint16_t xn = 0, xp = 0, zn = 0, zp = 0;
        if (y <= maxYb) {
            if (in.planeXn == nullptr) xn = m.full;
            if (in.planeXp == nullptr) xp = m.full;
            if (in.planeZn == nullptr) zn = m.full;
            if (in.planeZp == nullptr) zp = m.full;
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
        m.pxnN.data()[y] = xn;
        m.pxpN.data()[y] = xp;
        m.znN.data()[y] = zn;
        m.zpN.data()[y] = zp;
    }
}

int32_t meshBitmask(const MeshInput& in, int max_y, bool smooth, const Masks& m,
                    float* out_quads, int32_t cap_quads) {
    EmitState es{out_quads, cap_quads};
    const int CS = in.cs;
    const int WH = in.wh;
    const uint16_t* const nm = m.n.data();
    const uint16_t* const opqM = m.opq.data();
    const uint16_t* const tcubeM = m.tcube.data();
    const uint16_t* const znM = m.znN.data();
    const uint16_t* const zpM = m.zpN.data();
    for (int lx = 0; lx < CS; lx++) {
        const uint16_t* selfNCol = nm + lx * WH;
        const uint16_t* westN = (lx > 0) ? nm + (lx - 1) * WH : m.pxnN.data();
        const uint16_t* eastN = (lx < CS - 1) ? nm + (lx + 1) * WH : m.pxpN.data();
        for (int ly = 0; ly <= max_y; ly++) {
            const uint16_t opq = opqM[lx * WH + ly];
            const uint16_t tc = tcubeM[lx * WH + ly];
            if ((opq | tc) == 0) {
                continue; // all-air / non-cube row: 2 loads and done
            }
            const uint16_t selfN = selfNCol[ly];
            const uint16_t fTop = static_cast<uint16_t>(
                opq & ((ly < WH - 1) ? selfNCol[ly + 1] : m.full));
            const uint16_t fBottom = static_cast<uint16_t>(
                opq & ((ly > 0) ? selfNCol[ly - 1] : m.full));
            const uint16_t znBit = static_cast<uint16_t>((znM[ly] >> lx) & 1u);
            const uint16_t zpBit = static_cast<uint16_t>((zpM[ly] >> lx) & 1u);
            const uint16_t fNorth = static_cast<uint16_t>(
                opq & static_cast<uint16_t>((selfN << 1) | znBit));
            const uint16_t fSouth = static_cast<uint16_t>(
                opq & static_cast<uint16_t>((selfN >> 1) | (zpBit << (CS - 1))));
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
                                 int32_t chunk_size, int32_t world_height,
                                 const uint8_t* class_table, int32_t class_table_len,
                                 int32_t air_id,
                                 const int16_t* plane_xn, const int16_t* plane_xp,
                                 const int16_t* plane_zn, const int16_t* plane_zp,
                                 const int16_t* corner_nn, const int16_t* corner_pn,
                                 const int16_t* corner_np, const int16_t* corner_pp,
                                 const int16_t* heights,
                                 int32_t max_y, int32_t smooth_lighting,
                                 float* out_quads, int32_t cap_quads) {
    if (blocks == nullptr || class_table == nullptr || heights == nullptr || out_quads == nullptr
            || chunk_size < 1 || chunk_size > CK_MESH_MAX_CHUNK_SIZE || world_height < 1) {
        return CK_MESH_BAD_ARGS;
    }
    const int WH = world_height;
    if (max_y >= WH) max_y = WH - 1;
    const bool smooth = smooth_lighting != 0;

    const MeshInput in{blocks, class_table, class_table_len, air_id,
                       plane_xn, plane_xp, plane_zn, plane_zp,
                       corner_nn, corner_pn, corner_np, corner_pp, heights,
                       chunk_size, world_height};

    const char* impl = std::getenv("CENDA_MESHER_IMPL");
    if (impl != nullptr && std::strcmp(impl, "scalar") == 0) {
        return meshScalar(in, max_y, smooth, out_quads, cap_quads);
    }

    thread_local Masks m;
    buildMasks(in, (max_y + 1 < WH) ? max_y + 1 : WH - 1, m);
    return meshBitmask(in, max_y, smooth, m, out_quads, cap_quads);
}
