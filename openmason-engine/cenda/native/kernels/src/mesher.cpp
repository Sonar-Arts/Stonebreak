/* Native chunk mesher: culling + per-vertex lighting for standard cube blocks.
 * Semantics are a 1:1 port of the Java MmsCcoAdapter cube path and
 * VertexLightSampler (smooth + flat modes) — the Java parity test compares
 * output bit-for-bit, so any change here must mirror the Java sampler exactly.
 * No libm is used; float ops are portable with -ffp-contract=off. */
#include "cenda/kernels.h"

#include <cstddef>

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

    int32_t quads = 0;
    int32_t needed = 0;

    for (int lx = 0; lx < CS; lx++) {
        for (int ly = 0; ly <= max_y; ly++) {
            for (int lz = 0; lz < CS; lz++) {
                const int32_t id = blocks[(ly * CS + lz) * CS + lx];
                const uint8_t cls = in.classOf(id);
                if ((cls & CK_CLASS_CUBE) == 0) {
                    continue;
                }
                const bool selfTransparent = (cls & CK_CLASS_TRANSPARENT) != 0;

                for (int face = 0; face < 6; face++) {
                    const int32_t adj = in.adjacentId(lx + FACE_DX[face], ly + FACE_DY[face],
                                                      lz + FACE_DZ[face]);
                    bool render;
                    if (adj == air_id) {
                        render = true;
                    } else if (selfTransparent) {
                        render = adj != id;
                    } else {
                        render = (in.classOf(adj) & CK_CLASS_TRANSPARENT) != 0;
                    }
                    if (!render) {
                        continue;
                    }

                    needed++;
                    if (quads >= cap_quads) {
                        continue; // keep counting so the caller can retry sized
                    }
                    float* q = out_quads + static_cast<ptrdiff_t>(quads) * 9;
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
                    quads++;
                }
            }
        }
    }
    return (needed > cap_quads) ? -needed : quads;
}
