package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each player's Shade clock comes round every lap while they stay, and stops when they break it. */
class ShadeClockTest {

    /** The Shade is everything west of x=0. */
    private static final Predicate<Vector3f> WEST_IS_SHADE = p -> p.x < 0;

    private static EntitySpawner.SpawnAnchor player(int id, float x, boolean alive) {
        return new EntitySpawner.SpawnAnchor(new Vector3f(x, 64, 0), 8, alive, id);
    }

    /** Runs the clock a second at a time and counts how often {@code watched} came round. */
    private static int laps(ShadeClock clock, int seconds, int watched, EntitySpawner.SpawnAnchor... anchors) {
        int laps = 0;
        for (int i = 0; i < seconds; i++) {
            for (EntitySpawner.SpawnAnchor due : clock.update(List.of(anchors), 1f, WEST_IS_SHADE)) {
                if (due.playerId() == watched) laps++;
            }
        }
        return laps;
    }

    @Test
    void comesRoundOnTheMinuteAndAHalf() {
        ShadeClock clock = new ShadeClock(90f);
        assertEquals(0, laps(clock, 89, 1, player(1, -10, true)));
        assertEquals(1, laps(clock, 1, 1, player(1, -10, true)), "due at 90 s exactly");
        assertEquals(0f, clock.seconds(1), "and starts the next lap");
    }

    @Test
    void keepsRevolvingWhileThePlayerStays() {
        ShadeClock clock = new ShadeClock(90f);
        assertEquals(4, laps(clock, 360, 1, player(1, -10, true)));
    }

    @Test
    void outsideTheShadeItNeverRuns() {
        ShadeClock clock = new ShadeClock(90f);
        assertEquals(0, laps(clock, 360, 2, player(2, 10, true)));
    }

    @Test
    void leavingTheShadeSetsItBack() {
        ShadeClock clock = new ShadeClock(90f);
        laps(clock, 80, 1, player(1, -10, true));
        laps(clock, 1, 1, player(1, 10, true));
        assertEquals(0, laps(clock, 80, 1, player(1, -10, true)), "the 80 s before don't carry over");
        assertEquals(1, laps(clock, 10, 1, player(1, -10, true)));
    }

    @Test
    void dyingOrLeavingTheGameSetsItBack() {
        ShadeClock clock = new ShadeClock(90f);
        laps(clock, 80, 1, player(1, -10, true), player(2, -10, true));
        laps(clock, 1, 1, player(1, -10, false));
        assertEquals(0f, clock.seconds(1), "dead");
        assertEquals(0f, clock.seconds(2), "disconnected");
    }

    @Test
    void eachPlayerKeepsTheirOwnClock() {
        ShadeClock clock = new ShadeClock(90f);
        laps(clock, 60, 1, player(1, -10, true));
        laps(clock, 30, 1, player(1, -10, true), player(2, -20, true));
        assertEquals(0f, clock.seconds(1), "player 1 came round");
        assertEquals(30f, clock.seconds(2), "player 2 is a third of the way");
    }
}
