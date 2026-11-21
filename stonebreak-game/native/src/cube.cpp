/* generate_cube: terrain fill (every carver, Density3D, deep layers, river voids, swamp pools) +
 * stalagmites, ores, crystals and limestone - all pure functions of (seed, cube). See worldgen.h. */
#include "decide.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <unordered_map>

namespace sbw {

namespace {

constexpr int DEEP_SPACING = 100;

/* --------------------------------------------------------------------- batched terrain */
/* One bit volume of a carve mask, looked up per 32-row slice instead of per cell (MaskSet::test). */
struct MaskCursor {
    const MaskSet* m = nullptr;
    int64_t cy = INT64_MIN;
    const Slice* s = nullptr;
    bool test(int lx, int64_t y, int lz) {
        const int64_t c = y >> 5;
        if (c != cy) {
            cy = c;
            s = m->find(c);
        }
        if (s == nullptr) return false;
        const int i = cellIdx(lx, static_cast<int>(y & 31), lz);
        return (s->bits[i >> 6] >> (i & 63)) & 1ULL;
    }
};

/* the column cube tiles one fill touches (a cube: 1, a stalagmite pad: up to 3 x 3) */
struct FillTile {
    int64_t cx = INT64_MIN, cz = INT64_MIN;
    std::shared_ptr<const BaseTile> base;
    std::shared_ptr<const CarveTile> carve;
    std::shared_ptr<const DeepTile> deep[SBW_DEEP_LAYERS];
    bool deepLoaded[SBW_DEEP_LAYERS] = {};
    MaskCursor cave, formation;
    const CarveTile& carveOf(Generator& g) {
        if (!carve) {
            carve = g.carve(cx, cz);
            if (!carve) throw std::runtime_error("carve unavailable");
            cave.m = &carve->cave;
            formation.m = &carve->formation;
        }
        return *carve;
    }
};

/* Density channels of a batch of cells. Each channel is sampled only for the cells whose decision can
 * read it (see Decider::flush); a cell whose decision still asks for a missing channel gets it in another
 * round. Every sample is GenPositionArray3D, the same path Node3::point takes, and a point's value does
 * not depend on the batch it rides in - so a batched decision equals the point query bit for bit. */
struct Batch {
    std::vector<int32_t> out;       /* output index */
    std::vector<int32_t> col;       /* tile slot << 10 | column */
    std::vector<int64_t> x, y, z;
    std::vector<float> fz, fx, fy;  /* float positions (y unsquashed) */
    std::vector<float> v[4];        /* channel values */
    std::vector<uint8_t> have;      /* bit per channel */
    std::vector<int32_t> pending, next, need[4];
    std::vector<float> gz, gx, gy, gv;
    void clear() {
        out.clear(); col.clear(); x.clear(); y.clear(); z.clear(); fz.clear(); fx.clear(); fy.clear(); have.clear();
    }
};

const Node3& channelNode(const Generator& g, int ch) {
    return ch == D_CHEESE ? g.cheese : ch == D_SPAG1 ? g.spag1 : ch == D_SPAG2 ? g.spag2 : g.crag;
}

void sampleChannel(const Generator& g, Batch& b, int ch, const std::vector<int32_t>& cells) {
    if (cells.empty()) return;
    const Node3& n = channelNode(g, ch);
    const size_t m = cells.size();
    b.gz.resize(m);
    b.gx.resize(m);
    b.gy.resize(m);
    b.gv.resize(m);
    for (size_t i = 0; i < m; i++) {
        const auto k = static_cast<size_t>(cells[i]);
        b.gz[i] = b.fz[k];
        b.gx[i] = b.fx[k];
        b.gy[i] = b.fy[k] * n.squash;
    }
    n.points(b.gv.data(), static_cast<int>(m), b.gz.data(), b.gx.data(), b.gy.data());
    for (size_t i = 0; i < m; i++) {
        const auto k = static_cast<size_t>(cells[i]);
        b.v[ch][k] = b.gv[i];
        b.have[k] = static_cast<uint8_t>(b.have[k] | (1u << ch));
    }
}

struct NoDensity {
    float operator()(int) const { throw std::logic_error("density outside the batch"); }
};

/* Decides terrain cells over a set of column tiles: cells that need no density at once, the rest in
 * cache-sized batches with lazily sampled density channels. decide(...) of a cell = the generator's
 * decideBlock (+ the deep layers when withDeep: Generator::terrainBlockAt(x, y, z, false)). */
class Decider {
public:
    Decider(Generator& g, FillTile* tiles, bool withDeep, uint16_t* out)
        : g_(g), tiles_(tiles), out_(out), b_(acquire()), withDeep_(withDeep && (g.cfg.flags & SBW_FLAG_DEEP_LAYERS) != 0),
          air_(g.cfg.id[SBW_AIR]), water_(g.cfg.id[SBW_WATER]), stone_(g.cfg.id[SBW_STONE]), caveFloor_(g.caveFloor()) {
        b_.clear();
    }
    ~Decider() {
        b_.clear();
        release();
    }
    Decider(const Decider&) = delete;
    Decider& operator=(const Decider&) = delete;

    /* the cell (x, y, z) of tile slot `slot` -> out[oi] (now or at the next flush) */
    void cell(int slot, int64_t x, int64_t y, int64_t z, int32_t oi) {
        FillTile& t = tiles_[slot];
        const BaseTile& bt = *t.base;
        const int lx = static_cast<int>(x & 31), lz = static_cast<int>(z & 31);
        const int col = colIdx(lx, lz);
        if (y < g_.cfg.minY || y >= g_.cfg.maxY) {
            out_[oi] = air_;
            return;
        }
        const int32_t h = bt.h[col];
        if (y >= h && !(bt.rivers && bt.riverRoof[col] >= y)) {
            out_[oi] = (bt.water[col] != NO_WATER && y < bt.water[col]) ? water_ : air_;
            return;
        }
        const CarveTile& ct = t.carveOf(g_);
        const bool candidate = y < h && y >= caveFloor_
                               && !(bt.rivers && bt.riverRoof[col] > bt.riverFloor[col] && y >= bt.riverFloor[col] && y <= bt.riverRoof[col])
                               && !t.formation.test(lx, y, lz) && !t.cave.test(lx, y, lz);
        if (!candidate) {
            uint16_t id = g_.decideBlockM(bt, [&] { return t.formation.test(lx, y, lz); }, [&] { return t.cave.test(lx, y, lz); },
                                          [&] { return g_.densitySolid(NoDensity{}, y, h, bt.biome[col], bt.refH[col], ct.table[col], ct.guard[col], ct.seal[col]); },
                                          col, x, y, z, ct.flood[col]);
            if (id == stone_ && withDeep_ && deepCarved(t, lx, y, lz, h)) id = air_;
            out_[oi] = id;
            return;
        }
        b_.out.push_back(oi);
        b_.col.push_back((slot << 10) | col);
        b_.x.push_back(x);
        b_.y.push_back(y);
        b_.z.push_back(z);
        b_.fz.push_back(static_cast<float>(z));
        b_.fx.push_back(static_cast<float>(x));
        b_.fy.push_back(static_cast<float>(y));
        if (b_.out.size() >= CHUNK_CELLS) flush();
    }

    void flush() {
        Batch& b = b_;
        const size_t m = b.out.size();
        if (m == 0) return;
        for (auto& v : b.v) v.resize(m);
        b.have.assign(m, 0);
        /* Pre-sample a superset of what densitySolid reads, so one evaluation per cell suffices: cheese for
         * every cell, crag in stony-peaks overhang bands, the first spaghetti channel where the fade is on
         * (depth > 10), the second where the first lies inside the widest band any cell can have
         * ((0.085 + 0.070) * 1). Extra samples never change a value; a channel a cell still lacks is
         * sampled by the rounds below. */
        b.pending.resize(m);
        for (size_t i = 0; i < m; i++) b.pending[i] = static_cast<int32_t>(i);
        sampleChannel(g_, b, D_CHEESE, b.pending);
        for (auto& n : b.need) n.clear();
        for (size_t i = 0; i < m; i++) {
            const int slot = b.col[i] >> 10, col = b.col[i] & 1023;
            const BaseTile& bt = *tiles_[slot].base;
            const int64_t depth = static_cast<int64_t>(bt.h[col]) - b.y[i];
            if (depth <= D_OVERHANG_DEPTH && g_.densityNeedsCrag(bt.biome[col])) b.need[D_CRAG].push_back(static_cast<int32_t>(i));
            if (depth > D_SPAG_FADE_START) b.need[D_SPAG1].push_back(static_cast<int32_t>(i));
        }
        sampleChannel(g_, b, D_CRAG, b.need[D_CRAG]);
        sampleChannel(g_, b, D_SPAG1, b.need[D_SPAG1]);
        constexpr float WIDEST_BAND = D_SPAG_THICKNESS + D_SPAG_GALLERY_BONUS + 0.001f;
        b.need[D_SPAG2].clear();
        for (int32_t k : b.need[D_SPAG1]) {
            if (std::fabs(b.v[D_SPAG1][static_cast<size_t>(k)]) < WIDEST_BAND) b.need[D_SPAG2].push_back(k);
        }
        sampleChannel(g_, b, D_SPAG2, b.need[D_SPAG2]);
        auto falseMask = [] { return false; };
        while (!b.pending.empty()) {
            for (auto& n : b.need) n.clear();
            b.next.clear();
            for (int32_t k : b.pending) {
                const auto ku = static_cast<size_t>(k);
                const int slot = b.col[ku] >> 10, col = b.col[ku] & 1023;
                FillTile& t = tiles_[slot];
                const BaseTile& bt = *t.base;
                int missing = -1;
                auto dens = [&](int ch) -> float {
                    if (((b.have[ku] >> ch) & 1) != 0) return b.v[ch][ku];
                    if (missing < 0) missing = ch;
                    return std::numeric_limits<float>::quiet_NaN();
                };
                const bool solid = g_.densitySolid(dens, b.y[ku], bt.h[col], bt.biome[col], bt.refH[col], t.carve->table[col],
                                                   t.carve->guard[col], t.carve->seal[col]);
                if (missing >= 0) {
                    b.need[missing].push_back(k);
                    b.next.push_back(k);
                    continue;
                }
                uint16_t id = g_.decideBlockM(bt, falseMask, falseMask, [solid] { return solid; }, col, b.x[ku], b.y[ku], b.z[ku]);
                if (id == stone_ && withDeep_ && deepCarved(t, col & 31, b.y[ku], col >> 5, bt.h[col])) id = air_;
                out_[b.out[ku]] = id;
            }
            for (int ch = 0; ch < 4; ch++) sampleChannel(g_, b, ch, b.need[ch]);
            b.pending.swap(b.next);
        }
        b.clear();
    }

private:
    static constexpr size_t CHUNK_CELLS = 2048;
    /* per-thread batch buffers, one per nesting level (a tile build inside a fill may decide cells too) */
    struct Pool {
        std::vector<std::unique_ptr<Batch>> batches;
        size_t depth = 0;
    };
    static Pool& pool() {
        thread_local Pool p;
        return p;
    }
    static Batch& acquire() {
        Pool& p = pool();
        if (p.depth == p.batches.size()) p.batches.push_back(std::make_unique<Batch>());
        return *p.batches[p.depth++];
    }
    static void release() { pool().depth--; }
    bool deepCarved(FillTile& t, int lx, int64_t y, int lz, int32_t h) {
        for (int L = 0; L < SBW_DEEP_LAYERS && y < static_cast<int64_t>(h) - DEEP_SPACING * (L + 1); L++) {
            if (!t.deepLoaded[L]) {
                t.deep[L] = g_.deepLayer(L, t.cx, t.cz);
                t.deepLoaded[L] = true;
            }
            if (t.deep[L] && t.deep[L]->carve.test(lx, y, lz)) return true;
        }
        return false;
    }

    Generator& g_;
    FillTile* tiles_;
    uint16_t* out_;
    Batch& b_;
    const bool withDeep_;
    const uint16_t air_, water_, stone_;
    const int32_t caveFloor_;
};

/* The terrain block (before pools, stalagmites, ores and limestone) of every cell of the box
 * [x0, x0+nx) x [y0, y0+ny) x [z0, z0+nz) into out[((iy * nz) + iz) * nx + ix] (a 32^3 cube box is
 * CV_CELL_INDEX order). Exactly Generator::terrainBlockAt(x, y, z, false) per cell. */
void fillBox(Generator& g, int64_t x0, int64_t y0, int64_t z0, int nx, int ny, int nz, uint16_t* out) {
    const int64_t tx0 = x0 >> 5, tz0 = z0 >> 5;
    const int tnx = static_cast<int>(((x0 + nx - 1) >> 5) - tx0 + 1), tnz = static_cast<int>(((z0 + nz - 1) >> 5) - tz0 + 1);
    FillTile tiles[9];
    if (tnx * tnz > 9) throw std::runtime_error("fill box too wide");
    for (int a = 0; a < tnx; a++) {
        for (int c = 0; c < tnz; c++) {
            FillTile& t = tiles[a * tnz + c];
            t.cx = tx0 + a;
            t.cz = tz0 + c;
            t.base = g.base(t.cx, t.cz);
            if (!t.base) throw std::runtime_error("column profile unavailable");
        }
    }
    Decider d(g, tiles, true, out);
    for (int iz = 0; iz < nz; iz++) {
        const int64_t z = z0 + iz;
        for (int ix = 0; ix < nx; ix++) {
            const int64_t x = x0 + ix;
            const int slot = static_cast<int>(((x >> 5) - tx0) * tnz + ((z >> 5) - tz0));
            for (int iy = 0; iy < ny; iy++) d.cell(slot, x, y0 + iy, z, (iy * nz + iz) * nx + ix);
        }
    }
    d.flush();
}

} // namespace

/* The carved surface descent of a whole column cube (Generator::buildCarved), batched: every column
 * steps down from its raw top while the generated block (decideBlock, no deep layers) is air or water. */
void carvedDescent(Generator& g, const std::shared_ptr<const BaseTile>& base, const std::shared_ptr<const CarveTile>& carve,
                   int64_t cx, int64_t cz, CarvedTile& out) {
    FillTile tile;
    tile.cx = cx;
    tile.cz = cz;
    tile.base = base;
    tile.carve = carve;
    tile.cave.m = &carve->cave;
    tile.formation.m = &carve->formation;
    const BaseTile& b = *base;
    const uint16_t air = g.cfg.id[SBW_AIR], water = g.cfg.id[SBW_WATER];
    int64_t y[COLS];
    uint16_t block[COLS];
    std::vector<int32_t> pending, next;
    for (int col = 0; col < COLS; col++) {
        y[col] = static_cast<int64_t>(b.h[col]) - 1;
        block[col] = air;
        if (y[col] > g.cfg.minY) {
            pending.push_back(col);
        }
    }
    const int64_t X0 = cx * CE, Z0 = cz * CE;
    while (!pending.empty()) {
        {
            Decider d(g, &tile, false, block);
            for (int32_t col : pending) d.cell(0, X0 + (col & 31), y[col], Z0 + (col >> 5), col);
            d.flush();
        }
        next.clear();
        for (int32_t col : pending) {
            if (block[col] != air && block[col] != water) continue;
            y[col]--;
            if (y[col] > g.cfg.minY) next.push_back(col);
        }
        pending.swap(next);
    }
    for (int col = 0; col < COLS; col++) {
        uint16_t bl = block[col];
        if (y[col] <= g.cfg.minY) bl = g.cfg.id[SBW_BEDROCK];
        out.carvedH[col] = static_cast<int32_t>(y[col] + 1);
        out.carved[col] = out.carvedH[col] < b.h[col] ? 1 : 0;
        out.top[col] = bl;
        out.sea[col] = carve->flood[col] != FLOOD_NONE && out.carvedH[col] < g.cfg.sea ? 1 : 0;
    }
}

namespace {

/* ------------------------------------------------------------------------- classification */
constexpr uint8_t ROCK = 0, STONE = 1, AIR = 2;

/* Padded raw classification around the cube: in-cube cells from the cube's own ids, the shell from
 * the pure terrain (raw: before the neighbour's stalagmites/ores/limestone). */
struct Pad {
    int64_t x0 = 0, y0 = 0, z0 = 0;
    int nx = 0, ny = 0, nz = 0;
    std::vector<uint8_t> cls;
    [[nodiscard]] bool inside(int64_t x, int64_t y, int64_t z) const {
        return x >= x0 && x < x0 + nx && y >= y0 && y < y0 + ny && z >= z0 && z < z0 + nz;
    }
    [[nodiscard]] uint8_t at(int64_t x, int64_t y, int64_t z) const {
        return cls[static_cast<size_t>(((y - y0) * nz + (z - z0)) * nx + (x - x0))];
    }
};

/* ------------------------------------------------------------------------------- ores */
constexpr int IRON_DEEP_PEAK_Y = 12, IRON_TROUGH_Y = 64, IRON_HIGH_PEAK_Y = 130;
constexpr float IRON_TROUGH_WEIGHT = 0.15f;
constexpr int COAL_VEINS = 30, IRON_CANDIDATES = 28;
constexpr int DEEP_STREAM_FACTOR = 3; /* deep band ~ three times the old column's stone */
constexpr int COAL_SIZE_MIN = 8, COAL_SIZE_MAX = 24, IRON_SIZE_MIN = 4, IRON_SIZE_MAX = 14;
constexpr uint64_t COAL_SALT = UINT64_C(0x517CC1B727220A95);
constexpr uint64_t IRON_SALT = UINT64_C(0x2545F4914F6CDD1D);
constexpr uint64_t DEEP_ORE_SALT = UINT64_C(0xD5EE90E5A17C0A11);
constexpr float JIT_MIN = 0.80f, JIT_MAX = 1.20f, STEP_JITTER = 0.9f, AXIS_Y_DAMP = 0.6f;
constexpr int SUBSURFACE_DEPTH = 4;
constexpr int CRYSTAL_MIN_Y = 20;
constexpr float CRYSTAL_CHANCE = 0.02f;

float veinSpan(int size) { return static_cast<float>(size) * 0.22f; }
float veinRadius(int size) { return static_cast<float>(std::cbrt(static_cast<double>(size) * 0.085)); }

float ironDepthWeight(int64_t y) {
    if (y <= IRON_DEEP_PEAK_Y || y >= IRON_HIGH_PEAK_Y) return 1.0f;
    if (y < IRON_TROUGH_Y) {
        const float t = static_cast<float>(y - IRON_DEEP_PEAK_Y) / static_cast<float>(IRON_TROUGH_Y - IRON_DEEP_PEAK_Y);
        return 1.0f + t * (IRON_TROUGH_WEIGHT - 1.0f);
    }
    const float t = static_cast<float>(y - IRON_TROUGH_Y) / static_cast<float>(IRON_HIGH_PEAK_Y - IRON_TROUGH_Y);
    return IRON_TROUGH_WEIGHT + t * (1.0f - IRON_TROUGH_WEIGHT);
}

struct CubeCtx {
    Generator& g;
    int64_t cx, cy, cz, X0, Y0, Z0;
    uint16_t* ids;
    std::shared_ptr<const BaseTile> base;
    uint16_t* stateLayer = nullptr;
    bool contains(int64_t x, int64_t y, int64_t z) const {
        return x >= X0 && x < X0 + CE && y >= Y0 && y < Y0 + CE && z >= Z0 && z < Z0 + CE;
    }
    uint16_t& at(int64_t x, int64_t y, int64_t z) {
        return ids[CV_CELL_INDEX(static_cast<int>(x - X0), static_cast<int>(y - Y0), static_cast<int>(z - Z0))];
    }
};

int64_t veinRngSeed(int64_t seed, int64_t cx, int64_t cz, uint64_t salt) {
    return static_cast<int64_t>(((static_cast<uint64_t>(seed) * UINT64_C(6364136223846793005))
                                 ^ (static_cast<uint64_t>(cx) * UINT64_C(0x9E3779B97F4A7C15)))
                                ^ (static_cast<uint64_t>(cz) * UINT64_C(0xC2B2AE3D27D4EB4F)) ^ salt);
}

void fillSphere(CubeCtx& c, uint16_t ore, float wx, float wy, float wz, float radius) {
    const int r = static_cast<int>(std::ceil(radius));
    const int64_t px = stonebreak::javaRoundFloat(wx), py = stonebreak::javaRoundFloat(wy),
                  pz = stonebreak::javaRoundFloat(wz);
    if (px + r < c.X0 || px - r >= c.X0 + CE) return;
    if (pz + r < c.Z0 || pz - r >= c.Z0 + CE) return;
    if (py + r < c.Y0 || py - r >= c.Y0 + CE) return;
    const float r2 = radius * radius;
    const uint16_t stone = c.g.cfg.id[SBW_STONE];
    for (int dx = -r; dx <= r; dx++) {
        for (int dz = -r; dz <= r; dz++) {
            for (int dy = -r; dy <= r; dy++) {
                const int64_t x = px + dx, y = py + dy, z = pz + dz;
                if (!c.contains(x, y, z)) continue;
                if (y <= c.g.cfg.minY) continue;
                if (static_cast<float>(dx * dx + dy * dy + dz * dz) > r2) continue;
                uint16_t& v = c.at(x, y, z);
                if (v == stone) v = ore;
            }
        }
    }
}

/* The spheres of one vein: placeVein's walk, recorded (fillSphere arguments). */
void veinSpheres(int64_t ox, int64_t oy, int64_t oz, int size, JavaRandom& rng, bool keep, std::vector<OreSphere>& out) {
    const double pi = 3.141592653589793;
    const double theta = rng.nextDouble() * pi * 2;
    const double pitch = (rng.nextDouble() - 0.5) * pi;
    const auto axisX = static_cast<float>(std::cos(pitch) * std::cos(theta));
    const float axisY = static_cast<float>(std::sin(pitch)) * AXIS_Y_DAMP;
    const auto axisZ = static_cast<float>(std::cos(pitch) * std::sin(theta));
    const float span = veinSpan(size);
    const float radius = veinRadius(size);
    const int steps = 2 + size / 8;
    for (int s = 0; s <= steps; s++) {
        const float t = static_cast<float>(s) / static_cast<float>(steps) - 0.5f;
        const float jx = (rng.nextFloat() - 0.5f) * 2.0f * STEP_JITTER;
        const float jy = (rng.nextFloat() - 0.5f) * 2.0f * STEP_JITTER * AXIS_Y_DAMP;
        const float jz = (rng.nextFloat() - 0.5f) * 2.0f * STEP_JITTER;
        const float r = radius * (JIT_MIN + rng.nextFloat() * (JIT_MAX - JIT_MIN));
        if (keep) {
            out.push_back({static_cast<float>(ox) + axisX * span * t + jx, static_cast<float>(oy) + axisY * span * t + jy,
                           static_cast<float>(oz) + axisZ * span * t + jz, r});
        }
    }
}

const int MAX_VEIN_REACH = static_cast<int>(std::ceil(veinSpan(COAL_SIZE_MAX) * 0.5f
                                                      + veinRadius(COAL_SIZE_MAX) * JIT_MAX + STEP_JITTER));

} // namespace

/* Every kept ore sphere one 16-block source chunk spawns, per ore and stream (the top stream from y 1 to
 * the column's stone top, the deep stream in [min_y + 6, 1)): the veins' walks depend only on (seed,
 * source chunk) and the height oracle, so every cube near the source replays the same spheres. */
std::shared_ptr<OreSource> buildOreSource(Generator& g, int64_t scx, int64_t scz) {
    auto src = std::make_shared<OreSource>();
    const int64_t bx = scx * CHUNK, bz = scz * CHUNK;
    const int64_t deepLo = g.cfg.minY + 6;
    for (int iron = 0; iron < 2; iron++) {
        const int sizeMin = iron ? IRON_SIZE_MIN : COAL_SIZE_MIN;
        const int sizeMax = iron ? IRON_SIZE_MAX : COAL_SIZE_MAX;
        {
            std::vector<OreSphere>& out = src->spheres[iron][0];
            JavaRandom rng(veinRngSeed(g.cfg.seed, scx, scz, iron ? IRON_SALT : COAL_SALT));
            const int candidates = iron ? IRON_CANDIDATES : COAL_VEINS;
            for (int i = 0; i < candidates; i++) {
                const int64_t ox = bx + rng.nextInt(CHUNK);
                const int64_t oz = bz + rng.nextInt(CHUNK);
                const int64_t stoneTop = static_cast<int64_t>(heightOracle(g, ox, oz)) - SUBSURFACE_DEPTH;
                const int64_t oy = stoneTop > 1 ? 1 + rng.nextInt(static_cast<int32_t>(stoneTop - 1)) : 1;
                const int size = sizeMin + rng.nextInt(sizeMax - sizeMin + 1);
                const float keepRoll = rng.nextFloat();
                const bool keep = stoneTop > 1 && (!iron || keepRoll < ironDepthWeight(oy));
                veinSpheres(ox, oy, oz, size, rng, keep, out);
            }
        }
        if (deepLo < 1) {
            std::vector<OreSphere>& out = src->spheres[iron][1];
            JavaRandom rng(veinRngSeed(g.cfg.seed, scx, scz, (iron ? IRON_SALT : COAL_SALT) ^ DEEP_ORE_SALT));
            const int candidates = (iron ? IRON_CANDIDATES : COAL_VEINS) * DEEP_STREAM_FACTOR;
            const auto span = static_cast<int32_t>(1 - deepLo);
            for (int i = 0; i < candidates; i++) {
                const int64_t ox = bx + rng.nextInt(CHUNK);
                const int64_t oz = bz + rng.nextInt(CHUNK);
                const int64_t oy = deepLo + rng.nextInt(span);
                const int size = sizeMin + rng.nextInt(sizeMax - sizeMin + 1);
                const float keepRoll = rng.nextFloat();
                const bool keep = !iron || keepRoll < ironDepthWeight(oy);
                veinSpheres(ox, oy, oz, size, rng, keep, out);
            }
        }
        for (auto& v : src->spheres[iron]) v.shrink_to_fit();
    }
    return src;
}

namespace {

/* coal before iron, stone only; each ore's spheres in source order (a sphere only turns stone into its
 * ore, so the result is the union of the ore's spheres over the stone the earlier ore left) */
void generateOres(CubeCtx& c, bool iron) {
    Generator& g = c.g;
    const uint16_t ore = g.cfg.id[iron ? SBW_IRON_ORE : SBW_COAL_ORE];
    const int64_t c0 = floorDiv64(c.X0 - MAX_VEIN_REACH, CHUNK), c1 = floorDiv64(c.X0 + CE + MAX_VEIN_REACH, CHUNK);
    const int64_t d0 = floorDiv64(c.Z0 - MAX_VEIN_REACH, CHUNK), d1 = floorDiv64(c.Z0 + CE + MAX_VEIN_REACH, CHUNK);
    const int64_t deepLo = g.cfg.minY + 6;
    /* the deep stream only matters for cubes reaching below y 1 + reach */
    const bool deepRelevant = c.Y0 < 1 + MAX_VEIN_REACH && deepLo < 1;
    const bool topRelevant = c.Y0 + CE + MAX_VEIN_REACH > 1;
    for (int64_t scx = c0; scx <= c1; scx++) {
        for (int64_t scz = d0; scz <= d1; scz++) {
            auto src = g.oreSource(scx, scz);
            if (!src) throw std::runtime_error("column profile unavailable");
            for (int stream = 0; stream < 2; stream++) {
                if (stream == 0 ? !topRelevant : !deepRelevant) continue;
                for (const OreSphere& sp : src->spheres[iron ? 1 : 0][stream]) fillSphere(c, ore, sp.x, sp.y, sp.z, sp.r);
            }
        }
    }
}

void generateCrystals(CubeCtx& c) {
    Generator& g = c.g;
    const BaseTile& b = *c.base;
    static const int32_t CRYSTAL_HASH = stonebreak::javaStringHash("crystal");
    const uint16_t redSand = g.cfg.id[SBW_RED_SAND], stone = g.cfg.id[SBW_STONE], magma = g.cfg.id[SBW_MAGMA];
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int col = colIdx(lx, lz);
            if (b.biome[col] != SBW_RED_SAND_DESERT) continue;
            const int64_t maxY = static_cast<int64_t>(b.h[col]) - 1 - 4;
            const int64_t lo = std::max<int64_t>(CRYSTAL_MIN_Y + 1, c.Y0);
            const int64_t hi = std::min<int64_t>(maxY, c.Y0 + CE);
            for (int64_t y = lo; y < hi; y++) {
                uint16_t& v = c.at(c.X0 + lx, y, c.Z0 + lz);
                if (v != redSand && v != stone && v != magma) continue;
                if (g.drand.shouldGenerate3(c.X0 + lx, y, c.Z0 + lz, CRYSTAL_HASH, CRYSTAL_CHANCE)) {
                    v = g.cfg.id[SBW_CRYSTAL];
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- limestone */
constexpr float CAVERN_ZONE_RADIUS = 24.0f, CAVERN_ZONE_HALF_HEIGHT = 16.0f;
constexpr float MEGA_ZONE_RADIUS = 48.0f, MEGA_ZONE_HALF_HEIGHT = 36.0f;
constexpr int STALAGMITE_SIZE1 = 45, STALAGMITE_SIZE2 = 35, STALAGMITE_MAX = 3;
constexpr int CAVERN_COAT_DEPTH = 2;
constexpr float CAVERN_COAT_THRESHOLD = -0.05f, MEGA_COAT_THRESHOLD = -0.20f;
constexpr float KARST_REGION_FREQ = 0.004f, KARST_REGION_THRESHOLD = 0.20f, KARST_COAT_THRESHOLD = 0.10f;
constexpr float PATCH_FREQ_XZ = 0.07f, PATCH_FREQ_Y = 0.10f;
constexpr float STRATA_REGION_FREQ = 0.003f, STRATA_REGION_THRESHOLD = 0.25f, STRATA_FADE = 0.25f;
constexpr float STRATA_WARP_FREQ = 0.012f, STRATA_WARP_AMPLITUDE = 5.0f;
constexpr float STRATA_PERIOD = 12.0f, STRATA_MAX_FILL = 0.40f;
constexpr int STRATA_MIN_Y_ABOVE_FLOOR = 6, STRATA_SURFACE_CLEARANCE = 8;
constexpr int POCKET_CANDIDATES = 3;
constexpr float POCKET_KEEP = 0.45f, POCKET_R_MIN = 2.0f, POCKET_R_MAX = 4.5f;
constexpr int POCKET_BLOBS_MIN = 2, POCKET_BLOBS_MAX = 5;
constexpr float POCKET_BLOB_OFFSET = 3.0f;
constexpr uint64_t POCKET_SALT = UINT64_C(0x11E57011E5701);
/* Stalagmite runs are only resolved when they are at most this tall (both cubes a run touches must
 * see all of it, so the decision is identical on both sides of a cube border). */
constexpr int RUN_MAX = 12;
constexpr int RUN_WINDOW = 16;

struct CavernZone {
    float x, y, z;
    int kind;
};

void cavernZones(Generator& g, int64_t X0, int64_t Z0, std::vector<CavernZone>& zones) {
    for (int kind = 1; kind <= 2; kind++) {
        const float radius = kind == 1 ? CAVERN_ZONE_RADIUS : MEGA_ZONE_RADIUS;
        const auto minX = static_cast<float>(X0) - radius, maxX = static_cast<float>(X0 + CE) + radius;
        const auto minZ = static_cast<float>(Z0) - radius, maxZ = static_cast<float>(Z0 + CE) + radius;
        const int64_t c0 = floorDiv64(X0 - static_cast<int64_t>(radius) - CHUNK, CHUNK);
        const int64_t c1 = floorDiv64(X0 + CE + static_cast<int64_t>(radius) + CHUNK, CHUNK);
        const int64_t d0 = floorDiv64(Z0 - static_cast<int64_t>(radius) - CHUNK, CHUNK);
        const int64_t d1 = floorDiv64(Z0 + CE + static_cast<int64_t>(radius) + CHUNK, CHUNK);
        for (int64_t scx = c0; scx <= c1; scx++) {
            for (int64_t scz = d0; scz <= d1; scz++) {
                float o[3];
                if (!cavernOrigin(g, g.cfg.seed, kind == 2, scx, scz, o)) continue;
                if (o[0] < minX || o[0] > maxX || o[2] < minZ || o[2] > maxZ) continue;
                zones.push_back({o[0], o[1], o[2], kind});
            }
        }
    }
}

int zoneAt(const std::vector<CavernZone>& zones, int64_t x, int64_t y, int64_t z) {
    int best = 0;
    for (const CavernZone& zn : zones) {
        const bool mega = zn.kind == 2;
        const float r = mega ? MEGA_ZONE_RADIUS : CAVERN_ZONE_RADIUS;
        const float h = mega ? MEGA_ZONE_HALF_HEIGHT : CAVERN_ZONE_HALF_HEIGHT;
        const float dx = (static_cast<float>(x) - zn.x) / r;
        const float dy = (static_cast<float>(y) - zn.y) / h;
        const float dz = (static_cast<float>(z) - zn.z) / r;
        if (dx * dx + dy * dy + dz * dz <= 1.0f) {
            if (mega) return 2;
            best = 1;
        }
    }
    return best;
}

uint64_t formationHash(int64_t seed, int64_t wx, int64_t y, int64_t wz) {
    uint64_t h = static_cast<uint64_t>(seed) ^ UINT64_C(0x57A1A6317E5);
    h ^= static_cast<uint64_t>(wx) * UINT64_C(0x9E3779B97F4A7C15);
    h = ((h << 21) | (h >> 43)) ^ (static_cast<uint64_t>(y) * UINT64_C(0xC2B2AE3D27D4EB4F));
    h = ((h << 17) | (h >> 47)) ^ (static_cast<uint64_t>(wz) * UINT64_C(0x165667B19E3779F9));
    h ^= h >> 29;
    return h * UINT64_C(0xBF58476D1CE4E5B9);
}

int rollStalagmiteSize(uint64_t hash) {
    const auto roll = static_cast<int>((hash >> 1) % 100);
    return roll < STALAGMITE_SIZE1 ? 1 : roll < STALAGMITE_SIZE1 + STALAGMITE_SIZE2 ? 2 : 3;
}

uint8_t classify(const Generator& g, uint16_t id) {
    if (id == g.cfg.id[SBW_STONE]) return STONE;
    if (id == g.cfg.id[SBW_AIR]) return AIR;
    return ROCK;
}

void fillPad(Generator& g, CubeCtx& c, Pad& pad, int margin, int yBelow, int yAbove) {
    pad.x0 = c.X0 - margin;
    pad.z0 = c.Z0 - margin;
    pad.y0 = c.Y0 - yBelow;
    pad.nx = CE + 2 * margin;
    pad.nz = CE + 2 * margin;
    pad.ny = CE + yBelow + yAbove;
    pad.cls.assign(static_cast<size_t>(pad.nx * pad.ny * pad.nz), ROCK);
    /* the cube's own cells from its ids, the shell from the pure terrain of the 26 neighbour cubes: a
     * neighbour's raw terrain when it is cached (cubes generated nearby keep theirs a while), built and
     * cached when the overlap is a whole half cube above or below (that cube is generated next to this
     * one anyway, and then finds it), else just the overlap. Out of the world: never air
     * (LimestoneGenerator.isAir). */
    thread_local std::vector<uint16_t> tlsBuf;
    std::vector<uint16_t>& buf = tlsBuf;
    const int64_t px1 = pad.x0 + pad.nx, py1 = pad.y0 + pad.ny, pz1 = pad.z0 + pad.nz;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                const int64_t ncx = c.cx + dx, ncy = c.cy + dy, ncz = c.cz + dz;
                const int64_t bx0 = std::max(pad.x0, ncx * CE), bx1 = std::min(px1, ncx * CE + CE);
                const int64_t by0 = std::max(pad.y0, ncy * CE), by1 = std::min(py1, ncy * CE + CE);
                const int64_t bz0 = std::max(pad.z0, ncz * CE), bz1 = std::min(pz1, ncz * CE + CE);
                if (bx0 >= bx1 || by0 >= by1 || bz0 >= bz1) continue;
                if (by1 <= g.cfg.minY || by0 >= g.cfg.maxY) continue; /* ROCK already */
                const int sx = static_cast<int>(bx1 - bx0), sy = static_cast<int>(by1 - by0), sz = static_cast<int>(bz1 - bz0);
                std::shared_ptr<const RawCube> raw = g.rawCache.peek3(ncx, ncy, ncz);
                if (!raw && dx == 0 && dz == 0 && sx * sy * sz >= CELLS / 4) raw = g.rawCube(ncx, ncy, ncz);
                auto put = [&](int64_t x, int64_t y, int64_t z, uint16_t id) {
                    const auto k = static_cast<size_t>(((y - pad.y0) * pad.nz + (z - pad.z0)) * pad.nx + (x - pad.x0));
                    pad.cls[k] = (y < g.cfg.minY || y >= g.cfg.maxY) ? ROCK : classify(g, id);
                };
                if (raw) {
                    for (int64_t y = by0; y < by1; y++)
                        for (int64_t z = bz0; z < bz1; z++)
                            for (int64_t x = bx0; x < bx1; x++) put(x, y, z, raw->at(x, y, z));
                    continue;
                }
                buf.resize(static_cast<size_t>(sx * sy * sz));
                fillBox(g, bx0, by0, bz0, sx, sy, sz, buf.data());
                for (int iy = 0; iy < sy; iy++)
                    for (int iz = 0; iz < sz; iz++)
                        for (int ix = 0; ix < sx; ix++)
                            put(bx0 + ix, by0 + iy, bz0 + iz, buf[static_cast<size_t>((iy * sz + iz) * sx + ix)]);
            }
        }
    }
    for (int ly = 0; ly < CE; ly++)
        for (int lz = 0; lz < CE; lz++)
            for (int lx = 0; lx < CE; lx++)
                pad.cls[static_cast<size_t>(((ly + yBelow) * pad.nz + lz + margin) * pad.nx + lx + margin)] =
                    classify(g, c.ids[CV_CELL_INDEX(lx, ly, lz)]);
}

/* LimestoneGenerator.placeStalagmites over raw terrain (runs of one-wide stone pillars in cavern zones). */
void placeStalagmites(CubeCtx& c, const Pad& pad, const std::vector<CavernZone>& zones) {
    Generator& g = c.g;
    const BaseTile& b = *c.base;
    auto airAt = [&](int64_t x, int64_t y, int64_t z) { return pad.inside(x, y, z) && pad.at(x, y, z) == AIR; };
    auto stoneAt = [&](int64_t x, int64_t y, int64_t z) { return pad.inside(x, y, z) && pad.at(x, y, z) == STONE; };
    auto isFormation = [&](int64_t x, int64_t y, int64_t z) {
        return airAt(x + 1, y, z) && airAt(x - 1, y, z) && airAt(x, y, z + 1) && airAt(x, y, z - 1);
    };
    const uint16_t stalagmite = g.cfg.id[SBW_LIMESTONE_STALAGMITE];
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int col = colIdx(lx, lz);
            const int64_t x = c.X0 + lx, z = c.Z0 + lz;
            const int64_t stoneTop = std::min<int64_t>(static_cast<int64_t>(b.h[col]) - SUBSURFACE_DEPTH, g.cfg.maxY - 1);
            const int64_t lo = std::max<int64_t>(g.cfg.minY + 1, pad.y0 + 1);
            const int64_t hi = std::min<int64_t>(stoneTop, pad.y0 + pad.ny - 1);
            int64_t y = lo;
            while (y < hi) {
                if (!stoneAt(x, y, z) || !isFormation(x, y, z)) {
                    y++;
                    continue;
                }
                /* the maximal pillar (stone with air on all four sides) through y */
                const int64_t seqStart = y;
                while (y < hi && stoneAt(x, y, z) && isFormation(x, y, z)) y++;
                const int64_t seqEnd = y - 1;
                /* only pillars seen whole (and short) are resolved, so every cube that sees one
                 * decides it identically */
                if (seqStart <= pad.y0 + 1 || y >= pad.y0 + pad.ny - 1 || seqEnd - seqStart + 1 > RUN_MAX) continue;
                /* LimestoneGenerator: the run starts at the first cell inside a cavern zone */
                int64_t runStart = INT64_MIN;
                for (int64_t k = seqStart; k <= seqEnd; k++) {
                    if (zoneAt(zones, x, k, z) != 0) { runStart = k; break; }
                }
                if (runStart == INT64_MIN) continue;
                const int64_t runEnd = seqEnd;
                if (runEnd + STALAGMITE_MAX < c.Y0 || runStart - STALAGMITE_MAX >= c.Y0 + CE) continue;
                const bool floorBelow = !airAt(x, runStart - 1, z);
                const bool airAbove = airAt(x, runEnd + 1, z);
                int direction;
                if (floorBelow && airAbove) {
                    direction = 1;        /* stands on the floor */
                } else if (!airAbove && !floorBelow) {
                    direction = -1;       /* hangs from the ceiling */
                } else {
                    continue;             /* bridges floor to ceiling, or floats */
                }
                const int64_t anchorY = direction > 0 ? runStart : runEnd;
                int room = 0;
                while (room < STALAGMITE_MAX) {
                    const int64_t cy = anchorY + room * direction;
                    const bool inRun = cy >= runStart && cy <= runEnd;
                    if (cy < g.cfg.minY + 1 || cy >= stoneTop || !(inRun || airAt(x, cy, z))) break;
                    room++;
                }
                const uint64_t roll = formationHash(g.cfg.seed, x, anchorY, z);
                const int size = std::min(room, rollStalagmiteSize(roll));
                const int facing = static_cast<int>((roll >> 40) & 3);
                const int hanging = direction < 0 ? 1 : 0;
                for (int64_t cy = runStart; cy <= runEnd; cy++) {
                    if (c.contains(x, cy, z)) {
                        c.at(x, cy, z) = g.cfg.id[SBW_AIR];
                        if (c.stateLayer) c.stateLayer[CV_CELL_INDEX(lx, static_cast<int>(cy - c.Y0), lz)] = 0;
                    }
                }
                for (int i = 0; i < size; i++) {
                    const int64_t cy = anchorY + static_cast<int64_t>(i) * direction;
                    if (!c.contains(x, cy, z)) continue;
                    c.at(x, cy, z) = stalagmite;
                    if (c.stateLayer) {
                        c.stateLayer[CV_CELL_INDEX(lx, static_cast<int>(cy - c.Y0), lz)] = i == 0
                            ? g.cfg.stalagmiteState[((size - 1) * 2 + hanging) * 4 + facing]
                            : g.cfg.stalagmitePart[hanging];
                    }
                }
            }
        }
    }
}

int64_t pocketRngSeed(int64_t seed, int64_t cx, int64_t cz, uint64_t salt) {
    return veinRngSeed(seed, cx, cz, salt);
}

void fillBlob(CubeCtx& c, float wx, float wy, float wz, float radius) {
    const int r = static_cast<int>(std::ceil(radius));
    const int64_t px = stonebreak::javaRoundFloat(wx), py = stonebreak::javaRoundFloat(wy),
                  pz = stonebreak::javaRoundFloat(wz);
    if (px + r < c.X0 || px - r >= c.X0 + CE) return;
    if (pz + r < c.Z0 || pz - r >= c.Z0 + CE) return;
    if (py + r < c.Y0 || py - r >= c.Y0 + CE) return;
    const float r2 = radius * radius;
    const uint16_t stone = c.g.cfg.id[SBW_STONE], lime = c.g.cfg.id[SBW_LIMESTONE];
    for (int dx = -r; dx <= r; dx++) {
        for (int dz = -r; dz <= r; dz++) {
            for (int dy = -r; dy <= r; dy++) {
                const int64_t x = px + dx, y = py + dy, z = pz + dz;
                if (!c.contains(x, y, z) || y <= c.g.cfg.minY) continue;
                const float sy = static_cast<float>(dy) * 1.6f;
                if (static_cast<float>(dx * dx + dz * dz) + sy * sy > r2) continue;
                uint16_t& v = c.at(x, y, z);
                if (v == stone) v = lime;
            }
        }
    }
}

void generatePockets(CubeCtx& c) {
    Generator& g = c.g;
    const int scan = static_cast<int>(std::ceil((POCKET_R_MAX + POCKET_BLOB_OFFSET + 1) / CHUNK));
    const int64_t c0 = floorDiv64(c.X0, CHUNK) - scan, c1 = floorDiv64(c.X0 + CE - 1, CHUNK) + scan;
    const int64_t d0 = floorDiv64(c.Z0, CHUNK) - scan, d1 = floorDiv64(c.Z0 + CE - 1, CHUNK) + scan;
    const int64_t deepLo = g.cfg.minY + 6;
    const int reach = static_cast<int>(POCKET_R_MAX + POCKET_BLOB_OFFSET) + 2;
    const bool deepRelevant = c.Y0 < 1 + reach && deepLo < 1;
    const bool topRelevant = c.Y0 + CE + reach > 1;
    for (int64_t scx = c0; scx <= c1; scx++) {
        for (int64_t scz = d0; scz <= d1; scz++) {
            for (int stream = 0; stream < 2; stream++) {
                if (stream == 0 && !topRelevant) continue;
                if (stream == 1 && !deepRelevant) continue;
                JavaRandom rng(pocketRngSeed(g.cfg.seed, scx, scz, stream == 0 ? POCKET_SALT : POCKET_SALT ^ DEEP_ORE_SALT));
                const int candidates = stream == 0 ? POCKET_CANDIDATES : POCKET_CANDIDATES * DEEP_STREAM_FACTOR;
                for (int i = 0; i < candidates; i++) {
                    const int64_t ox = scx * CHUNK + rng.nextInt(CHUNK);
                    const int64_t oz = scz * CHUNK + rng.nextInt(CHUNK);
                    int64_t oy;
                    bool keep;
                    if (stream == 0) {
                        const int64_t stoneTop = static_cast<int64_t>(heightOracle(g, ox, oz)) - SUBSURFACE_DEPTH;
                        oy = stoneTop > 2 ? 1 + rng.nextInt(static_cast<int32_t>(stoneTop - 1)) : 1;
                        keep = rng.nextFloat() < POCKET_KEEP && stoneTop > 2;
                    } else {
                        oy = deepLo + rng.nextInt(static_cast<int32_t>(1 - deepLo));
                        keep = rng.nextFloat() < POCKET_KEEP;
                    }
                    const int blobs = POCKET_BLOBS_MIN + rng.nextInt(POCKET_BLOBS_MAX - POCKET_BLOBS_MIN + 1);
                    for (int bI = 0; bI < blobs; bI++) {
                        const float bx = static_cast<float>(ox) + (rng.nextFloat() - 0.5f) * 2.0f * POCKET_BLOB_OFFSET;
                        const float by = static_cast<float>(oy) + (rng.nextFloat() - 0.5f) * POCKET_BLOB_OFFSET;
                        const float bz = static_cast<float>(oz) + (rng.nextFloat() - 0.5f) * 2.0f * POCKET_BLOB_OFFSET;
                        const float r = POCKET_R_MIN + rng.nextFloat() * (POCKET_R_MAX - POCKET_R_MIN);
                        if (keep) fillBlob(c, bx, by, bz, r);
                    }
                }
            }
        }
    }
}

/* zones touching this cube's y range */
bool zonesNear(const std::vector<CavernZone>& zones, int64_t Y0) {
    for (const CavernZone& z : zones) {
        const float hh = z.kind == 2 ? MEGA_ZONE_HALF_HEIGHT : CAVERN_ZONE_HALF_HEIGHT;
        if (z.y + hh >= static_cast<float>(Y0 - RUN_WINDOW) && z.y - hh <= static_cast<float>(Y0 + CE + RUN_WINDOW)) {
            return true;
        }
    }
    return false;
}

/* LimestoneGenerator coat + strata + pockets. `pad` is the raw shell (null: build one if needed);
 * its in-cube cells are refreshed from the cube (post stalagmites and ores). */
void generateLimestone(CubeCtx& c, const std::vector<CavernZone>& zones, bool zonesHere, Pad* existing) {
    Generator& g = c.g;
    const BaseTile& b = *c.base;
    bool anyStoneBand = false;
    for (int col = 0; col < COLS && !anyStoneBand; col++) {
        anyStoneBand = static_cast<int64_t>(b.h[col]) - SUBSURFACE_DEPTH > c.Y0;
    }
    if (!anyStoneBand) return;

    auto lc = g.limeColumns(c.cx, c.cz);
    if (!lc) throw std::runtime_error("limestone columns unavailable");
    const float* karst = lc->karst;
    const float* bedFill = lc->bedFill;
    const float* warp = lc->warp;
    const bool anyKarst = lc->anyKarst;

    Pad local;
    Pad* padp = existing;
    const bool needPad = zonesHere || anyKarst;
    if (needPad) {
        if (padp == nullptr) {
            /* no stalagmite pad = no cavern zone near this cube: only karst coating reads the shell, and it
             * looks one cell out (exposure depth 1), so a one-cell shell answers every read */
            const int shellDepth = zonesHere ? CAVERN_COAT_DEPTH : 1;
            fillPad(g, c, local, shellDepth, shellDepth, shellDepth);
            padp = &local;
        } else {
            for (int64_t y = c.Y0; y < c.Y0 + CE; y++)
                for (int64_t z = c.Z0; z < c.Z0 + CE; z++)
                    for (int64_t x = c.X0; x < c.X0 + CE; x++)
                        padp->cls[static_cast<size_t>(((y - padp->y0) * padp->nz + (z - padp->z0)) * padp->nx + (x - padp->x0))] =
                            classify(g, c.at(x, y, z));
        }
    }
    const Pad& pad = padp ? *padp : local;
    auto isAir = [&](int64_t x, int64_t y, int64_t z) { return pad.inside(x, y, z) && pad.at(x, y, z) == AIR; };
    auto exposure = [&](int64_t x, int64_t y, int64_t z, int maxDepth) {
        for (int d = 1; d <= maxDepth; d++) {
            if (isAir(x + d, y, z) || isAir(x - d, y, z) || isAir(x, y + d, z) || isAir(x, y - d, z)
                    || isAir(x, y, z + d) || isAir(x, y, z - d)) {
                return d;
            }
        }
        return 0;
    };
    auto isFormation = [&](int64_t x, int64_t y, int64_t z) {
        return isAir(x + 1, y, z) && isAir(x - 1, y, z) && isAir(x, y, z + 1) && isAir(x, y, z - 1);
    };

    const uint16_t stone = g.cfg.id[SBW_STONE], lime = g.cfg.id[SBW_LIMESTONE];
    const int64_t strataMin = g.cfg.minY + STRATA_MIN_Y_ABOVE_FLOOR;
    std::vector<uint16_t> snapshot(c.ids, c.ids + CELLS); /* classification = before this pass */
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int col = colIdx(lx, lz);
            const int64_t x = c.X0 + lx, z = c.Z0 + lz;
            const int64_t stoneTop = std::min<int64_t>(static_cast<int64_t>(b.h[col]) - SUBSURFACE_DEPTH, g.cfg.maxY);
            const int64_t lo = std::max<int64_t>(c.Y0, g.cfg.minY + 1);
            const int64_t hi = std::min<int64_t>(stoneTop, c.Y0 + CE);
            for (int64_t y = lo; y < hi; y++) {
                const int ci = CV_CELL_INDEX(lx, static_cast<int>(y - c.Y0), lz);
                if (snapshot[static_cast<size_t>(ci)] != stone) continue;
                bool lim = false;
                const bool karstHere = karst[col] > KARST_REGION_THRESHOLD;
                const int zone = zonesHere ? zoneAt(zones, x, y, z) : 0;
                if (needPad && (zone != 0 || karstHere)) {
                    const int exp = exposure(x, y, z, zone != 0 ? CAVERN_COAT_DEPTH : 1);
                    if (exp > 0) {
                        if (zone != 0 && isFormation(x, y, z)) {
                            lim = true;
                        } else {
                            const float patch = g.limePatch.noise3D(static_cast<float>(x) * PATCH_FREQ_XZ,
                                                                    static_cast<float>(y) * PATCH_FREQ_Y,
                                                                    static_cast<float>(z) * PATCH_FREQ_XZ);
                            if (zone != 0) {
                                const float threshold = zone == 2 ? MEGA_COAT_THRESHOLD : CAVERN_COAT_THRESHOLD;
                                lim = patch > threshold + static_cast<float>(exp - 1) * 0.25f;
                            } else {
                                lim = exp == 1 && patch > KARST_COAT_THRESHOLD;
                            }
                        }
                    }
                }
                if (!lim && bedFill[col] > 0.0f && y >= strataMin
                        && y < stoneTop + SUBSURFACE_DEPTH - STRATA_SURFACE_CLEARANCE) {
                    const float phase = (static_cast<float>(y) + warp[col]) / STRATA_PERIOD;
                    lim = phase - std::floor(phase) < bedFill[col];
                }
                if (lim) c.ids[ci] = lime;
            }
        }
    }
    generatePockets(c);
}

} // namespace

/* ------------------------------------------------------------------------------ entry */
int32_t Generator::generateCube(int64_t cx, int64_t cy, int64_t cz, uint16_t* ids, uint16_t* const* layers) {
    const int64_t Y0 = cy * CE;
    const uint16_t air = cfg.id[SBW_AIR];
    uint16_t* waterL = (layers && cfg.waterLayer >= 0) ? layers[cfg.waterLayer] : nullptr;
    uint16_t* stateL = (layers && cfg.stateLayer >= 0) ? layers[cfg.stateLayer] : nullptr;
    uint16_t* snowL = (layers && cfg.snowLayer >= 0) ? layers[cfg.snowLayer] : nullptr;
    auto uniformAir = [&]() {
        ids[0] = air;
        return 0;
    };
    if (Y0 >= cfg.maxY || Y0 + CE <= cfg.minY) return uniformAir();
    auto bp = base(cx, cz);
    if (!bp) return -1;
    if (Y0 >= bp->maxTop) return uniformAir();

    /* fill */
    const int64_t X0 = cx * CE, Z0 = cz * CE;
    {
        auto raw = rawCube(cx, cy, cz);
        if (!raw) throw std::runtime_error("column profile unavailable");
        if (raw->ids.empty()) std::fill_n(ids, CELLS, raw->value);
        else std::memcpy(ids, raw->ids.data(), sizeof(uint16_t) * CELLS);
    }
    if (waterL) std::fill_n(waterL, CELLS, uint16_t{0});
    if (stateL) std::fill_n(stateL, CELLS, uint16_t{0});
    if (snowL) std::fill_n(snowL, CELLS, uint16_t{0});

    const BaseTile& b = *bp;
    /* swamp pools */
    bool anySwamp = false;
    for (int c = 0; c < COLS && !anySwamp; c++) anySwamp = b.biome[c] == SBW_SWAMP;
    if (anySwamp) {
        auto pp = pools(cx, cz);
        if (pp) {
            for (int c = 0; c < COLS; c++) {
                const int d = pp->depth[c];
                for (int k = 1; k <= d; k++) {
                    const int64_t y = static_cast<int64_t>(b.h[c]) - k;
                    if (y >= Y0 && y < Y0 + CE) ids[CV_CELL_INDEX(c & 31, static_cast<int>(y - Y0), c >> 5)] = cfg.id[SBW_WATER];
                }
            }
        }
    }

    CubeCtx cc{*this, cx, cy, cz, X0, Y0, Z0, ids, bp, stateL};
    /* the stone band of some column reaches this cube: stalagmites (raw terrain), ores (coal before
     * iron, stone only), crystals, limestone coat + beds + pockets (stone only, after the ores) */
    if (Y0 < b.maxH) {
        std::vector<CavernZone> zones;
        cavernZones(*this, X0, Z0, zones);
        const bool zonesHere = zonesNear(zones, Y0);
        Pad pad;
        if (zonesHere) {
            fillPad(*this, cc, pad, 2, RUN_WINDOW, RUN_WINDOW);
            placeStalagmites(cc, pad, zones);
        }
        generateOres(cc, false);
        generateOres(cc, true);
        generateCrystals(cc);
        generateLimestone(cc, zones, zonesHere, zonesHere ? &pad : nullptr);
    }

    /* water layer: every water cell a source, river tops marked */
    if (waterL) {
        for (int i = 0; i < CELLS; i++) {
            if (ids[i] == cfg.id[SBW_WATER]) waterL[i] = cfg.waterSource;
        }
        if (b.rivers) {
            for (int c = 0; c < COLS; c++) {
                const int oct = b.riverFlow[c];
                if (oct < 0 || oct > 7 || cfg.waterRiver[oct] == 0 || b.water[c] == NO_WATER) continue;
                const int64_t y = static_cast<int64_t>(b.water[c]) - 1;
                if (y < Y0 || y >= Y0 + CE) continue;
                const int i = CV_CELL_INDEX(c & 31, static_cast<int>(y - Y0), c >> 5);
                if (ids[i] == cfg.id[SBW_WATER]) waterL[i] = cfg.waterRiver[oct];
            }
        }
    }

    /* uniform fast path (no layer content) */
    const uint16_t first = ids[0];
    bool uniform = true;
    for (int i = 1; i < CELLS && uniform; i++) uniform = ids[i] == first;
    if (uniform) {
        bool layered = false;
        if (waterL) for (int i = 0; i < CELLS && !layered; i++) layered = waterL[i] != 0;
        if (stateL) for (int i = 0; i < CELLS && !layered; i++) layered = stateL[i] != 0;
        if (!layered) return 0;
    }
    return 1;
}

/* ------------------------------------------------------------------------------ raw cubes */
/* LimestoneGenerator's per-column fields (karst region, strata fill and warp) of a column cube. */
std::shared_ptr<const LimeColumns> Generator::limeColumns(int64_t cx, int64_t cz) {
    return limeCache.get(cx, cz, [this](int64_t a, int64_t b) {
        auto t = std::make_shared<LimeColumns>();
        const int64_t X0 = a * CE, Z0 = b * CE;
        for (int lz = 0; lz < CE; lz++) {
            for (int lx = 0; lx < CE; lx++) {
                const int col = colIdx(lx, lz);
                const auto wx = static_cast<float>(X0 + lx), wz = static_cast<float>(Z0 + lz);
                t->karst[col] = limeRegion.noise(wx * KARST_REGION_FREQ, wz * KARST_REGION_FREQ);
                const float strata = limeRegion.noise(wx * STRATA_REGION_FREQ + 911.0f, wz * STRATA_REGION_FREQ - 377.0f);
                t->bedFill[col] = strata > STRATA_REGION_THRESHOLD
                    ? STRATA_MAX_FILL * std::min(1.0f, (strata - STRATA_REGION_THRESHOLD) / STRATA_FADE) : 0.0f;
                t->warp[col] = t->bedFill[col] > 0.0f
                    ? limeWarp.noise(wx * STRATA_WARP_FREQ, wz * STRATA_WARP_FREQ) * STRATA_WARP_AMPLITUDE : 0.0f;
                t->anyKarst |= t->karst[col] > KARST_REGION_THRESHOLD;
            }
        }
        return t;
    });
}

std::shared_ptr<const OreSource> Generator::oreSource(int64_t scx, int64_t scz) {
    return oreCache.get(scx, scz, [this](int64_t a, int64_t b) { return buildOreSource(*this, a, b); });
}

std::shared_ptr<const RawCube> Generator::rawCube(int64_t cx, int64_t cy, int64_t cz) {
    return rawCache.get3(cx, cy, cz, [this](int64_t a, int64_t b, int64_t c) {
        auto r = std::make_shared<RawCube>();
        r->x0 = a * CE;
        r->y0 = b * CE;
        r->z0 = c * CE;
        std::vector<uint16_t> ids(CELLS);
        fillBox(*this, r->x0, r->y0, r->z0, CE, CE, CE, ids.data());
        r->value = ids[0];
        bool uniform = true;
        for (int i = 1; i < CELLS && uniform; i++) uniform = ids[static_cast<size_t>(i)] == r->value;
        if (!uniform) r->ids = std::move(ids);
        return r;
    });
}

} // namespace sbw
