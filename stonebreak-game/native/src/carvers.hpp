/* Carvers over a 32 x 32 column footprint: PerlinWormCarver, CavernCarver, MegaCavernCarver,
 * RavineCarver, SinkholeCarver, WaterGuard, CaveWaterTable (+ the profile carve styles' sea stacks and tower
 * karst, native only). Ported from the Java (and the previous native ports in terrain_ctx.hpp / generator.cpp) with the 256-block frame replaced by the world
 * bounds: no [1, WORLD_HEIGHT) clips, no 8-bit Y masks, a floor above the bedrock band.
 *
 * Every carver is a deterministic field: the walk of a worm depends only on (seed, source chunk),
 * never on the footprint asking, so two footprints sharing columns carve them identically. */
#pragma once

#include "gen.hpp"

namespace sbw {

/* One carving layer: the top layer uses the world seed; DeepCaveLayers use seed ^ salt * (i + 1)
 * and run in a frame `drop` blocks above the world (world y = frame y - drop). */
struct LayerSeeds {
    int64_t seed = 0;
    int32_t headingSeed = 0, radiusSeed = 0, wobbleSeed = 0;
    static LayerSeeds of(int64_t seed) {
        LayerSeeds s;
        s.seed = seed;
        s.headingSeed = javaLongHash(seed + 41);
        s.radiusSeed = javaLongHash(seed + 113);
        s.wobbleSeed = javaLongHash(seed + 8191);
        return s;
    }
};

struct CarveCtx {
    Generator& g;
    LayerSeeds seeds;
    bool dry = false;          /* deep layers: no water anywhere (DeepCaveLayers.DryHeights) */
    int32_t drop = 0;          /* frame -> world: world y = frame y - drop */
    int64_t X0 = 0, Z0 = 0;    /* footprint origin */
    const int32_t* th = nullptr;    /* footprint heights [colIdx] */
    const int32_t* guard = nullptr; /* WaterGuard plane, nullptr = open */
    MaskSet* out = nullptr;
    /* SEA_STACKS outputs (nullptr = not wanted; both preset to FLOOD_NONE): the footprint's CarveTile.flood and
     * CarveTile.seal - the lowest shelf of a sea-flooded cut in the column or a face neighbour. The caller lowers the
     * WaterGuard plane to the seal (as beside standing water) before the other carvers run. */
    int32_t* flood = nullptr;
    int32_t* seal = nullptr;

    CarveCtx(Generator& gen, const LayerSeeds& s) : g(gen), seeds(s) {}

    int32_t height(int64_t x, int64_t z) const { return heightOracle(g, x, z); }
    int32_t water(int64_t x, int64_t z) const { return dry ? NO_WATER : waterOracle(g, x, z); }
    /* frame-y clip: the world cell must sit above the bedrock band and below the top */
    bool yOk(int64_t frameY) const {
        const int64_t y = frameY - drop;
        return y > g.cfg.carveFloor() && y < g.cfg.maxY;
    }
    int32_t yFloor() const { return static_cast<int32_t>(g.cfg.minY) + 6 + drop; } /* worm Y_FLOOR */
    void set(int lx, int64_t frameY, int lz) { out->set(lx, frameY - drop, lz); }
};

constexpr int32_t GUARD_OPEN = INT32_MAX;

/* WaterGuard.guardPlane for the footprint (ring resolved through the oracle). */
void waterGuardPlane(Generator& g, int64_t X0, int64_t Z0, const int32_t* th, const int32_t* water,
                     int32_t* outPlane);
inline bool guardSeals(const int32_t* plane, int idx, int64_t y, int clearance) {
    return plane != nullptr && plane[idx] != GUARD_OPEN && y >= static_cast<int64_t>(plane[idx]) - clearance;
}

/* CaveWaterTable.resolve / zoneAt / galleryWeight */
enum class Zone { VADOSE, EPIPHREATIC, PHREATIC };
int32_t waterTableResolve(const Generator& g, int32_t surface, int32_t waterLevel, float wobble);
Zone waterTableZone(int32_t table, int64_t y);
float waterTableGallery(int32_t table, int64_t y);

/* Carver passes (each ORs into ctx.out). carveWorms / carveRavines walk every source in the footprint's
 * window right here (uncached: the carveFootprint test hook); the generator's tiles replay the cached walks
 * below instead (Generator::carveLayerWorms / carveTopRavines), which carve exactly the same cells. */
void carveWorms(CarveCtx& ctx);
void carveCaverns(CarveCtx& ctx, bool mega);
void carveRavines(CarveCtx& ctx);
void carveSinkholes(CarveCtx& ctx);
/* Profile kind with SBW_FLAG_PROFILE_CARVE, top layer only (no-ops otherwise): carve styles SEA_STACKS (cliffed coasts:
 * wave-cut platform at the water surface - a shallow shelf under the sea where the cut connects to the open sea, which
 * floods it (ctx.flood / ctx.seal) - stacks, notches, sea caves, arches) and TOWERS (tower karst: corridors and
 * pits between vertical-walled towers). Pointwise fields - see carvers.cpp. */
void carveSeaStacks(CarveCtx& ctx);
void carveTowers(CarveCtx& ctx);

/* ---- cached walks ------------------------------------------------------------------------------------
 * A worm system (PerlinWormCarver.spawnWorms of one 16-block source chunk) and a ravine walk depend only on
 * (layer seeds, dry, drop, source chunk) and the column oracles - never on the footprint that carves them -
 * so they are walked ONCE per layer and source chunk and replayed by every footprint whose scan window holds
 * the source (a worm system is shared by ~100 column cubes, a ravine by ~500). Replay = the same ellipsoid /
 * slice rasterization calls in the same footprint context, so the carved bits are identical. */
/* Scan windows (16-block source chunks) of a footprint. */
struct SourceWindow {
    int64_t c0, c1, d0, d1;
};
SourceWindow wormWindow(int64_t X0, int64_t Z0);
SourceWindow ravineWindow(int64_t X0, int64_t Z0);
bool wormSourceAt(int64_t seed, int64_t sx, int64_t sz);   /* PerlinWormCarver.hasWorm */
/* RavineCarver.hasRavine, or - profile kind with SBW_FLAG_PROFILE_CARVE - the same hash thinned by the carve-node
 * density at the source chunk's centre (terrain-aware: ravines where the terrain is incised; none on plains, karst,
 * sea stacks or towers). */
bool ravineSourceAt(Generator& g, int64_t seed, int64_t sx, int64_t sz);
/* The carve-node code of column (x, z) (0 unless profile kind with SBW_FLAG_PROFILE_CARVE). */
uint16_t carveCodeAt(Generator& g, int64_t x, int64_t z);
/* Walk one source (ctx: seeds, dry, drop; footprint fields unused). */
std::shared_ptr<WormWalk> walkWormSystem(CarveCtx& ctx, int64_t sx, int64_t sz);
std::shared_ptr<RavineWalk> walkRavine(CarveCtx& ctx, int64_t sx, int64_t sz);
/* Rasterize a walk into ctx.out over ctx's footprint. */
void rasterWorms(CarveCtx& ctx, const WormWalk& w);
void rasterRavine(CarveCtx& ctx, const RavineWalk& w);
/* CavernCarver.buildFormations over one carver's own mask (world y). */
void buildFormations(Generator& g, int64_t layerSeed, bool mega, int64_t X0, int64_t Z0,
                     const MaskSet& carve, MaskSet& formations);

/* Cavern origins for limestone zones (CavernCarver.computeCavernOrigin) of one carving layer: `seed` = the
 * layer's seed, `drop` = its frame offset (Generator::layerDrop; 0 = the top carvers). out[1] is WORLD y. */
bool cavernOrigin(Generator& g, int64_t seed, int32_t drop, bool mega, int64_t cx16, int64_t cz16, float out[3]);

/* PerlinWormCarver.nearestWormOrigin (sinkhole aim). */
bool nearestWormOrigin(Generator& g, const LayerSeeds& s, int64_t cx16, int64_t cz16, int radius,
                       float out[3]);

} // namespace sbw
