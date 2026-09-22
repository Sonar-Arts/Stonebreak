/* ck_carve_water — inland water at block resolution, stamped from a water plan.
 *
 * Layer 3 of the lakes-first hydrology (Dev Working/Lakes-first hydrology
 * plan.md §6). Input is one terrain tile's raw block heights plus a one-tile
 * halo, the depression-fill planes covering that same ground, and the river
 * polylines planned over it; output is the center tile's heights, per-column
 * water levels, and — where a river runs under standing ground — the floor and
 * roof of the tunnel that carries it.
 *
 * ═══ What this file used to be, and why none of it survived ═══
 *
 * The previous version derived water from noise alone: rivers were the zero
 * isoline of a channel field, their width came from |C| / |grad C|, their
 * surface from a box-blurred and eroded copy of the terrain, and lakes came
 * from a jittered world lattice that flooded a bounded box, tried a ladder of
 * depths, and — when that found nothing, which on smooth terrain was nearly
 * always — excavated a pond instead. It was seam-free and it was fast, and it
 * delivered 0.04 % of land as inland water while the physical answer is 2.9 %.
 * Every one of those mechanisms is gone (§3.1). A channel field has no source
 * and no mouth and crosses contours; a width from a noise gradient is unrelated
 * to anything physical; the blur and erode existed to paper over the fact that
 * nothing routed; the lattice, the depth ladder and the excavator existed to
 * paper over the fact that nothing filled.
 *
 * One fill replaces all of it. `basin_plan.hpp` produces lake surfaces, lake
 * depths, river sources, river termini and the field rivers descend — five
 * things the old designs computed five different ways, none of them agreeing.
 *
 * ═══ Canonicality (the seam rule) ═══
 *
 * Every emitted value must be a pure function of (seed, column) so that any
 * tile whose window covers a column computes the identical value. This file no
 * longer has a mechanism of its own to argue about: it reads the DEM planes
 * and the routes, and those are canonical because a region owns them (§4.1,
 * §5) and because a basin small enough to be emitted is contained whole by
 * every window that touches it (§4.4). Everything here is either a comparison
 * or an order-independent merge — heights by `min`, water by `max` — so two
 * tiles stamping the same column from the same plan cannot disagree.
 *
 * ═══ Rivers tunnel; they do not excavate (2026-09-07) ═══
 *
 * This kernel does not lower terrain. It used to, in two places, and both are
 * gone:
 *
 *   The CHANNEL CUT wrote `carved = surf - cut` unconditionally. `surf` is the
 *   river surface read off the depression-filled DEM at 16-BLOCK cells, while
 *   `carved` lands in the FULL-RESOLUTION heightfield, and nothing compared the
 *   two. Where block-scale ground stood above `surf` the entire column dropped
 *   to the water line, so a river crossing a hill deleted the hill. The router's
 *   defence — "a route never climbs, largest rise 3.1 blocks"
 *   (river_plan.hpp) — is true and says nothing about this: it is measured on
 *   the same coarse grid the route descends, not on the ground written here.
 *
 *   The VALLEY PULL drew every column within 80 blocks down toward
 *   `surf + bank_tolerance`. It only ever lowered, so with the rule above it
 *   can no longer fire anywhere, and it is deleted rather than gated — a knob
 *   that looks honoured and is not is worse than an absent one. `git show
 *   6758ff77` has it if river valleys are wanted back.
 *
 * In their place, one per-column decision taken against the fine `raw`:
 *
 *     raw - tunnel_min_roof >  surf_top + tunnel_min_air
 *         -> TUNNEL: roof = min(max(surf_top + headroom·(1 - u²),
 *                                   surf_top + tunnel_min_air),
 *                               raw - tunnel_min_roof),
 *                    floor/roof planes, `carved` untouched
 *     otherwise
 *         -> OPEN:   `carved = surf - cut`, as before
 *
 * The roof arches on the same `u` the bed is cut on, so the WATER'S void
 * narrows toward the channel edge — but it does not pinch SHUT. It stops at
 * `tunnel_min_air`, because the block loop makes the roof plane itself stone
 * and the air a column delivers is `roof - water`: with the arch alone that
 * was nil along both sides of every tunnel, and one or two blocks under any
 * hill barely taller than the lid. A passage the river fills to its ceiling is
 * not a passage. Nothing outside the half-width is opened by that floor, so
 * what holds the water in is unchanged. The only thing opened past the channel
 * edge is the dry bulge above the waterline (see "Noise on the walls"), which
 * sits on a stone lip at the top water block.
 * `tunnel_min_roof` is the thinnest lid that reads as rock rather than debris,
 * and clamping the roof to `raw - it` bounds what the stamp may still take.
 *
 * ═══ `surf_top`: the void spans a fall, the water does not (2026-09-21) ═══
 *
 * `surf` STEPS at the middle of a falling segment — §5.8's whole point, and
 * right for the water and the bed. It was wrong for the VOID. The roof, the
 * lid test and the portal were all read off the stepped value, so downstream
 * of a drop the ceiling sat at `bSurf + arch` while the reach above poured in
 * at `aSurf`; for any drop taller than the arch the two voids were not even
 * connected, and the river simply stopped against rock. So those three read
 * `surf_top` — the higher of the segment's two levels — and one shaft spans
 * the drop. Away from a step `surf_top == surf` and nothing moves.
 *
 * A column that cannot carry a lid over `surf_top` now takes the OPEN branch
 * and is cut to the water: the fall breaks the surface instead of being roofed
 * over mid-air. That is the excavation budget, and it is declared rather than
 * unbounded: `tunnel_min_roof + tunnel_min_air + the bed`, plus the drop where
 * a plunge is cut open. With pooling on every pool boundary carries the
 * waterfall flag, but those drops are at most `pool_max_drop` and the air
 * floor already clears them, so ordinary riffles cost nothing.
 *
 * ═══ Containment (the WaterSim invariant) ═══
 *
 * Worldgen water is source blocks: a wet column with a lower dry 4-neighbor is
 * a permanent spring that floods every chunk it touches. Rule held here (same
 * as the bridge's carve.py): for every wet column at level W, each 4-neighbor
 * is wet itself or has terrain >= W. Wet-next-to-wet at different levels is a
 * waterfall and is deliberately allowed — §5.8 depends on it. Since the guard
 * rail (below) the dry neighbour is walled to the column's RAIL, which is >= W,
 * so the rule still holds with room to spare.
 *
 * ═══ The bank skirt (2026-09-15) ═══
 *
 * The rule above is not negotiable, so the wall it produces cannot be removed;
 * it can only stop being a CLIFF. §1a's shore flood shrinks the walled set by
 * wetting the ground the coarse mask cut off, but a lake with no outlet has to
 * be walled at its spill lip, and a waterfall has to be walled BESIDE its
 * drop. §2b therefore grades the ground away from whatever §3 raises, at a
 * repose angle, out to wherever the ramp meets the terrain that was already
 * there — everywhere except at a real plunge, where a repose terrace across
 * the drop is a mound of stone in front of the water and the bare crest is
 * what a waterfall should have. See `talusSkirt`.
 *
 * That skirt is the one thing in this file that ADDS ground, and it adds it
 * because nothing here may take any away: the crest is pinned at the waterline
 * by the invariant, so the only shape left is a monotone ramp down from it. It
 * never touches a wet column and never rises above the water it banks.
 *
 * ═══ Noise on the walls (2026-09-17) ═══
 *
 * A plain 1:4 ramp did nothing to the commonest wall there is — a river bank
 * one block high — because the column beside the crest floors to `W - 1`,
 * which is the ground that was already there. So the skirt now holds the
 * crest level for a noisy one to three columns before it falls, and the fall
 * itself varies in steepness and carries a block of roughness. The tunnel
 * shell gets the same treatment: an uneven vault and, above the waterline, a
 * dry bulge past the channel edge, so the passage stops being one parabola
 * extruded along the route.
 *
 * Every noise value is `smoothNoise01` of the WORLD column and the seed,
 * evaluated per column with nothing accumulated, so the seam rule holds. All
 * of it raises dry ground or opens rock ABOVE the water — never beside it.
 *
 * ═══ The guard rail (2026-09-18) ═══
 *
 * Holding the static waterline is not enough, because the static waterline is
 * not where WaterSim leaves the water. A river's surface steps down a block at
 * a time — `lround` of a ramp, which on a diagonal is a staircase — and every
 * step exposes the upper column's top water block to air, so it is scheduled
 * on load and spreads a flowing layer ONE BLOCK ABOVE the lower reach. The
 * bank there was walled to the lower reach's level, and the layer runs over it.
 *
 * Worse, it does not stay a layer. `WaterSim.computeState` mints a source from
 * any flowing cell with two source neighbours and a source BELOW it, and a
 * step front that is not dead straight always has such cells, so the lower
 * reach is promoted one layer up, which makes the next front a step of its
 * own, and so on: a reach settles at the level of the water upstream of it.
 * The sim is not changing (the user's call), so the banks have to.
 *
 * §2c therefore gives every wet column a RAIL: the highest level of any water
 * within `river_guard_reach` wet steps of it, capped at `RIVER_GUARD_LIFT`
 * over the column's own water — the level the cascade can carry down to it.
 * §2a, §2b and §3 wall to the rail instead of to the water. Wet columns are
 * the only conductors, so a rail never jumps a ridge to a different river;
 * where nothing higher is within reach the rail IS the water, so a still lake
 * and a flat reach come out exactly as before.
 *
 * ═══ Both bounds are measured, and the first pair was far too generous ═══
 *
 * The reach shipped at 32 and the lift was unbounded, and on a river that
 * descends at all that pair is not a detail: the freeboard a bank carries is
 * the river's own SLOPE times the reach, along the whole river. Measured over
 * five river fixtures and four seeds — twenty runs of six tiles each, with
 * `FlowReplica` (the sim, rule for rule) judging every one of them:
 *
 *     reach  lift  ground raised   mean freeboard   escaped
 *        32   —          99,847         1.57 blk          0
 *        12   —          21,312         0.88 blk          0
 *        12   3          10,349         0.76 blk          0   <- ships
 *         0   —           3,561         0.65 blk        552   <- rail off
 *
 * "Freeboard" is what a player sees: how far the ground beside the water
 * stands over that water. At 32 the rail was raising ten blocks of ground for
 * every one the sim needed, to stop water it never sends — nothing escaped at
 * 12 either, in any of those twenty runs — and the capped pair reads as bare
 * bank (0.76) against no rail at all (0.65).
 *
 * The lift is the bound that matters for what a bank LOOKS like, and it is
 * measured the same way: at the shipping reach, a cap of 2 leaks on two of
 * the twenty runs and a cap of 3 leaks on none.
 *
 * It is NOT the promotion the sim performs, and the difference is worth
 * stating because the obvious reading is wrong: the replica settles some
 * reaches four to six blocks over their planned level. Those reaches do not
 * leak, because the ground already stands over them — a rail is only ever
 * spent where the bank is LOWER than the water can get, and how far the sim
 * lifts a reach in a gorge says nothing about that. Three is what containment
 * costs, not what the cascade does.
 *
 * `testTheGuardRailHoldsWhatTheSimMakes` pins both directions.
 *
 * Known limit, stated rather than hidden: the fully settled level is the
 * headwater's, carried the whole length of the river, and no bounded window
 * can know it. A cascade that climbs further than the lift, or runs further
 * than the reach, can still climb past the rail; raise [31] and the lift
 * together if that shows up, and re-measure the table above rather than
 * guessing — the mechanism defeats derivation. It was derived twice while
 * this was being written ("only a one-block step can mint a source"; "a fall
 * delivers one block over what it lands in") and the replica falsified both,
 * promoting a pool two blocks under a four-block fall.
 */

#include "cenda/kernels.h"
#include "basin_plan.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace {

/* Blocks of air between the river surface and the tunnel roof, measured at the
 * centreline; the roof arches down toward the surface at the channel edge, but
 * never below `tunnel_min_air` of it.
 * Params slot [24] ("tunnel_headroom") overrides it. */
constexpr float DEF_TUNNEL_HEADROOM = 5.0f;
/* The FLOOR under that arch: the least air a tunnelled column may carry over
 * its water, anywhere across the channel. [24] is the vault's target at the
 * centreline and may exceed this; this is what the sides get.
 * Params slot [35] ("tunnel_min_air") overrides it.
 *
 * It exists because the arch rides on `1 - u^2` and so went to ZERO at the
 * channel edge, and the block loop makes the roof plane itself stone: the air
 * a column actually delivers is `roof - water`, which was nil along both sides
 * of every tunnel and 1-2 blocks under any hill barely taller than
 * `tunnel_min_roof`. A river you cannot see along is not a tunnel.
 *
 * It is also half of the tunnel/open predicate: a column is only worth
 * tunnelling if its ground can carry a lid AND this much air under it. Ground
 * that cannot takes the open branch and is cut to the water line, exactly as
 * shorter ground already was. */
constexpr float DEF_TUNNEL_MIN_AIR = 3.0f;
/* The thinnest rock lid that still reads as ground rather than as debris.
 * Because the roof is clamped to `raw - this`, it doubles as the excavation
 * budget: the most ground the stamp may take from a column it tunnels under.
 * Params slot [25] ("tunnel_min_roof") overrides it.
 *
 * Lowering it toward zero is what makes a river bead — `surf` is a bilinear
 * sample of a 16-block DEM and fine terrain wobbles a few blocks either side of
 * it, so a small value roofs a river with one-block lids wherever it does. This
 * is the knob to raise if that shows up. */
constexpr float DEF_TUNNEL_MIN_ROOF = 4.0f;

/* A drop of at least this much over one step is a real PLUNGE rather than a
 * pool boundary. Params slot [12] ("waterfall_min_drop") overrides it, and it
 * is the plan's own knob — `river_plan.hpp` classifies on the same number.
 * With pooling on, EVERY pool boundary carries the waterfall flag (the flag
 * means "the surface steps here"), so the flag alone cannot tell a one-block
 * riffle from a cliff and the drop has to be measured. */
constexpr float DEF_WATERFALL_MIN_DROP = 6.0f;
/* How far past the channel a plunge holds the talus skirt off. See
 * "A plunge is not graded" below. */
constexpr float PLUNGE_SKIRT_PAD = 2.0f;

/* How far past the coarse lake mask the shore flood may travel, in blocks.
 * Params slot [26] ("lake_shore_reach") overrides it.
 *
 * This is a PATH length, not a box: a column's wetness has to be a function of
 * a neighbourhood no window can truncate, or two tiles sharing a shore draw it
 * differently. Every center-tile column has T blocks of margin inside its own
 * window, so the cap is clamped to `T - 1` below. 64 covers any real shelf;
 * the clamp is what makes a silly value safe rather than seam-breaking. */
constexpr float DEF_LAKE_SHORE_REACH = 64.0f;
/* The deepest a column may sit below the lake surface and still be claimed by
 * the shore flood. Params slot [27] ("lake_shore_max_depth") overrides it.
 *
 * A real shore is shallow by definition — it is ground the coarse DEM read a
 * few blocks too high, because a cell is a 16x16 MEAN of a noisy heightmap and
 * the coarse fetch carries no noise at all. A column ten blocks under the
 * surface that the fill nonetheless called dry is a different animal: a
 * block-scale notch through the rim that the mean averaged away. Flooding it
 * would run a tongue of water down the outside of the basin. So the flood
 * repairs the shoreline and refuses to discover new basins, which is the fill's
 * job and needs the fill's window. */
constexpr float DEF_LAKE_SHORE_MAX_DEPTH = 8.0f;

/* How gently the ground falls away from a containment wall, in blocks of drop
 * per block of horizontal distance. Params slot [29] ("lake_bank_slope")
 * overrides it.
 *
 * The wall cannot be removed. §3 below is the WaterSim invariant, and a wet
 * column with a lower dry neighbour is a spring — so the only thing left to do
 * with a wall is stop it being a cliff. The skirt is therefore RAISE-ONLY: a
 * talus ramp from the crest outward, ending where it meets the ground the
 * terrain already had. Lowering toward the water is not an option and is not
 * coming back; the river valley pull that did exactly that is deleted, for the
 * reasons in the header.
 *
 * 1:4 was the first value here, and it was wrong for the reason a player
 * notices: loose ground does not lie at 1:4, it lies at its ANGLE OF REPOSE,
 * which for scree is about 34 degrees — near enough 1:1.5. At 1:4 a wall eight
 * blocks tall spread thirty-two columns of dead-flat fill across a hillside,
 * which reads as a road embankment stuck to a mountain. At repose the same
 * wall dies in twelve, as a talus cone does.
 *
 * Retuned together with DEF_LAKE_BANK_REACH: `slope * reach` is the skirt's
 * total fall and that correspondence is load-bearing (see below). */
constexpr float DEF_LAKE_BANK_SLOPE = 0.65f;
/* How far the skirt may travel from a wall, in blocks. Params slot [30]
 * ("lake_bank_reach") overrides it.
 *
 * A PATH length, for the same reason `lake_shore_reach` is one, and it SHARES
 * that reach's margin: a skirt seeded from a wall the shore flood found needs
 * `1 + shore_reach + bank_reach` blocks of window, so the clamp below is
 * `T - 1 - shoreReach` rather than `T - 1`. Eight-connected, so a path of `n`
 * edges is at most `n` columns away on each axis — the ring index still bounds
 * the box, which is what the margin argument needs.
 *
 * 32 columns at 1:4; 12 at repose. The product is what matters, not either
 * number, and shortening the reach also gives the stamp range back the twenty
 * columns the skirt used to spend (measured at 0.95 -> 1.33 ms/tile when it
 * was widened to 32).
 *
 * `slope * reach` is 8, which is `lake_shore_max_depth` on purpose. A wall no
 * taller than that is one the flood WANTED and its depth gate refused, so the
 * skirt reaches natural ground and no cliff is left at all; a taller one is a
 * rim the flood declined to cross, and grading that away to nothing would be
 * inventing a hillside rather than blending one. Retuning either knob alone
 * breaks the correspondence. */
constexpr float DEF_LAKE_BANK_REACH = 12.0f;

/* How far upstream, in blocks of wet path, a bank looks for water higher than
 * its own when deciding how tall to stand. Params slot [31]
 * ("river_guard_reach") overrides it. See the header's "The guard rail".
 *
 * A PATH length through wet columns, and it spends the same margin the other
 * two reaches do: a skirt `bank_reach` outside the tile seeds from a rail that
 * read water `guard_reach` beyond that, so the budget is
 * `1 + shore_reach + bank_reach + guard_reach <= T` and this is clamped from
 * whatever the other two left. It is a declared cap, not a halo that could be
 * grown until it is right — the settled level has no bound (see the header).
 *
 * 32 for one day, which was 2.7x past where the sim stops spilling and cost
 * five times the ground for it. The knee is 12: eight held every run but the
 * two on the gentlest fixture, which leaked a block each. This is the worst
 * measured knee with no margin folded into it, because the margin is the LIFT
 * below — reaching further only finds water a bank may already be walled to,
 * and the cap is what decides how much of it counts. */
constexpr float DEF_RIVER_GUARD_REACH = 12.0f;
/* And how far over the water beside it a rail may stand, in blocks: the least
 * that still contained the sim on every fixture measured, 2 having leaked on
 * two of them. Not a params slot — one knob for the rail's extent is enough,
 * and this is a property of `WaterSim` rather than of a world. See the
 * header's table, including what this is NOT.
 *
 * The reach says how far a level travels; this says how high it may climb
 * when it gets there, and on a ramping river they are not the same question.
 * A reach alone answers it as `slope * reach`, which is how the uncapped rail
 * came to stand ten blocks over water a player could step across. */
constexpr int RIVER_GUARD_LIFT = 3;

/* ── Wall noise (see the header's "Noise on the walls") ──
 *
 * Not params slots: they are shape, not policy, and nothing outside this file
 * has a reason to move them. */

/* How many columns PAST the wall itself the crest stays level with the water,
 * at most. The wall is one column, so a bank's lip is 1 to 1 + this thick. */
constexpr float BANK_PLATEAU_MAX = 2.0f;
/* How much steeper than `lake_bank_slope` the ramp may run, as a fraction of
 * it. Steeper only, never gentler: the slope is the floor of the fall, so
 * `slope * reach` stays the least a skirt spends and the budget in
 * DEF_LAKE_BANK_REACH's comment still holds. */
constexpr float BANK_SLOPE_JITTER = 0.5f;
/* Blocks of roughness on the ramp, either way. Faded in over the first column
 * past the plateau so the lip does not end in a notch. */
constexpr float BANK_ROUGH = 1.0f;
/* Wavelengths, in blocks: the lip width changes along a bank over about a
 * chunk; the roughness is finer. */
constexpr float BANK_PLATEAU_WAVE = 9.0f;
constexpr float BANK_SLOPE_WAVE = 13.0f;
constexpr float BANK_ROUGH_WAVE = 4.0f;

/* Blocks of roughness carried on a LONG wavelength, on top of BANK_ROUGH's
 * block-scale fuzz. This is the term that makes the skirt's outline lobed
 * rather than a clean arc: the toe is where the ramp meets the ground, so a
 * wobble of `h` blocks in the ramp moves that meeting point `h / slope`
 * columns in or out — at repose, about a column and a half per block. Block
 * fuzz alone moves the toe by less than the eye resolves from ground level;
 * this moves it by lobes you can walk around. */
constexpr float BANK_TOE_ROUGH = 1.5f;
constexpr float BANK_TOE_WAVE = 23.0f;

/* A wall's crest is the rail exactly, with no freeboard on top. Tried, and
 * removed the same day: lifting the crest lifts the whole skirt hanging off
 * it, which spent more fill than the repose angle had just saved, and it put
 * raised ground above the highest water on the tile — the bound
 * `testTheBankBrushNeverWetsNorLowersNorRaisesWater` pins. The dead-level
 * skyline it was meant to break is mostly gone anyway now that §5.8b's pools
 * leave far fewer crest-height walls standing. */

/* ── Strata (see `strataStep`) ── */
/* The thickness of a bed, in blocks, and how much of each bed is flat tread
 * rather than riser. 4 and 0.3 put a walkable ledge every four blocks. */
constexpr float STRATA_BAND = 4.0f;
constexpr float STRATA_TREAD = 0.3f;
/* Beds are not level over a landscape. The whole stack is shifted by up to
 * this many blocks, over this wavelength, which tilts and folds it gently
 * instead of ringing the world in perfect contour lines. */
constexpr float STRATA_PHASE = 3.0f;
constexpr float STRATA_PHASE_WAVE = 97.0f;

/* The vault's headroom varies by these fractions of `tunnel_headroom` either
 * side of it: a slow swell along the route and finer lumps on top, 0.4x to
 * 1.6x in all. Fractions rather than blocks so that a headroom of zero still
 * means no air, which is what that knob promises. Both ride on the same
 * `1 - u²` the arch does, so the void still pinches shut at the channel edge. */
constexpr float TUNNEL_HEADROOM_JITTER = 0.3f;
constexpr float TUNNEL_ROUGH = 0.3f;
constexpr float TUNNEL_HEADROOM_WAVE = 17.0f;
constexpr float TUNNEL_ROUGH_WAVE = 5.0f;
/* How far past the channel edge, in blocks, the dry bulge above the waterline
 * may reach, and how tall it stands at the edge. */
constexpr float TUNNEL_BULGE_MAX = 2.0f;
constexpr float TUNNEL_BULGE_RISE = 2.0f;
constexpr float TUNNEL_BULGE_WAVE = 7.0f;

/* ── The portal (see `portalNearness`) ──
 *
 * A tunnel's mouth is the one part of it a player sees from outside, and until
 * now it was the worst part: the lid is clamped to `raw - tunnel_min_roof`, so
 * as the ground falls away toward the opening the roof falls with it and the
 * passage PINCHES SHUT exactly where it meets daylight. A hole in a flat face,
 * which is what a river tunnel looked like from the valley.
 *
 * Real cave mouths are the opposite: widest and tallest at the entrance, cut
 * back into an overhung alcove, and taller than they are wide (which is what
 * Tectonic found when it reworked its own underground rivers — it moved the
 * transition away from the mountain and made the entrances much taller).
 *
 * So near the mouth the lid is allowed to thin to PORTAL_MIN_ROOF, the vault
 * to rise by PORTAL_RISE, and the dry bulge beside the water to flare out to
 * PORTAL_FLARE — an overhang, since only the void moves and the ground above
 * it is untouched. `PORTAL_CLEARANCE` is the lid thickness over which all of
 * that fades back to an ordinary tunnel. */
constexpr float PORTAL_CLEARANCE = 10.0f;
constexpr float PORTAL_MIN_ROOF = 2.0f;
constexpr float PORTAL_RISE = 1.0f;
constexpr float PORTAL_FLARE = 3.0f;
/* What the bucket pad and the segment clip have to cover: the widest the void
 * beside a channel can reach, bulge or flare. */
constexpr float CHANNEL_PAD = PORTAL_FLARE > TUNNEL_BULGE_MAX ? PORTAL_FLARE : TUNNEL_BULGE_MAX;
/* ...and what the bucket pad and the segment clip must ACTUALLY cover: the
 * widest void, plus the plunge mask that reaches a little further still. One
 * constant for both, because a mask truncated on a bucket line is exactly the
 * lattice artifact CHANNEL_PAD exists to prevent. */
constexpr float STAMP_PAD = CHANNEL_PAD + PLUNGE_SKIRT_PAD;

constexpr uint64_t SALT_BANK_PLATEAU = 0x42414E4B504C5400ULL; /* "BANKPLT" */
constexpr uint64_t SALT_BANK_SLOPE = 0x42414E4B534C5000ULL;   /* "BANKSLP" */
constexpr uint64_t SALT_BANK_ROUGH = 0x42414E4B52474800ULL;   /* "BANKRGH" */
constexpr uint64_t SALT_TUNNEL_HEAD = 0x54554E4E48454400ULL;  /* "TUNNHED" */
constexpr uint64_t SALT_TUNNEL_ROUGH = 0x54554E4E52474800ULL; /* "TUNNRGH" */
constexpr uint64_t SALT_TUNNEL_BULGE = 0x54554E4E424C4700ULL; /* "TUNNBLG" */
constexpr uint64_t SALT_BANK_TOE = 0x42414E4B544F4500ULL;     /* "BANKTOE" */
constexpr uint64_t SALT_STRATA = 0x5354524154410000ULL;       /* "STRATA"  */

inline size_t idx2(int row, int col, int stride) {
    return static_cast<size_t>(row) * static_cast<size_t>(stride) + static_cast<size_t>(col);
}

/** Floor division for the bilinear stencil, which straddles zero near a
 *  window's first cell. */
inline int floorDivInt(int a, int b) {
    const int q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

/**
 * Smooth 2D value noise in [0, 1) at a WORLD column: hashed lattice corners
 * every `wave` blocks, blended with a smoothstep. A pure function of its
 * arguments, evaluated once per column and never accumulated, so every tile
 * whose window covers a column reads the same value — the seam rule for free.
 */
inline float smoothNoise01(int64_t seed, int64_t wx, int64_t wz, float wave, uint64_t salt) {
    const double fx = (static_cast<double>(wx) + 0.5) / wave;
    const double fz = (static_cast<double>(wz) + 0.5) / wave;
    const double x0 = std::floor(fx);
    const double z0 = std::floor(fz);
    const auto cx = static_cast<int64_t>(x0);
    const auto cz = static_cast<int64_t>(z0);
    const auto ease = [](float t) { return t * t * (3.0f - 2.0f * t); };
    const float tx = ease(static_cast<float>(fx - x0));
    const float tz = ease(static_cast<float>(fz - z0));
    const auto at = [&](int64_t i, int64_t j) {
        return cenda::basin::hash01(cenda::basin::hashCell(seed, i, j, salt));
    };
    const float a = at(cx, cz) + (at(cx + 1, cz) - at(cx, cz)) * tx;
    const float b = at(cx, cz + 1) + (at(cx + 1, cz + 1) - at(cx, cz + 1)) * tx;
    return a + (b - a) * tz;
}

/* The DEM planes covering the 3x3 tile window, at cell resolution. */
struct Dem {
    const float* filled;
    const float* depth;
    int cells;
    int cellBlocks;
};

/**
 * The lake level at one column of the window, or -1 for dry.
 *
 * The lake's SURFACE is flat and comes from the fill; all that happens here is
 * deciding which columns are under it. That split is the whole trick: the level
 * is one integer for a whole basin, so a lake is level to the bit however
 * jagged its edge, while the edge itself is drawn at block resolution by
 * comparing real block heights against that level. A 16-block DEM produces a
 * 1-block shoreline for free, and the two properties never fight.
 *
 * Membership uses the bilinear 2x2 stencil — the four cells whose CENTERS
 * bracket the column — but takes the maximum level among those that are lake
 * rather than interpolating between them. §6 says "bilinear across cells", and
 * interpolating is what that would mean; it is also wrong here. Between a lake
 * cell and the dry cell beside it, an interpolated surface tilts, and a tilted
 * lake surface is the exact defect the old eroded-blur water plane had. Taking
 * the maximum keeps the surface flat and lets the lake reach at most half a
 * cell into the dry ground beside it, which is the shoreline tolerance the
 * coarse lattice owes the block grid. Where two lakes at different levels reach
 * the same column the higher wins, which is the same order-independent max rule
 * lakes already merge by.
 */
inline int lakeLevelAt(const Dem& dem, int x, int z) {
    /* Cell c's center sits at (c + 0.5) * cellBlocks in window coordinates, so
     * the stencil's low cell is floor(x / cellBlocks - 0.5). */
    const int i0 = floorDivInt(2 * x - dem.cellBlocks, 2 * dem.cellBlocks);
    const int j0 = floorDivInt(2 * z - dem.cellBlocks, 2 * dem.cellBlocks);
    int level = -1;
    for (int di = 0; di <= 1; ++di) {
        const int ci = std::clamp(i0 + di, 0, dem.cells - 1);
        for (int dj = 0; dj <= 1; ++dj) {
            const int cj = std::clamp(j0 + dj, 0, dem.cells - 1);
            const size_t c = idx2(ci, cj, dem.cells);
            if (dem.depth[c] > 0.0f) {
                level = std::max(level, static_cast<int>(std::lround(dem.filled[c])));
            }
        }
    }
    return level;
}

/* ── Rivers ─────────────────────────────────────────────────────────────── */

/** One reach of a route, in WINDOW coordinates. */
struct Seg {
    float ax, az, bx, bz;
    float aSurf, bSurf;
    float aHalf, bHalf;   /* half-widths */
    float aBed, bBed;
    float shape;          /* cross-section exponent; see rosgenShape */
    bool falls;           /* the surface steps rather than ramping (§5.8) */
    bool gorge;           /* runs between walls: a narrower channel (§5.6) */
};

/**
 * Cross-section exponent from a simplified Rosgen classification on
 * (local slope, width) — §6's "cheap per-column table lookup, and the largest
 * visual gain per millisecond available".
 *
 * The cut across a channel is `bed * (1 - t^p)` for `t` the fraction of the
 * half-width, so `p` alone decides the shape: near 1 it is a V, and as it
 * grows the bed flattens and the banks steepen into a U. Rosgen's stream types
 * differ mostly in exactly that, and mostly with gradient:
 *
 *   A  steep (> 4 %)   entrenched step-pool, narrow and deep     -> V
 *   B  moderate (2-4%) moderately entrenched, moderate w/d       -> between
 *   C/E gentle (< 2 %) meandering, wide and shallow, flat bed    -> U
 *
 * This is a caricature of the classification, not the classification: real
 * Rosgen needs entrenchment ratio, width/depth ratio and sinuosity, and types
 * D (braided) and F (incised) have no representation here at all. It is
 * documented as a caricature because it will look right without being right,
 * the same trade §5.5 makes for width.
 */
inline float rosgenShape(float slope) {
    if (slope > 0.04f) {
        return 1.2f;   /* A: a V-notch cut by a steep stream          */
    }
    if (slope > 0.015f) {
        return 2.0f;   /* B: a moderate trough                        */
    }
    return 3.2f;       /* C/E: a broad flat bed with defined banks    */
}

/** Squared distance from a point to a segment, and the parameter of the
 *  nearest point along it. */
inline float segDistanceSq(const Seg& s, float px, float pz, float& t) {
    const float dx = s.bx - s.ax;
    const float dz = s.bz - s.az;
    const float len2 = dx * dx + dz * dz;
    t = len2 > 1e-9f ? ((px - s.ax) * dx + (pz - s.az) * dz) / len2 : 0.0f;
    t = std::clamp(t, 0.0f, 1.0f);
    const float qx = s.ax + dx * t - px;
    const float qz = s.az + dz * t - pz;
    return qx * qx + qz * qz;
}

/* Per-thread scratch. Reused across calls on the same worker thread (the
 * generator.cpp pattern). */
struct Scratch {
    std::vector<int16_t> carved;
    std::vector<int16_t> water;
    /* Where a river runs under standing ground: the void carried through it,
     * -1 for the great majority of columns that have none. */
    std::vector<int16_t> floor;
    std::vector<int16_t> roof;
    /* Every segment of the refined polyline: what the CHANNEL is cut from,
     * where four-block detail is the whole point of refining. */
    std::vector<Seg> channel;
    /* Segment indices per 16-block bucket of the window. Rivers touch a few per
     * cent of a tile, so the bucket list is what lets the other 95 % of columns
     * skip the river pass on one empty-vector test. */
    std::vector<std::vector<int32_t>> channelBuckets;
    /* Which columns are under a lake surface, and at what level; -1 for dry.
     * Built once per tile by `floodLakeShore` and read by the stamp. */
    std::vector<int16_t> lakeWater;
    /* The shore flood's working set: the seeded columns that have somewhere to
     * expand to, their levels, and the two rings of a level-synchronous BFS.
     * Synchronous rings rather than a distance plane — the ring index IS the
     * path length, so the reach cap costs no memory and no per-column state. */
    std::vector<int32_t> shoreFrontier;
    std::vector<int32_t> shoreFrontierLevel;
    std::vector<int32_t> shoreRing;
    std::vector<int32_t> shoreNextRing;
    std::vector<int32_t> shoreLevels;
    /* The bank skirt's value plane, in Q8 blocks (units of 1/256 of a block),
     * 0 where no skirt reaches, plus the two rings of its frontier relaxation
     * and the values those rings were pushed with.
     *
     * Fixed point rather than float because a value accumulates one step's cost
     * per edge along a path, and float accumulation differs in the last ulp with
     * the path taken — one ulp is enough to flip the floor at emission and make
     * two tiles disagree on a column they share. */
    std::vector<int32_t> bank;
    std::vector<int32_t> bankRing;
    std::vector<int32_t> bankNextRing;
    std::vector<int32_t> bankRingVal;
    std::vector<int32_t> bankNextRingVal;
    /* Columns under or beside a real PLUNGE, which the talus skirt steps
     * around. One byte per column rather than a set, because the stamp writes
     * it column-by-column and the skirt reads it the same way. */
    std::vector<uint8_t> plunge;
    /* The crest of the wall each `bank` value descends from, Q8. Together
     * they give the path distance back — `(crest - bank) / cost` — which is
     * what the noisy profile at emission is a function of. */
    std::vector<int32_t> bankCrest;
    std::vector<int32_t> bankRingCrest;
    std::vector<int32_t> bankNextRingCrest;
    /* Which way the river over this column RUNS, as an octant 0..7 of
     * (dx, dz), or -1 where the column's level was not set by a channel —
     * dry ground, a lake, the sea. See `riverOctant`. */
    std::vector<int8_t> flow;
    /* §2c's guard rail per column, -1 for dry, plus the rings of its
     * relaxation and the values they were pushed with. */
    std::vector<int16_t> rail;
    std::vector<int32_t> railRing;
    std::vector<int32_t> railNextRing;
    std::vector<int16_t> railRingVal;
    std::vector<int16_t> railNextRingVal;
};

thread_local Scratch tls;

/**
 * Fill `s.lakeWater` over `[lo, hi)^2` of the window: the level at every column
 * a lake covers, -1 elsewhere.
 *
 * `lakeLevelAt` decides where a lake IS. This decides where it ENDS, and the
 * two are different questions that were being answered by the same test.
 *
 * The coarse answer is a cell mask: a lake cell donates its level to its own 16
 * columns plus 8 blocks of dilation, so the wet region's boundary lies on the
 * lines `x = 8 (mod 16)` — straight, axis-aligned, and visible from a long way
 * off. Worse, the columns cut off that way are not merely left dry: §3's
 * containment repair RAISES them flush to the water, so the artifact reads as a
 * wall of ground standing exactly at the waterline. (Beaches are disabled for
 * all inland water because of those walls — see DiffusionBiomeMapper.)
 *
 * Two things guarantee real ground falls outside the mask, and neither is a
 * tuning error. `min_lake_depth` drops the outermost ring of cells, whose 16x16
 * MEAN depth is under half a block even where most of their columns are wet.
 * And the coarse DEM is a different FIELD from the block heightmap, not a
 * coarser view of it: the coarse fetch carries no noise and is not floored,
 * while fine heights carry both, so fine ground near a shore sits
 * systematically below what the fill believes.
 *
 * So the mask seeds a flood and stops there. The shore is then wherever the
 * real blocks cross the level — connectivity, at block resolution, the way the
 * water itself would find it.
 *
 * What is deliberately NOT changed by this: the level. It is still one integer
 * per basin, still straight from the fill, still never interpolated. A flood
 * cannot tilt a surface, which is why this can be a pure widening of the wet
 * set rather than a new way of computing where the water sits.
 *
 * Order-independence, which the seam rule needs: levels are flooded in
 * DESCENDING order and a claimed column is never re-claimed, so where two lakes
 * reach one column the higher wins — the same max rule `lakeLevelAt` already
 * uses across its stencil, and the same one rivers merge by. Within one level a
 * BFS with uniform edge weights reaches the same set whatever the queue order.
 *
 * Seam-safety, which is the reason for `reach`: a claim travels at most `reach`
 * columns from a seed, so a center-tile column's answer depends only on ground
 * within `reach` of it. `[lo, hi)` is the stamp range grown by `reach`, and the
 * caller clamps `reach` to `T - 1`, so every column of every path is inside the
 * window that stamps it. Grow either without the other and two tiles will
 * disagree along their shared edge.
 */
void floodLakeShore(const Dem& dem, const int16_t* heights, int W,
                    int world_height, int lo, int hi,
                    int reach, int maxDepth, Scratch& s) {
    const size_t N = static_cast<size_t>(W) * static_cast<size_t>(W);
    s.lakeWater.assign(N, -1);

    /* 1. Seed from the coarse mask, by exactly the test the stamp used to make
     *    inline. A seed is the fill's own answer and is taken as given; the
     *    depth cap below applies only to what the flood ADDS. */
    bool any = false;
    for (int x = lo; x < hi; ++x) {
        for (int z = lo; z < hi; ++z) {
            const size_t i = idx2(x, z, W);
            const int level = lakeLevelAt(dem, x, z);
            if (level <= 0) {
                continue;
            }
            if (std::clamp<int>(heights[i], 1, world_height - 1) >= level) {
                continue;
            }
            s.lakeWater[i] = static_cast<int16_t>(level);
            any = true;
        }
    }
    if (!any || reach <= 0 || maxDepth <= 0) {
        return;
    }

    /* 2. The frontier: seeded columns with an unclaimed neighbour. A lake's
     *    interior is already answered, so only its rim can expand — which is
     *    what keeps this proportional to shoreline rather than to lake area. */
    s.shoreFrontier.clear();
    s.shoreFrontierLevel.clear();
    const auto stride = static_cast<size_t>(W);
    for (int x = lo + 1; x < hi - 1; ++x) {
        for (int z = lo + 1; z < hi - 1; ++z) {
            const size_t i = idx2(x, z, W);
            const int level = s.lakeWater[i];
            if (level < 0) {
                continue;
            }
            if (s.lakeWater[i - stride] >= 0 && s.lakeWater[i + stride] >= 0
                    && s.lakeWater[i - 1] >= 0 && s.lakeWater[i + 1] >= 0) {
                continue;
            }
            s.shoreFrontier.push_back(static_cast<int32_t>(i));
            s.shoreFrontierLevel.push_back(level);
        }
    }
    if (s.shoreFrontier.empty()) {
        return;
    }

    /* 3. Distinct levels, highest first. There are as many as there are lakes
     *    touching the window — a handful — so this sorts the frontier's levels
     *    and not the far larger seed set. */
    s.shoreLevels.assign(s.shoreFrontierLevel.begin(), s.shoreFrontierLevel.end());
    std::sort(s.shoreLevels.begin(), s.shoreLevels.end(),
              [](int32_t a, int32_t b) { return a > b; });
    s.shoreLevels.erase(std::unique(s.shoreLevels.begin(), s.shoreLevels.end()),
                        s.shoreLevels.end());

    for (const int32_t level : s.shoreLevels) {
        s.shoreRing.clear();
        for (size_t k = 0; k < s.shoreFrontier.size(); ++k) {
            if (s.shoreFrontierLevel[k] == level) {
                s.shoreRing.push_back(s.shoreFrontier[k]);
            }
        }
        /* Ring `step` holds the columns exactly `step` from the mask, so the
         * loop bound IS the reach cap. */
        for (int step = 0; step < reach && !s.shoreRing.empty(); ++step) {
            s.shoreNextRing.clear();
            for (const int32_t ci : s.shoreRing) {
                const int cx = ci / W;
                const int cz = ci % W;
                const auto claim = [&](int nx, int nz) {
                    if (nx < lo || nx >= hi || nz < lo || nz >= hi) {
                        return;
                    }
                    const size_t ni = idx2(nx, nz, W);
                    if (s.lakeWater[ni] >= 0) {
                        return;
                    }
                    const int h = std::clamp<int>(heights[ni], 1, world_height - 1);
                    if (h >= level || level - h > maxDepth) {
                        return;
                    }
                    /* And the fill's own answer about which ground belongs to
                     * this water: the cell's filled surface must stand at the
                     * level or above it.
                     *
                     * The depth cap alone does not bound an ESCAPE. A notch
                     * through the rim, five blocks wide and too narrow for a
                     * 16x16 mean to notice, is shallow where it leaves the
                     * lake and only deepens at the gradient of the ground
                     * outside — so a cap on depth lets the flood walk tens of
                     * blocks down the outside of the basin before it bites.
                     * `filled` says which side of the spill a cell is on, which
                     * is the question actually being asked, and it says it from
                     * the fill's window rather than from this tile's. Inside
                     * the basin `filled` IS the level; on the shore ring it is
                     * the ground, which is why that ring stays reachable; one
                     * cell past the spill it is already below, which is where
                     * this stops. Repair a shoreline, never discover a basin. */
                    const int cellI = std::clamp(nx / dem.cellBlocks, 0, dem.cells - 1);
                    const int cellJ = std::clamp(nz / dem.cellBlocks, 0, dem.cells - 1);
                    if (std::lround(dem.filled[idx2(cellI, cellJ, dem.cells)]) < level) {
                        return;
                    }
                    s.lakeWater[ni] = static_cast<int16_t>(level);
                    s.shoreNextRing.push_back(static_cast<int32_t>(ni));
                };
                claim(cx - 1, cz);
                claim(cx + 1, cz);
                claim(cx, cz - 1);
                claim(cx, cz + 1);
            }
            s.shoreRing.swap(s.shoreNextRing);
        }
    }
}

/**
 * Fill `s.rail` over `[lo, hi)^2` of the window: for every wet column, the
 * highest `s.water` of any wet column at most `reach` 4-steps away through wet
 * columns, and at most `RIVER_GUARD_LIFT` over its own water; -1 for dry. See
 * the header's "The guard rail".
 *
 * A max-dilation that only ever conducts through water, so a rail cannot cross
 * a ridge to a different river, and a column with nothing higher within reach
 * keeps its own level — the rail adds nothing to a still lake or a flat reach.
 *
 * Order-independence, for the seam rule, is `talusSkirt`'s argument: a round
 * expands only the ring fixed at its start, with the values captured at that
 * moment, so a level travels exactly one step per round wherever it is
 * processed from. Only columns with a lower wet neighbour are seeded — a level
 * only ever flows downhill, and a column with none raises nothing.
 *
 * Seam-safety: after `reach` rounds every value is from within `reach` steps,
 * so a column's rail is exact when `[lo, hi)` extends `reach` past it. The
 * caller sizes the stamp range for that and reads rails only inside it.
 */
void guardRail(int W, int lo, int hi, int reach, Scratch& s) {
    const size_t N = static_cast<size_t>(W) * static_cast<size_t>(W);
    s.rail.assign(s.water.begin(), s.water.begin() + static_cast<std::ptrdiff_t>(N));
    if (reach <= 0) {
        return;
    }
    const auto stride = static_cast<size_t>(W);
    s.railRing.clear();
    s.railRingVal.clear();
    for (int x = lo; x < hi; ++x) {
        for (int z = lo; z < hi; ++z) {
            const size_t i = idx2(x, z, W);
            const int16_t w = s.water[i];
            if (w < 0) {
                continue;
            }
            const bool lowerNeighbour =
                (x > lo && s.water[i - stride] >= 0 && s.water[i - stride] < w)
                || (x + 1 < hi && s.water[i + stride] >= 0 && s.water[i + stride] < w)
                || (z > lo && s.water[i - 1] >= 0 && s.water[i - 1] < w)
                || (z + 1 < hi && s.water[i + 1] >= 0 && s.water[i + 1] < w);
            if (lowerNeighbour) {
                s.railRing.push_back(static_cast<int32_t>(i));
                s.railRingVal.push_back(w);
            }
        }
    }
    for (int step = 0; step < reach && !s.railRing.empty(); ++step) {
        s.railNextRing.clear();
        s.railNextRingVal.clear();
        for (size_t k = 0; k < s.railRing.size(); ++k) {
            const int32_t ci = s.railRing[k];
            const int16_t cv = s.railRingVal[k];
            const int cx = ci / W;
            const int cz = ci % W;
            const auto relax = [&](int nx, int nz) {
                if (nx < lo || nx >= hi || nz < lo || nz >= hi) {
                    return;
                }
                const size_t ni = idx2(nx, nz, W);
                if (s.water[ni] < 0 || s.rail[ni] >= cv) {
                    return;
                }
                s.rail[ni] = cv;
                s.railNextRing.push_back(static_cast<int32_t>(ni));
                s.railNextRingVal.push_back(cv);
            };
            relax(cx - 1, cz);
            relax(cx + 1, cz);
            relax(cx, cz - 1);
            relax(cx, cz + 1);
        }
        s.railRing.swap(s.railNextRing);
        s.railRingVal.swap(s.railNextRingVal);
    }

    /* The lift, applied after the dilation rather than inside it: a column
     * passes the level on UNCAPPED and keeps only what it may itself stand
     * under, so the cap shortens walls without shortening the reach behind
     * them. Per column, from two canonical fields, so it is as seam-safe as
     * the dilation it trims. */
    for (int x = lo; x < hi; ++x) {
        for (int z = lo; z < hi; ++z) {
            const size_t i = idx2(x, z, W);
            if (s.water[i] >= 0) {
                s.rail[i] = std::min<int16_t>(
                    s.rail[i], static_cast<int16_t>(s.water[i] + RIVER_GUARD_LIFT));
            }
        }
    }
}

/**
 * Fill `s.bank` over `[lo, hi)^2` of the window: a raise-only talus skirt
 * falling away from every containment wall, in Q8 blocks, 0 where none reaches.
 *
 * §3 below raises any dry column beside water flush to the waterline, because
 * the WaterSim invariant leaves it no choice. What it leaves behind is a
 * one-column vertical cliff on the dry side — a retaining wall around every
 * lake rim, river bank and waterfall lip the shore flood could not wet. The
 * flood shrank that set; it cannot empty it, because the spill lip of a lake
 * with no outlet HAS to be walled.
 *
 * So the wall stands and the ground beside it is graded instead. `slope` is the
 * repose angle and the skirt terminates where it meets the terrain that was
 * already there, so a one-block wall gets a four-block ramp and a six-block
 * wall a twenty-four-block one, with no per-wall bookkeeping.
 *
 * What this pass builds is only the LINEAR potential — crest minus slope times
 * path distance — plus the crest it descends from, which together give the
 * distance back. The profile actually written (a noisy level lip, then a noisy
 * ramp) is §3's, because it is a per-column function of that distance and of
 * nothing else; see `bankProfile`.
 *
 * RAISE-ONLY, and not by preference. The crest cannot come down without
 * springing the water, and nothing in this kernel lowers terrain any more (the
 * header says why the valley pull is gone), so a monotone ramp from the crest
 * out to natural ground is the only shape available. `V <= crest <= waterline`
 * everywhere, so the skirt never stands above the water it banks.
 *
 * WET COLUMNS ARE BARRIERS: never seeded, never written, and they donate
 * nothing. That one rule is what keeps the skirt from raising a lake bed,
 * filling a channel, or damming the outlet river of the very lake it is
 * banking. It also truncates a skirt where a river tunnels under it, which is
 * a pure function of the world column — seam-safe, if occasionally visible.
 *
 * Order-independence, which the seam rule needs: a round expands only from the
 * ring fixed at its start and reads each parent's value from `bankRingVal`,
 * captured at the same moment, so a round is a pure function of the previous
 * round's state. Within a round two parents may reach one column in either
 * order; the strict improvement test keeps the larger value regardless, and an
 * improvement always re-enqueues, so no maximum is lost. A column enqueued
 * twice in one round expands twice, and the second expansion dominates the
 * first at every neighbour, so the duplicate costs work and changes nothing.
 *
 * Seam-safety, which is the reason for `reach`: after `step` rounds a column is
 * at most `step` edges from a seed, hence at most `step` columns away on each
 * axis. `[lo, hi)` is the stamp range, the caller clamps `reach` so that range
 * plus the shore flood's own reach fits the window, and every path is therefore
 * inside the window that stamps it.
 */
/**
 * The octant of (dx, dz) a reach runs toward: 0 = +x, then counter-clockwise
 * through +z in eighths of a turn, so 8 directions at 45 degrees.
 *
 * Eight is what the renderer can use and what a water surface can show. The
 * quantisation is done HERE rather than on the Java side because the direction
 * is a property of the route, and the route is only in scope in this file.
 */
inline int8_t riverOctant(float dx, float dz) {
    /* [-pi, pi] -> [0, 8), rounded to the nearest octant and wrapped. */
    const float turns = std::atan2(dz, dx) * (4.0f / 3.14159265358979323846f);
    int oct = static_cast<int>(std::lround(turns)) & 7;
    return static_cast<int8_t>(oct);
}

/**
 * How close this column is to being a tunnel MOUTH, in [0, 1].
 *
 * Measured on the lid the column can carry — `(raw - tunnel_min_roof) - surf`,
 * the same quantity the tunnel/open decision is made on — so it reaches 1
 * exactly where the passage would otherwise pinch out into daylight and falls
 * to 0 once there is `PORTAL_CLEARANCE` of rock overhead. No neighbourhood
 * scan, no route-order state: a pure function of this column's own ground and
 * its own water level, which is what keeps it identical from every tile.
 */
inline float portalNearness(int raw, int surf, int tunnelMinRoof) {
    const auto lid = static_cast<float>(raw - tunnelMinRoof - surf);
    if (lid <= 0.0f) {
        return 1.0f;
    }
    if (lid >= PORTAL_CLEARANCE) {
        return 0.0f;
    }
    return 1.0f - lid / PORTAL_CLEARANCE;
}

/**
 * Snap a height onto the nearest BED, so an exposed face steps instead of
 * presenting one flat slab.
 *
 * Rock does not erode into a smooth ramp. It erodes into beds: a hard layer
 * stands out as a tread and the soft one above it retreats, which is why every
 * real cliff reads as a stack of steps. That is a shape, not a material, so it
 * costs nothing here to have — the block loop keeps choosing blocks by biome.
 *
 * `t` is the height in beds; `f` is where it falls within its own bed. The
 * first and last `STRATA_TREAD` of a bed flatten to the bed's floor and
 * ceiling and the middle carries the whole rise, so a ramp that climbs one bed
 * comes out as tread, riser, tread. MONOTONE in `y` by construction (`f` is
 * clamped, never reversed), which is what lets callers keep the bounds they
 * had: the result never moves more than `STRATA_TREAD * STRATA_BAND` from `y`.
 *
 * A pure function of the WORLD column and height, like every other shape term
 * in this file, so two tiles sharing a face carve the same ledges into it.
 */
inline float strataStep(int64_t seed, int64_t wx, int64_t wz, float y) {
    const float phase = STRATA_PHASE
        * smoothNoise01(seed, wx, wz, STRATA_PHASE_WAVE, SALT_STRATA);
    const float t = (y - phase) / STRATA_BAND;
    const float bed = std::floor(t);
    const float f = t - bed;
    const float rise = 1.0f - 2.0f * STRATA_TREAD;
    const float shaped = f <= STRATA_TREAD
        ? 0.0f
        : (f >= 1.0f - STRATA_TREAD ? 1.0f : (f - STRATA_TREAD) / rise);
    return (bed + shaped) * STRATA_BAND + phase;
}

/**
 * The bank skirt: grade the ground away from every wall §3 raises, at a repose
 * angle, out to wherever the ramp meets the terrain that was already there.
 *
 * ── A plunge is not graded ──
 *
 * `s.plunge` is a hard barrier here, exactly like a wet column, and for the
 * opposite reason to the usual one. The skirt exists to stop a bank reading as
 * a CLIFF — but at a waterfall a cliff is the point, and a twelve-block repose
 * terrace laid across the drop is a mound of stone in front of the falling
 * water. §3's containment raise is untouched by this: the crest the invariant
 * demands still stands, it simply stops being the head of a cone.
 *
 * Safe by construction, not by measurement: the skirt only ever ADDS ground
 * (`h = max(h, want)` in the dry branch at emission), so declining to lay it
 * cannot spring water. Nothing else consults `s.bank`.
 */
void talusSkirt(const int16_t* heights, int W, int world_height,
                int lo, int hi, int reach, int axialCost, int diagCost,
                int32_t slack, Scratch& s) {
    const size_t N = static_cast<size_t>(W) * static_cast<size_t>(W);
    s.bank.assign(N, 0);
    s.bankCrest.assign(N, 0);
    if (reach <= 0 || axialCost <= 0) {
        return;
    }

    /* 1. Seed from the walls, by exactly the test §3 raises on — one place
     *    decides what a wall is, and the skirt is its consequence. The ring
     *    inside `[lo, hi)` is skipped because its neighbours' water lies
     *    outside the stamped range; a wall there is further from the center
     *    tile than `reach`, so it could not have reached it anyway. */
    const auto stride = static_cast<size_t>(W);
    s.bankRing.clear();
    s.bankRingVal.clear();
    s.bankRingCrest.clear();
    for (int x = lo + 1; x < hi - 1; ++x) {
        for (int z = lo + 1; z < hi - 1; ++z) {
            const size_t i = idx2(x, z, W);
            if (s.water[i] >= 0 || s.plunge[i] != 0) {
                continue;
            }
            int need = -1;
            need = std::max<int>(need, s.rail[i - stride]);
            need = std::max<int>(need, s.rail[i + stride]);
            need = std::max<int>(need, s.rail[i - 1]);
            need = std::max<int>(need, s.rail[i + 1]);
            if (std::clamp<int>(heights[i], 1, world_height - 1) >= need) {
                continue;
            }
            const int32_t crest = std::min(need, world_height - 1) << 8;
            s.bank[i] = crest;
            s.bankCrest[i] = crest;
            s.bankRing.push_back(static_cast<int32_t>(i));
            s.bankRingVal.push_back(crest);
            s.bankRingCrest.push_back(crest);
        }
    }

    /* 2. Relax outward. Ring `step` holds columns at most `step` edges from a
     *    wall, so the loop bound IS the reach cap. */
    for (int step = 0; step < reach && !s.bankRing.empty(); ++step) {
        s.bankNextRing.clear();
        s.bankNextRingVal.clear();
        s.bankNextRingCrest.clear();
        for (size_t k = 0; k < s.bankRing.size(); ++k) {
            const int32_t ci = s.bankRing[k];
            const int32_t cv = s.bankRingVal[k];
            const int32_t cc = s.bankRingCrest[k];
            const int cx = ci / W;
            const int cz = ci % W;
            const auto relax = [&](int nx, int nz, int cost) {
                if (nx < lo || nx >= hi || nz < lo || nz >= hi) {
                    return;
                }
                const size_t ni = idx2(nx, nz, W);
                if (s.water[ni] >= 0 || s.plunge[ni] != 0) {
                    return;
                }
                const int32_t v = cv - cost;
                /* Lexicographic on (value, crest): equal values from walls of
                 * different heights resolve to the taller one whichever parent
                 * arrives first, so the crest — and with it the distance the
                 * emission profile reads — is order-independent too. */
                if (s.bank[ni] > v || (s.bank[ni] == v && s.bankCrest[ni] >= cc)) {
                    return;
                }
                /* Met the ground: this column needs no help, and neither does
                 * anything behind it — a ramp that has run into a hillside
                 * stops there rather than climbing over it and resuming on the
                 * far side, which is what makes the reach geodesic instead of
                 * a radius. `slack` is the most §3's noisy profile can stand
                 * above this linear value; stopping without it would cut the
                 * lip short wherever the ground is within a block of it. */
                const int32_t ground =
                    static_cast<int32_t>(std::clamp<int>(heights[ni], 1, world_height - 1)) << 8;
                if (v + slack <= ground) {
                    return;
                }
                s.bank[ni] = v;
                s.bankCrest[ni] = cc;
                s.bankNextRing.push_back(static_cast<int32_t>(ni));
                s.bankNextRingVal.push_back(v);
                s.bankNextRingCrest.push_back(cc);
            };
            relax(cx - 1, cz, axialCost);
            relax(cx + 1, cz, axialCost);
            relax(cx, cz - 1, axialCost);
            relax(cx, cz + 1, axialCost);
            relax(cx - 1, cz - 1, diagCost);
            relax(cx - 1, cz + 1, diagCost);
            relax(cx + 1, cz - 1, diagCost);
            relax(cx + 1, cz + 1, diagCost);
        }
        s.bankRing.swap(s.bankNextRing);
        s.bankRingVal.swap(s.bankNextRingVal);
        s.bankRingCrest.swap(s.bankNextRingCrest);
    }
}

/**
 * The height §2b's skirt asks for at one dry column, from the linear potential
 * `bankQ8` and the crest `crestQ8` it descends from (both Q8, as `talusSkirt`
 * leaves them), at world column `(wx, wz)`.
 *
 * `(crest - bank) / axialCost` is the path distance to the wall in columns.
 * The crest holds level for a noisy 0 to BANK_PLATEAU_MAX of it — so the lip,
 * wall column included, is one to three thick and varies along the bank — and
 * then falls at `slope` to `slope * (1 + BANK_SLOPE_JITTER)` with BANK_ROUGH of
 * roughness faded in over the first column. The lip is only the plateau: the
 * first column past it is at least a block down. Never above the crest, which
 * is the waterline; the caller takes the max with the ground, so never below.
 *
 * Every term is `smoothNoise01` of the world column, and `talusSkirt`'s pair
 * is canonical, so this is too.
 */
inline int bankProfile(int64_t seed, int64_t wx, int64_t wz,
                       int32_t bankQ8, int32_t crestQ8, int axialCost, float slope) {
    const float d = static_cast<float>(crestQ8 - bankQ8) / static_cast<float>(axialCost);
    /* Stretched: bilinear value noise piles up around one half and almost
     * never reaches its ends, so unstretched the lip was two thick nearly
     * everywhere and three thick nowhere. */
    const float n = smoothNoise01(seed, wx, wz, BANK_PLATEAU_WAVE, SALT_BANK_PLATEAU);
    const float plateau = BANK_PLATEAU_MAX * std::clamp((n - 0.25f) * 2.0f, 0.0f, 1.0f);
    const float dEff = std::max(0.0f, d - plateau);
    const int crest = crestQ8 >> 8;
    if (dEff <= 0.0f) {
        return crest;
    }
    const float steep = slope
        * (1.0f + BANK_SLOPE_JITTER * smoothNoise01(seed, wx, wz, BANK_SLOPE_WAVE, SALT_BANK_SLOPE));
    const float rough = (2.0f * smoothNoise01(seed, wx, wz, BANK_ROUGH_WAVE, SALT_BANK_ROUGH) - 1.0f)
        * BANK_ROUGH * std::min(1.0f, dEff);
    /* The lobes. Faded in over the first column past the plateau like `rough`,
     * so the lip keeps the thickness the noise above it chose. */
    const float toe = (2.0f * smoothNoise01(seed, wx, wz, BANK_TOE_WAVE, SALT_BANK_TOE) - 1.0f)
        * BANK_TOE_ROUGH * std::min(1.0f, dEff);
    const float ramp = static_cast<float>(crestQ8) / 256.0f - steep * dEff + rough + toe;
    /* Bedded, not sanded smooth. Applied to the ramp only: the crest is the
     * containment level and the plateau above returns before this. */
    const float v = strataStep(seed, wx, wz, ramp);
    /* Past the plateau is past the lip, by definition: roughness may lump the
     * ramp but must not stretch the lip a column further than the noise said. */
    return std::min(crest - 1, static_cast<int>(std::floor(v)));
}

constexpr int BUCKET = 16;

/** Register a segment list into buckets, each expanded by its own reach. */
inline void bucketSegments(const std::vector<Seg>& segs, float pad, int nb, int W,
                           std::vector<std::vector<int32_t>>& out) {
    (void)W;
    out.assign(static_cast<size_t>(nb) * static_cast<size_t>(nb), {});
    for (size_t i = 0; i < segs.size(); ++i) {
        const Seg& g = segs[i];
        const float reach = pad + std::max(g.aHalf, g.bHalf) + 1.0f;
        const int x0 = std::max(0,
            static_cast<int>(std::floor((std::min(g.ax, g.bx) - reach) / BUCKET)));
        const int x1 = std::min(nb - 1,
            static_cast<int>(std::floor((std::max(g.ax, g.bx) + reach) / BUCKET)));
        const int z0 = std::max(0,
            static_cast<int>(std::floor((std::min(g.az, g.bz) - reach) / BUCKET)));
        const int z1 = std::min(nb - 1,
            static_cast<int>(std::floor((std::max(g.az, g.bz) + reach) / BUCKET)));
        for (int bx = x0; bx <= x1; ++bx) {
            for (int bz = z0; bz <= z1; ++bz) {
                out[static_cast<size_t>(bx) * static_cast<size_t>(nb)
                    + static_cast<size_t>(bz)].push_back(static_cast<int32_t>(i));
            }
        }
    }
}

} // namespace

extern "C" {

int32_t ck_carve_water(int64_t seed,
                       int32_t tile_size,
                       int32_t origin_x, int32_t origin_z,
                       const int16_t* heights3x3,
                       int32_t sea_level, int32_t world_height,
                       int32_t dem_cells, int32_t dem_cell_blocks,
                       const float* dem_filled, const float* dem_depth,
                       int32_t n_routes, const int32_t* route_starts,
                       const float* vertices,
                       const float* params, int32_t n_params,
                       int16_t* out_heights, int16_t* out_water,
                       int16_t* out_river_floor, int16_t* out_river_roof,
                       int16_t* out_river_flow) {
    /* Read only by the wall noise (the header's "Noise on the walls"). The
     * water itself still has no hashed mechanism of its own: the DEM span and
     * the routes are addressed in world coordinates and everything else is a
     * comparison, so the plan remains its only source. */
    if (heights3x3 == nullptr || out_heights == nullptr || out_water == nullptr) {
        return -1;
    }
    if (tile_size < 64 || tile_size > 4096) {
        return -2;
    }
    if (world_height < 64 || sea_level < 1 || sea_level >= world_height) {
        return -3;
    }
    const int T = tile_size;
    const int W = 3 * T;
    const size_t N = static_cast<size_t>(W) * static_cast<size_t>(W);

    /* The shared water params array (kernels.h documents it). The carve reads
     * exactly NINE of its entries — [12], [24]-[27], [29]-[31] and [35]; the
     * rest belong to the plan, and sea level arrives as its own argument rather
     * than [2].
     *
     * Each is one idea in one slot, and this is the only place any of them is
     * consumed. `bank_tolerance` and `valley_radius` used to live at [10] and
     * [14] and were read here; the pull they shaped is gone, so they are gone
     * with it rather than left as knobs that look live.
     *
     * [12] is the plan's own `waterfall_min_drop` and is read here rather than
     * duplicated: the carve has to tell a real PLUNGE from an ordinary pool
     * boundary (with pooling on, every one of those carries the waterfall flag
     * too), and a second threshold that had to agree with that one would be a
     * second place for it to drift. */
    float waterfallMinDropF = DEF_WATERFALL_MIN_DROP;
    float tunnelHeadroom = DEF_TUNNEL_HEADROOM;
    float tunnelMinRoofF = DEF_TUNNEL_MIN_ROOF;
    float tunnelMinAirF = DEF_TUNNEL_MIN_AIR;
    float shoreReachF = DEF_LAKE_SHORE_REACH;
    float shoreMaxDepthF = DEF_LAKE_SHORE_MAX_DEPTH;
    float bankSlopeF = DEF_LAKE_BANK_SLOPE;
    float bankReachF = DEF_LAKE_BANK_REACH;
    float guardReachF = DEF_RIVER_GUARD_REACH;
    if (params != nullptr) {
        if (n_params > 12) waterfallMinDropF = params[12];
        if (n_params > 24) tunnelHeadroom = params[24];
        if (n_params > 25) tunnelMinRoofF = params[25];
        if (n_params > 26) shoreReachF = params[26];
        if (n_params > 27) shoreMaxDepthF = params[27];
        if (n_params > 29) bankSlopeF = params[29];
        if (n_params > 30) bankReachF = params[30];
        if (n_params > 31) guardReachF = params[31];
        if (n_params > 35) tunnelMinAirF = params[35];
    }
    tunnelHeadroom = std::clamp(tunnelHeadroom, 0.0f, static_cast<float>(world_height));
    /* At least one block of lid: a roof flush with the surface is not a roof,
     * and it would let the void breach the ground it is supposed to run under. */
    const int tunnelMinRoof =
        std::max(1, static_cast<int>(std::lround(tunnelMinRoofF)));
    /* Zero is legal and means "the arch is the only thing holding the roof up",
     * which is what this kernel did before the floor existed. */
    const int tunnelMinAir = std::clamp(
        static_cast<int>(std::lround(tunnelMinAirF)), 0, world_height);
    const float waterfallMinDrop = std::max(0.0f, waterfallMinDropF);

    /* The DEM span is optional: without it the tile gets sea-level-only water,
     * which is the same graceful degradation as an absent kernels library. It
     * must cover the window exactly, because the whole point of this kernel is
     * that it invents nothing — a partial span would silently drop lakes near
     * one edge and the tile next door would disagree. */
    Dem dem{nullptr, nullptr, 0, 0};
    const bool haveDem = dem_filled != nullptr && dem_depth != nullptr && dem_cells > 0;
    if (haveDem) {
        if (dem_cell_blocks <= 0 || dem_cells * dem_cell_blocks != W) {
            return -4;
        }
        dem = Dem{dem_filled, dem_depth, dem_cells, dem_cell_blocks};
    }

    /* Clamped, not rejected: these arrive from outside, and a silly value
     * should cost a plain shoreline rather than a seam. `T - 1` is the margin
     * every center-tile column has inside its own window, and it is the whole
     * of the seam argument in `floodLakeShore` — a reach past it lets one tile
     * read ground its neighbour cannot, and the two draw different shores. */
    const int shoreReach =
        std::clamp(static_cast<int>(std::lround(shoreReachF)), 0, T - 1);
    const int shoreMaxDepth =
        std::clamp(static_cast<int>(std::lround(shoreMaxDepthF)), 0, world_height);
    /* What is left of that margin after the shore flood has taken its share.
     * The two reaches are spent out of one budget — `1 + shoreReach + bankReach
     * <= T` — and this is the only place that budget exists, which is why the
     * stamp range below is derived from `bankReach` and the flood's range from
     * the stamp range, rather than all three restating `T`. */
    const int bankReach = std::clamp(static_cast<int>(std::lround(bankReachF)),
                                     0, std::max(0, T - 1 - shoreReach));
    /* And what the skirt left, for the rail it seeds from. */
    const int guardReach = std::clamp(static_cast<int>(std::lround(guardReachF)),
                                      0, std::max(0, T - 1 - shoreReach - bankReach));
    /* Q8 edge costs. The diagonal is the axial one times root two, so the ramp
     * falls at the same rate in every direction to within the eight per cent a
     * square lattice can express. */
    const float bankSlope = std::clamp(bankSlopeF, 0.0f, static_cast<float>(world_height));
    const int bankAxialCost =
        std::max(1, static_cast<int>(std::lround(bankSlope * 256.0f)));
    const int bankDiagCost =
        std::max(1, static_cast<int>(std::lround(bankSlope * 256.0f * 1.41421356f)));
    /* The most `bankProfile` can stand above the linear potential: a full
     * plateau's worth of fall it skipped, plus every term that can lift the
     * ramp, plus one for the floor. `talusSkirt` keeps relaxing until even that
     * is under the ground.
     *
     * EVERY term. This is not bookkeeping — it is the seam rule. The relax
     * stops where `v + slack <= ground`, so a profile that can stand higher
     * than the slack admits makes the stop depend on which window asked, and
     * two tiles then draw different banks along their shared edge. Adding a
     * noise term to `bankProfile` without adding it here is exactly how that
     * breaks (and did, the first time `toe` and `strataStep` went in).
     *
     * `strataStep` can lift a height by at most one tread — see its monotone
     * bound — and lowers by no more, which costs nothing here. */
    const int32_t bankSlack = static_cast<int32_t>(std::lround(BANK_PLATEAU_MAX * static_cast<float>(bankAxialCost)))
        + static_cast<int32_t>(std::lround(
            (BANK_ROUGH + BANK_TOE_ROUGH + STRATA_TREAD * STRATA_BAND) * 256.0f))
        + 256;

    /* The stamp's range: the center tile, the one-column ring §3 reads past it,
     * and whatever the skirt needs beyond that. A wall `bankReach` columns
     * outside the tile can still grade INTO it, so its own water has to be
     * stamped or the skirt a tile draws would depend on which tile drew it —
     * a straight, tile-aligned ridge, which is the artifact class §1a exists to
     * kill. §2 restates the cost this buys. */
    const int bankLo = std::max(0, (T - 1) - bankReach);
    const int bankHi = std::min(W, (2 * T + 1) + bankReach);
    /* ...and the rail every wall in that range reads has to have seen the
     * water `guardReach` beyond it, so that water is stamped too. §2c's rails
     * are exact only inside `[bankLo, bankHi)`, which is all anything reads. */
    const int stampLo = std::max(0, bankLo - guardReach);
    const int stampHi = std::min(W, bankHi + guardReach);

    Scratch& s = tls;
    s.carved.resize(N);
    s.water.assign(N, -1);
    s.floor.assign(N, -1);
    s.roof.assign(N, -1);
    s.flow.assign(N, -1);
    s.plunge.assign(N, 0);

    /* ── 1a. Where the lakes are, at block resolution ──
     *
     * Grown by the reach on each side of §2's stamp range, because a claim
     * travels at most that far: the columns §2 stamps then have every path that
     * could reach them inside this domain, and no column §2 writes depends on
     * ground the domain cut off. The two bounds move together or not at all —
     * literally, now: they are `stampLo`/`stampHi` grown by the reach, so there
     * is one range to widen and not two to keep in step. */
    if (haveDem) {
        const int floodLo = std::max(0, stampLo - shoreReach);
        const int floodHi = std::min(W, stampHi + shoreReach);
        floodLakeShore(dem, heights3x3, W, world_height,
                       floodLo, floodHi, shoreReach, shoreMaxDepth, s);
    } else {
        s.lakeWater.assign(N, -1);
    }

    /* ── 1. Build the river segments in window coordinates ──
     * Clipped to what can reach the window: a segment further than its own
     * half-width from it changes nothing here, and the region that owns it
     * stamps its own ground anyway. */
    s.channel.clear();
    const bool haveRivers = vertices != nullptr && route_starts != nullptr && n_routes > 0;
    if (haveRivers) {
        /* Build one Seg from two packed vertices, in window coordinates.
         *
         * Clipped by the segment's OWN half-width — a channel is a few blocks
         * wide, so a reach further than that from the window changes nothing
         * here. It used to be clipped by the valley radius, which was the pull's
         * reach and not the channel's; with the pull gone that bound was both
         * wrong and eighty blocks too generous. */
        const auto makeSeg = [&](const float* a, const float* b, Seg& g) {
            g.ax = a[0] - static_cast<float>(origin_x);
            g.az = a[1] - static_cast<float>(origin_z);
            g.bx = b[0] - static_cast<float>(origin_x);
            g.bz = b[1] - static_cast<float>(origin_z);
            const float reach = std::max(std::max(a[3], b[3]), 1.0f) * 0.5f + 1.0f
                + STAMP_PAD;
            const float lo = -reach;
            const float hi = static_cast<float>(W) + reach;
            if (std::max(g.ax, g.bx) < lo || std::min(g.ax, g.bx) > hi
                    || std::max(g.az, g.bz) < lo || std::min(g.az, g.bz) > hi) {
                return false;
            }
            g.aSurf = a[2];
            g.bSurf = b[2];
            g.aHalf = std::max(a[3], 1.0f) * 0.5f;
            g.bHalf = std::max(b[3], 1.0f) * 0.5f;
            g.aBed = a[4];
            g.bBed = b[4];
            const auto flags = static_cast<int32_t>(b[6]);
            g.falls = (flags & CK_RIVER_FLAG_WATERFALL) != 0;
            g.gorge = (flags & CK_RIVER_FLAG_GORGE) != 0;
            if (g.gorge) {
                /* §5.6: a gorge is a narrow channel with walls, not a valley.
                 * Narrowing the channel is all this flag does now — the valley
                 * pull it used to suppress is gone for every reach, not just
                 * this one. */
                g.aHalf *= 0.75f;
                g.bHalf *= 0.75f;
            }
            const float segLen = std::hypot(g.bx - g.ax, g.bz - g.az);
            const float drop = g.aSurf - g.bSurf;
            g.shape = rosgenShape(segLen > 1e-3f ? drop / segLen : 0.0f);
            return true;
        };
        for (int32_t r = 0; r < n_routes; ++r) {
            const int32_t from = route_starts[r];
            const int32_t to = route_starts[r + 1];
            const auto at = [&](int32_t v) {
                return vertices + static_cast<size_t>(v) * CK_RIVER_VERTEX_FLOATS;
            };
            for (int32_t v = from; v + 1 < to; ++v) {
                Seg g{};
                if (makeSeg(at(v), at(v + 1), g)) {
                    s.channel.push_back(g);
                }
            }
        }
    }

    const int nb = (W + BUCKET - 1) / BUCKET;
    /* The pad has to cover everything that reaches past the channel edge —
     * the bulge, and the wider alcove a portal flares into — or a column that
     * one of them would open falls in a bucket the segment was never added to
     * and the void stops at a 16-block lattice line. */
    bucketSegments(s.channel, STAMP_PAD, nb, W, s.channelBuckets);

    /* ── 2. Stamp: lakes from the fill, rivers from the plan, then the sea ──
     *
     * Over the center tile and a MARGIN, not the whole window. The window
     * exists so this pass has its INPUTS — a route sourced next door crosses
     * the border, and the DEM stencil straddles cells — and both are read at
     * world coordinates that lie outside the stamped range without being
     * stamped themselves.
     *
     * Stamping the full window instead computed 9x the columns and discarded
     * eight ninths of them. Measured on a 256-block tile, 200 iterations:
     * lakes only 4.22 -> 0.555 ms, with two routes crossing 14.70 -> 3.30 ms,
     * with `out_heights`/`out_water` byte-identical either way.
     *
     * The bound is coupled to what reads `s.water` past the tile: §3's four
     * neighbors, and §2b's skirt, which reaches `lake_bank_reach` further. That
     * is why `stampLo`/`stampHi` are computed up with the other reaches instead
     * of here — widen a consumer and the range widens with it, in one place.
     * §3 alone wanted one column; the skirt wants `lake_bank_reach` more, and
     * at the default 32 that is a 322x322 pass against the 258x258 a ring-only
     * margin would take — measured 0.95 -> 1.33 ms per tile. That is the price
     * of a skirt that does not depend on which tile drew it: seed it from only
     * the water a tile already stamped and two tiles grade a shared wall
     * differently, which is a straight tile-aligned step in the ground. `s.water` is cleared to -1 over the whole
     * window below regardless, so the failure mode of getting the range wrong
     * is a missing wall rather than stale water from the previous tile on this
     * thread. */
    for (int x = stampLo; x < stampHi; ++x) {
        for (int z = stampLo; z < stampHi; ++z) {
            const size_t i = idx2(x, z, W);
            /* Terrain is NOT carved for a lake. A lake sits in a depression the
             * terrain already has — that is what the fill found — so there is
             * nothing to excavate, and the old excavator existed only because
             * nothing was finding depressions. A river cuts a bed where it runs
             * at grade, and where it does not, tunnels instead of levelling the
             * ground in its way. */
            const int raw = std::clamp<int>(heights3x3[i], 1, world_height - 1);
            int carved = raw;
            int water = -1;
            /* The tunnel, if any reach over this column turns out to need one.
             * Merged across reaches the same order-independent way everything
             * else here is: floor by min, roof by max. */
            int tunnelFloor = world_height;
            int tunnelRoof = -1;

            /* §1a already answered this, on the real block heights and by
             * connectivity. The height test is a no-op for anything it claimed
             * — nothing raises `carved`, only rivers lower it — and is kept
             * because it is the invariant the plane is built to satisfy. */
            {
                const int level = s.lakeWater[i];
                if (level > 0 && carved < level) {
                    water = level;
                }
            }

            /* Rivers touch a few per cent of a tile, so for almost every
             * column this one empty-bucket test is the entire river pass. */
            const size_t bi = idx2(std::min(x / BUCKET, nb - 1),
                                   std::min(z / BUCKET, nb - 1), nb);
            const float px = static_cast<float>(x) + 0.5f;
            const float pz = static_cast<float>(z) + 0.5f;
            const int64_t wx = origin_x + static_cast<int64_t>(x);
            const int64_t wz = origin_z + static_cast<int64_t>(z);
            /* The wall noise, sampled once and only for columns a channel
             * actually reaches — which is a few per cent of them. */
            bool noiseReady = false;
            float vaultScale = 1.0f;
            float bulgeWidth = 0.0f;
            /* The reach this column's water level came from, and which way it
             * runs. Chosen CANONICALLY — highest surface, then nearest, then
             * the segment's own endpoints lexicographically — so two tiles
             * that share the column pick the same reach whatever order their
             * buckets hand the segments over in. Anything order-dependent here
             * would make the flow direction flip along a tile seam. */
            int flowSurf = -1;
            float flowDist2 = 0.0f;
            float flowKey[4] = {0.0f, 0.0f, 0.0f, 0.0f};
            int8_t flowDir = -1;
            const auto sampleNoise = [&]() {
                if (noiseReady) {
                    return;
                }
                noiseReady = true;
                vaultScale = 1.0f
                    + TUNNEL_HEADROOM_JITTER
                        * (2.0f * smoothNoise01(seed, wx, wz, TUNNEL_HEADROOM_WAVE, SALT_TUNNEL_HEAD) - 1.0f)
                    + TUNNEL_ROUGH
                        * (2.0f * smoothNoise01(seed, wx, wz, TUNNEL_ROUGH_WAVE, SALT_TUNNEL_ROUGH) - 1.0f);
                bulgeWidth = TUNNEL_BULGE_MAX
                    * smoothNoise01(seed, wx, wz, TUNNEL_BULGE_WAVE, SALT_TUNNEL_BULGE);
            };
            /* A dry void just past the channel edge, above the waterline, held
             * apart from the tunnel planes until the column is known to stay
             * dry — another reach may yet wet it, and then its own planes win. */
            int bulgeFloor = -1;
            int bulgeRoof = -1;

            /* The channel, from every segment of the refined polyline: this is
             * the detail refinement exists for, and it is cheap because a
             * channel is a few blocks wide.
             *
             * Heights merge by min, water and roof by max, floor by min, so two
             * rivers meeting is order-independent and needs no confluence graph
             * (§5.9).
             *
             * TUNNEL vs OPEN is decided here, per column, against the FULL
             * RESOLUTION `raw` — which is the whole point of the change. `surf`
             * is a bilinear sample of a 16-block DEM, so it says nothing on its
             * own about the block-scale ground this kernel writes. */
            for (int32_t si : s.channelBuckets[bi]) {
                const Seg& g = s.channel[static_cast<size_t>(si)];
                float t = 0.0f;
                const float d2 = segDistanceSq(g, px, pz, t);
                const float half = g.aHalf + (g.bHalf - g.aHalf) * t;
                if (d2 >= (half + STAMP_PAD) * (half + STAMP_PAD)) {
                    continue;
                }
                /* A real PLUNGE holds §2b's talus off this column (see "A
                 * plunge is not graded"). Measured on the drop rather than
                 * taken from `g.falls`, because with pooling on every pool
                 * boundary carries that flag and a one-block riffle wants its
                 * skirt like any other bank. Marked over the whole falling
                 * segment and a little past the channel: the cone the mask is
                 * there to stop is laid on the walls beside the fall, not on
                 * the fall itself.
                 *
                 * Seam-safe for the same reason everything else here is — it
                 * is a pure function of the segment and the world column. Any
                 * column within `half + STAMP_PAD` of a segment is inside the
                 * clip of every window that holds the column, which is what
                 * widening the clip and the bucket pad together buys: two
                 * tiles sharing a column mark it identically, so the skirt's
                 * geodesic sees the same barriers from both sides. */
                if (g.falls && g.aSurf - g.bSurf >= waterfallMinDrop) {
                    s.plunge[i] = 1;
                }
                /* §5.8: a falling reach steps rather than ramps. The surface
                 * takes the upstream level for the upper half and the
                 * downstream one for the lower, so the drop is a cliff and
                 * nothing is carved to ease it — which is exactly the
                 * "unnecessary terrain to hold the river" being avoided. The
                 * containment invariant already calls wet-next-to-wet at
                 * differing levels a waterfall. */
                const float surfF = g.falls
                    ? (t < 0.5f ? g.aSurf : g.bSurf)
                    : g.aSurf + (g.bSurf - g.aSurf) * t;
                /* Rounded once per cross-section: every column whose nearest
                 * point is at this `t` shares the level, so a channel never
                 * steps sideways across its own width. */
                const int surf = static_cast<int>(std::lround(surfF));
                /* The WATER and the BED follow that step; the VOID must not.
                 * The reach above pours in at ITS level, so rock left between
                 * the two voids DAMS the fall — the downstream ceiling sat at
                 * `bSurf + arch` while the water arrives at `aSurf`, and for
                 * any drop taller than the arch the two are not even
                 * connected. So the roof, the lid test and the portal are all
                 * decided on the HIGHER of the segment's two levels and one
                 * shaft spans the drop. Away from a step this IS `surf` and
                 * nothing changes. */
                const int surfTop = g.falls
                    ? static_cast<int>(std::lround(std::max(g.aSurf, g.bSurf)))
                    : surf;
                if (d2 >= half * half) {
                    /* Past the channel: only the bulge lives here. It widens a
                     * tunnel's AIR, never its water — the floor is a stone lip
                     * at `surf - 1`, level with the top water block beside it,
                     * so everything opened is at or above the surface and the
                     * containment rule has nothing to hold. Same lid test as
                     * the tunnel itself, so it never breaches the ground. */
                    if (raw - tunnelMinRoof <= surf) {
                        continue;
                    }
                    sampleNoise();
                    /* At a mouth the bulge becomes the alcove: wider, taller,
                     * and roofed by a lid allowed to thin to PORTAL_MIN_ROOF.
                     * Ground is not touched here either — an alcove is rock
                     * removed from under an overhang, so the surface above it
                     * stands exactly where the terrain put it. */
                    const float portal = portalNearness(raw, surf, tunnelMinRoof);
                    const float width = std::max(bulgeWidth, PORTAL_FLARE * portal);
                    const float past = std::sqrt(d2) - half;
                    if (past >= width) {
                        continue;
                    }
                    const float rise = (TUNNEL_BULGE_RISE + PORTAL_RISE * portal)
                        * (1.0f - past / width);
                    const float lid = static_cast<float>(tunnelMinRoof)
                        - (static_cast<float>(tunnelMinRoof) - PORTAL_MIN_ROOF) * portal;
                    const int roofY = std::min(surf + 1 + static_cast<int>(std::lround(rise)),
                                               raw - static_cast<int>(std::lround(lid)));
                    /* Floor by MAX, unlike a wet tunnel's: the lip has to
                     * stand at the top of every reach's water that opens it. */
                    bulgeFloor = std::max(bulgeFloor, surf - 1);
                    bulgeRoof = std::max(bulgeRoof, roofY);
                    continue;
                }
                const float bed = g.aBed + (g.bBed - g.aBed) * t;
                /* `cut` is `bed * (1 - u^p)`, deepest at the centreline and
                 * meeting the bank at the half-width; `p` is the Rosgen shape. */
                const float u = std::sqrt(d2) / half;
                const float cut = bed * (1.0f - std::pow(u, g.shape));
                const int bedY = surf - std::max(1, static_cast<int>(std::lround(cut)));

                /* Tunnel or open is decided by the COLUMN, not by the roof: is
                 * there enough ground standing over this reach's water to make
                 * a lid out of AND carry the air the passage promises?
                 * Deciding it on the roof instead is a trap worth recording —
                 * the arch goes to zero at the channel edge, so every rim
                 * column fell to the open branch and was cut to the water line
                 * even with forty blocks of hill on it. That is the original
                 * defect, moved to the edge of the channel.
                 *
                 * `surfTop + tunnelMinAir`, not `surf`: a column that cannot
                 * roof the level pouring INTO it has no business roofing it at
                 * all, and the open branch below cuts it to the water so the
                 * fall breaks the surface instead of hitting rock. The budget
                 * that costs is bounded and declared —
                 * `tunnel_min_roof + tunnel_min_air + the bed`, plus the drop
                 * at a plunge — against the unbounded excavation this branch
                 * replaced. */
                if (raw - tunnelMinRoof > surfTop + tunnelMinAir) {
                    /* The ground stands and the river runs under it. `carved` is
                     * deliberately untouched: this is the whole point.
                     *
                     * The roof arches on the same `u` the bed is cut on, so the
                     * void narrows toward the channel edge and no column
                     * outside the half-width is opened BELOW the water — that,
                     * and not a containment rule, is what holds it in. It no
                     * longer pinches SHUT: `tunnelMinAir` holds the vault open
                     * the whole width, which costs nothing there because every
                     * column inside the half-width is the river's own.
                     * The clamp to `raw - tunnelMinRoof` keeps a lid on it, and
                     * `vaultScale` only reshapes the air: it rides on the same
                     * `1 - u²`, so it cannot move the edge. */
                    sampleNoise();
                    /* Taller at the mouth, and under a thinner lid, so the
                     * passage OPENS toward daylight instead of pinching out.
                     * Both fade to nothing `PORTAL_CLEARANCE` blocks of rock
                     * back from the opening, which is where a tunnel is just a
                     * tunnel again. */
                    const float portal = portalNearness(raw, surfTop, tunnelMinRoof);
                    const float arch = tunnelHeadroom * vaultScale
                        * (1.0f + PORTAL_RISE * portal) * (1.0f - u * u);
                    const float lid = static_cast<float>(tunnelMinRoof)
                        - (static_cast<float>(tunnelMinRoof) - PORTAL_MIN_ROOF) * portal;
                    /* `tunnelMinAir` is the floor the arch may not sink below,
                     * and it cannot breach the ground: the branch was entered
                     * on `raw - tunnelMinRoof > surfTop + tunnelMinAir`, and
                     * `lid <= tunnelMinRoof`, so `raw - lid` is strictly above
                     * it. It only ever opens AIR, and only inside the
                     * half-width — the "nothing outside the channel is opened
                     * below the water" argument above is untouched. */
                    const int roofY = std::min(
                        std::max(surfTop + static_cast<int>(std::lround(arch)),
                                 surfTop + tunnelMinAir),
                        raw - static_cast<int>(std::lround(lid)));
                    if (roofY > bedY) {
                        tunnelFloor = std::min(tunnelFloor, bedY);
                        tunnelRoof = std::max(tunnelRoof, roofY);
                    }
                } else {
                    carved = std::min(carved, bedY);
                }
                water = std::max(water, surf);

                /* ...and record which way it runs, by the canonical rule. */
                const float key[4] = {g.ax, g.az, g.bx, g.bz};
                bool better = surf > flowSurf;
                if (!better && surf == flowSurf) {
                    better = d2 < flowDist2;
                    if (!better && d2 == flowDist2) {
                        for (int k = 0; k < 4 && !better; ++k) {
                            if (key[k] != flowKey[k]) {
                                better = key[k] < flowKey[k];
                                break;
                            }
                        }
                    }
                }
                if (better) {
                    flowSurf = surf;
                    flowDist2 = d2;
                    for (int k = 0; k < 4; ++k) {
                        flowKey[k] = key[k];
                    }
                    flowDir = riverOctant(g.bx - g.ax, g.bz - g.az);
                }
            }

            carved = std::clamp(carved, 1, world_height - 1);
            /* The sea is one case of the per-column water level (bridge rule). */
            if (carved < sea_level) {
                water = std::max(water, sea_level);
            }

            /* A column can be both, where one reach tunnels over it and another
             * runs open across it — a tight meander, or a confluence. The open
             * reach lowered `carved`, so hold the roof a full lid under it: a
             * void at or above the ground would put water in mid-air, and a lid
             * thinner than the one promised is not a roof. Where only tunnelling
             * happened `carved == raw` and the roof is already under that, so
             * this changes nothing. */
            tunnelRoof = std::min(tunnelRoof, carved - tunnelMinRoof);
            /* The bulge only where the column stayed dry. A wet column has
             * planes of its own, and a lip at `surf - 1` inside water would be
             * a dam. `carved == raw` here — only a wet column is ever lowered —
             * so the bulge's lid clamp is still the one that holds. */
            if (water < 0 && bulgeRoof > bulgeFloor + 1) {
                tunnelFloor = bulgeFloor;
                tunnelRoof = bulgeRoof;
            }
            const bool hasTunnel = tunnelRoof > tunnelFloor && tunnelFloor >= 1;

            s.carved[i] = static_cast<int16_t>(carved);
            s.water[i] = static_cast<int16_t>(water);
            s.floor[i] = hasTunnel ? static_cast<int16_t>(tunnelFloor) : static_cast<int16_t>(-1);
            s.roof[i] = hasTunnel ? static_cast<int16_t>(tunnelRoof) : static_cast<int16_t>(-1);
            /* A column RUNS only where a channel set the level it ended up
             * with. A lake the river passes through, and any column the sea
             * raised afterwards, are flat water and carry no direction — which
             * falls straight out of comparing the winning reach's surface
             * against the level actually emitted. */
            s.flow[i] = (water >= 0 && flowSurf == water) ? flowDir : static_cast<int8_t>(-1);
        }
    }

    /* ── 2d. No river flows uphill ──
     *
     * A route's tangent is a real direction; an OCTANT is one of eight, and
     * the rounding is not free. On a reach running diagonally across a step,
     * the nearest octant can point at the column the river just came down
     * from — so the surface would scroll UPSTREAM, over the step, which is the
     * one thing a moving surface must never do. Measured before this pass: 48
     * of 16,492 running columns on the two river fixtures, almost all of them
     * a single one-block step on a diagonal reach.
     *
     * The rule is local and exact, and it is the same one the test asserts:
     * step one column along the emitted octant and the water there does not
     * stand higher. Where the tangent already satisfies it — 99.7 % of columns
     * — it is kept untouched, so this changes direction only where direction
     * was wrong. Otherwise the octant turns to the nearest one that does, in a
     * fixed order (+1, -1, +2, -2, ...) so the result is a pure function of
     * the column and stays identical from every tile that stamps it.
     *
     * Every one of those 48 had at least two octants to turn to, and no column
     * on either fixture runs out — but if one ever did, it would be a column
     * whose every neighbour stands over it, and the honest answer for it is
     * that its water is not going anywhere: it reports still. That keeps the
     * invariant absolute rather than best-effort, at the cost of one cell that
     * stops scrolling.
     *
     * Runs on `s.water`, which is complete over the stamp range by now, and
     * writes only `s.flow`, which nothing else reads — so it can sit before
     * the rail without disturbing anything it does.
     *
     * The one-column inset keeps the 8-neighbour reads inside the range, and it
     * costs the emitted tile nothing, which is worth stating so nobody widens
     * it back out: `stampLo <= bankLo <= T-1`, and `stampHi >= bankHi >= 2T+1`
     * unless the window itself ends first, so both skipped edges lie OUTSIDE
     * `[T, 2T)`. Every column this call actually emits is corrected; the two
     * columns that are not are guard-rail margin the output never reads. */
    {
        static constexpr int OCTANT_X[8] = {1, 1, 0, -1, -1, -1, 0, 1};
        static constexpr int OCTANT_Z[8] = {0, 1, 1, 1, 0, -1, -1, -1};
        /* Nearest first, and +k before -k so ties are decided the same way
         * everywhere. 4 is the about-face, and is reached only when nothing
         * else worked. */
        static constexpr int TURN[8] = {0, 1, -1, 2, -2, 3, -3, 4};
        for (int x = stampLo + 1; x < stampHi - 1; ++x) {
            for (int z = stampLo + 1; z < stampHi - 1; ++z) {
                const size_t i = idx2(x, z, W);
                const int8_t oct = s.flow[i];
                if (oct < 0) {
                    continue;
                }
                const int here = s.water[i];
                int8_t chosen = -1;
                for (const int turn : TURN) {
                    const int k = (oct + turn) & 7;
                    const size_t n = idx2(x + OCTANT_X[k], z + OCTANT_Z[k], W);
                    /* Dry ground cannot be flowed up into, so it qualifies —
                     * the rule is about water standing over water. */
                    if (s.water[n] <= here) {
                        chosen = static_cast<int8_t>(k);
                        break;
                    }
                }
                s.flow[i] = chosen;
            }
        }
    }

    /* ── 2c. The guard rail (see the header) ──
     *
     * Computed before §2a because §2a, §2b and §3 all wall to it. Numbered
     * after them because it was added after them. */
    guardRail(W, stampLo, stampHi, guardReach, s);

    /* ── 2a. Seat every bulge on the water beside it ──
     *
     * A bulge's lip was set from the reaches that opened it, but the water it
     * sits beside may be a lake, or a reach whose own bulge noise did not reach
     * this column. Air beside water below its surface is a spring, so the lip
     * rises to the highest 4-neighbour's top water block, and a bulge that
     * leaves no air above that is dropped. Only the neighbours' `s.water`, so
     * canonical; the ring inside the stamp range is all §3 emits from. */
    for (int x = bankLo + 1; x < bankHi - 1; ++x) {
        for (int z = bankLo + 1; z < bankHi - 1; ++z) {
            const size_t i = idx2(x, z, W);
            if (s.water[i] >= 0 || s.floor[i] < 0) {
                continue;
            }
            int need = -1;
            need = std::max<int>(need, s.rail[i - static_cast<size_t>(W)]);
            need = std::max<int>(need, s.rail[i + static_cast<size_t>(W)]);
            need = std::max<int>(need, s.rail[i - 1]);
            need = std::max<int>(need, s.rail[i + 1]);
            const int lip = std::max<int>(s.floor[i], need - 1);
            if (s.roof[i] > lip + 1) {
                s.floor[i] = static_cast<int16_t>(lip);
            } else {
                s.floor[i] = -1;
                s.roof[i] = -1;
            }
        }
    }

    /* ── 2b. Grade the ground away from every wall §3 is about to raise ── */
    talusSkirt(heights3x3, W, world_height, bankLo, bankHi,
               bankReach, bankAxialCost, bankDiagCost, bankSlack, s);

    /* ── 3. Containment repair + emission (center tile only) ──
     *
     * The four neighbor reads below reach one column outside the tile, which
     * is exactly what §2's stamp range is sized for. Reaching further — a
     * diagonal, a second ring — means widening `stampLo`/`stampHi` to match. */
    for (int x = 0; x < T; ++x) {
        for (int z = 0; z < T; ++z) {
            const size_t wi = idx2(x + T, z + T, W);
            int h = s.carved[wi];
            const int16_t w = s.water[wi];
            if (w < 0) {
                /* Dry ground beside water must wall it (raise, never wet —
                 * extending water would need re-checking ITS neighbors). */
                int need = -1;
                need = std::max<int>(need, s.rail[wi - static_cast<size_t>(W)]);
                need = std::max<int>(need, s.rail[wi + static_cast<size_t>(W)]);
                need = std::max<int>(need, s.rail[wi - 1]);
                need = std::max<int>(need, s.rail[wi + 1]);
                if (h < need) {
                    h = std::min(need, world_height - 1);
                }
                /* §2b's skirt, applied here rather than in the pass that built
                 * it so that "never raises a wet column" is structural: it is
                 * inside the dry branch and cannot reach anything else. */
                const int32_t skirt = s.bank[wi];
                if (skirt > 0) {
                    const int want = bankProfile(seed, origin_x + static_cast<int64_t>(x + T),
                                                 origin_z + static_cast<int64_t>(z + T),
                                                 skirt, s.bankCrest[wi], bankAxialCost, bankSlope);
                    h = std::max(h, std::min(want, world_height - 1));
                }
            }
            const size_t oi = idx2(x, z, T);
            out_heights[oi] = static_cast<int16_t>(h);
            out_water[oi] = w;
            if (out_river_floor != nullptr) {
                out_river_floor[oi] = s.floor[wi];
            }
            if (out_river_roof != nullptr) {
                out_river_roof[oi] = s.roof[wi];
            }
            if (out_river_flow != nullptr) {
                out_river_flow[oi] = s.flow[wi];
            }
        }
    }
    return 0;
}

} // extern "C"
