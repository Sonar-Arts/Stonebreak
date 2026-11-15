package com.stonebreak.mobs.goblin.camp;

import org.joml.Vector3i;

import java.util.List;

/**
 * Where a band will make camp: the cell its fire goes in (a covered, standable cell in a dark
 * cave), and the cells to dig out first when that cave has no walkable way to the surface —
 * ordered from the surface down, so each is dug from where the last one was.
 */
public record CampSitePlan(Vector3i site, List<Vector3i> dig) {

    public CampSitePlan {
        dig = List.copyOf(dig);
    }

    public boolean needsDigging() {
        return !dig.isEmpty();
    }
}
