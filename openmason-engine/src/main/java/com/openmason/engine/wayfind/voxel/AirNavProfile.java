package com.openmason.engine.wayfind.voxel;

/**
 * How one kind of flyer routes through the air: the size of the airspace it needs, the altitude it
 * would rather hold, and what it costs to leave that altitude. The flight counterpart of
 * {@link NavProfile}, and deliberately a separate record — a walker's step, climb and fall limits
 * mean nothing to something with wings, and sharing one profile would leave half of it unread.
 *
 * <p><b>{@code cellSize} is the clearance model.</b> An air search works on cells of
 * {@code cellSize} blocks a side, and a cell counts as flyable only when <em>every</em> block in it
 * is empty. That is what buys the route its margin: a waypoint at a cell centre has at least half a
 * cell of clear air in every direction, so a body comfortably narrower than the cell can fly the
 * straight line between two of them. Raising the cell size makes routes coarser, cheaper and more
 * cautious; lowering it lets a flyer thread gaps at the cost of a much larger search.
 *
 * @param cruiseY        world Y the route prefers to hold; the altitude cost pulls back toward it
 * @param altitudeCost   cost per cell of vertical deviation from {@code cruiseY}. This is what
 *                       makes a route hug its cruise altitude and climb only where terrain forces
 *                       it, rather than wandering vertically through equally-open sky
 * @param climbCost      surcharge per cell of ascent — climbing is work
 * @param descendCost    surcharge per cell of descent; normally well below {@code climbCost}
 * @param allowUnknown   whether cells outside loaded terrain may be routed through. Ground
 *                       navigation refuses them because unloaded ground might be a cliff; a flyer
 *                       at altitude is almost certainly over open sky there, and refusing would
 *                       wall a migrating flock in at the edge of the loaded world
 * @param unknownCost    surcharge for entering an unknown cell, so known-clear air always wins a
 *                       tie. Ignored when {@code allowUnknown} is false
 * @param minY           lowest world Y a route may occupy; cells reaching below it are unflyable
 * @param maxY           highest world Y a route may occupy — the flight ceiling
 */
public record AirNavProfile(
        int cellSize,
        float cruiseY,
        float altitudeCost,
        float climbCost,
        float descendCost,
        boolean allowUnknown,
        float unknownCost,
        int minY,
        int maxY) {

    public AirNavProfile {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be positive: " + cellSize);
        }
        if (altitudeCost < 0.0f || climbCost < 0.0f || descendCost < 0.0f || unknownCost < 0.0f) {
            // A negative surcharge would make the euclidean heuristic inadmissible, silently
            // costing optimality. Preferences are expressed by raising other costs, never by
            // discounting below the geometric distance.
            throw new IllegalArgumentException("surcharges must not be negative");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must not exceed maxY: " + minY + " > " + maxY);
        }
    }

    /**
     * A bird: four-block cells, a mild pull toward cruise, climbing costed above descending, and
     * unloaded sky allowed at a premium.
     */
    public static AirNavProfile flyer(int cellSize, float cruiseY, int minY, int maxY) {
        return new AirNavProfile(cellSize, cruiseY, 0.35f, 0.5f, 0.15f, true, 0.75f, minY, maxY);
    }

    public AirNavProfile withCruiseY(float y) {
        return new AirNavProfile(cellSize, y, altitudeCost, climbCost, descendCost,
                allowUnknown, unknownCost, minY, maxY);
    }

    public AirNavProfile withUnknownAllowed(boolean allowed) {
        return new AirNavProfile(cellSize, cruiseY, altitudeCost, climbCost, descendCost,
                allowed, unknownCost, minY, maxY);
    }
}
