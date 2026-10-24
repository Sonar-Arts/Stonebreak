package com.stonebreak.vehicles;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.rendering.Renderer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seats, breaking, replication and hit tests of {@link Dinghy} — no world needed. */
class DinghyTest {

    /** A stand-in mob: only seat bookkeeping is under test. */
    private static final class Rider extends Entity {
        Rider() {
            super(null, new Vector3f());
            setNetworkId(77);
        }
        @Override public void update(float deltaTime) { }
        @Override public void render(Renderer renderer) { }
        @Override public EntityType getType() { return EntityType.COW; }
    }

    private static Dinghy boat() {
        return new Dinghy(null, new Vector3f(10f, 64f, 20f), 0f);
    }

    @Test
    void playersTakeTheOarsFirstThenTheStern() {
        Dinghy d = boat();
        assertEquals(Dinghy.DRIVER_SEAT, d.boardPlayer(1));
        assertEquals(Dinghy.PASSENGER_SEAT, d.boardPlayer(2));
        assertEquals(-1, d.boardPlayer(3), "full");
        assertEquals(1, d.driverPlayerId());
        assertEquals(Dinghy.DRIVER_SEAT, d.boardPlayer(1), "boarding again keeps the seat");
    }

    @Test
    void mobsOnlyTakeTheSternSeat() {
        Dinghy d = boat();
        Rider cow = new Rider();
        assertTrue(d.boardMob(cow));
        assertSame(d, cow.getVehicle());
        assertEquals(Dinghy.PASSENGER_SEAT, cow.getSeatIndex());
        assertEquals(Dinghy.DRIVER_SEAT, d.boardPlayer(5), "the oars stay free for a player");
        assertFalse(d.boardMob(new Rider()), "stern taken");
    }

    @Test
    void breakingStandsEveryoneUp() {
        Dinghy d = boat();
        Rider cow = new Rider();
        d.boardMob(cow);
        d.boardPlayer(4);
        d.dismountAll();
        assertNull(cow.getVehicle());
        assertEquals(-1, d.driverPlayerId());
        assertTrue(d.isSeatFree(Dinghy.PASSENGER_SEAT));
    }

    @Test
    void threeSpacedHitsBreakIt() {
        Dinghy d = boat();
        assertFalse(d.hit());
        assertFalse(d.hit(), "too soon after the last hit");
        d.update(0.3f);
        assertFalse(d.hit());
        d.update(0.3f);
        assertTrue(d.hit());
    }

    @Test
    void seatsAndOarsReplicate() {
        Dinghy server = boat();
        server.boardPlayer(9);
        server.boardMob(new Rider());
        server.applyDriverMove(10f, 64f, 20f, 0f, OarInput.fromKeys(true, false, false, true));

        Dinghy shadow = boat();
        shadow.applyNetworkState(server.networkVisualState());
        assertEquals(SeatToken.player(9), shadow.seatToken(Dinghy.DRIVER_SEAT));
        assertEquals(SeatToken.entity(77), shadow.seatToken(Dinghy.PASSENGER_SEAT));
        assertEquals(server.getOars(), shadow.getOars());
    }

    @Test
    void seatsTurnWithTheBoat() {
        Dinghy d = new Dinghy(null, new Vector3f(), 90f);
        Vector3f stern = d.seatPosition(Dinghy.PASSENGER_SEAT, new Vector3f());
        // yaw 90 turns model +Z (the stern) to world +X
        assertEquals(0.84f, stern.x, 1e-4f);
        assertEquals(0f, stern.z, 1e-4f);
    }

    @Test
    void theLongHullIsHitAlongItsLength() {
        Dinghy d = new Dinghy(null, new Vector3f(), 0f);
        Vector3f down = new Vector3f(0f, -1f, 0f);
        assertTrue(d.rayIntersect(new Vector3f(0f, 3f, -1.6f), down) < Float.MAX_VALUE, "bow");
        assertEquals(Float.MAX_VALUE, d.rayIntersect(new Vector3f(1.2f, 3f, 0f), down), "beside the hull");
        Dinghy turned = new Dinghy(null, new Vector3f(), 90f);
        assertTrue(turned.rayIntersect(new Vector3f(-1.6f, 3f, 0f), down) < Float.MAX_VALUE, "bow, turned");
    }
}
