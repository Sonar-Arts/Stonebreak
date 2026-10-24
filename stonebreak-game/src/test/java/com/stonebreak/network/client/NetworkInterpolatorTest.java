package com.stonebreak.network.client;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.rendering.Renderer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shadow interpolation. A shadow that stopped moving gets no packet until the 2 s resync: it
 * used to hold 25 % past its last authoritative position for that whole time (drops sank into
 * the ground) and oscillate around it on each resync; it now eases back onto the target. A
 * burst of snapshots (a server catching up several ticks at once) used to play 250 ms of
 * motion in one 50 ms segment and freeze; it now plays out evenly from a buffer.
 */
class NetworkInterpolatorTest {

    private static final long PERIOD = NetworkInterpolator.DEFAULT_PERIOD_NS;

    private static final class Shadow extends Entity {
        Shadow() { super(null, new Vector3f()); }
        @Override public void update(float deltaTime) { }
        @Override public void render(Renderer renderer) { }
        @Override public EntityType getType() { return EntityType.ITEM_DROP; }
    }

    @Test
    void extrapolatesBrieflyThenRestsOnTheTarget() {
        Shadow shadow = new Shadow();
        NetworkInterpolator interp = new NetworkInterpolator();
        long start = System.nanoTime();
        interp.seed(0f, 0f, 0f, 0f, 0f);
        interp.receive(1f, 0f, 0f, 0f, shadow); // queued behind the seed's own segment

        interp.apply(shadow, start + 2 * PERIOD);
        assertEquals(1f, shadow.getPosition().x, 0.02f);

        interp.apply(shadow, start + 2 * PERIOD + PERIOD / 4);
        assertTrue(shadow.getPosition().x > 1.15f, "keeps moving while the next snapshot is due");

        interp.apply(shadow, start + 4 * PERIOD);
        assertEquals(1f, shadow.getPosition().x, 1e-4f, "no snapshot came: back on the last target");

        interp.apply(shadow, start + 40 * PERIOD);
        assertEquals(1f, shadow.getPosition().x, 1e-4f);
    }

    @Test
    void aBurstOfSnapshotsPlaysOutEvenly() {
        Shadow shadow = new Shadow();
        NetworkInterpolator interp = new NetworkInterpolator();
        long start = System.nanoTime();
        interp.seed(0f, 0f, 0f, 0f, 0f);
        for (int i = 1; i <= 4; i++) {
            interp.receive(i, 0f, 0f, 0f, shadow); // four ticks arriving together
        }
        assertEquals(4f, interp.targetX(), "the delta base is the newest snapshot");

        float last = 0f;
        for (long t = 0; t <= 4 * PERIOD; t += PERIOD / 10) {
            interp.apply(shadow, start + t);
            float x = shadow.getPosition().x;
            assertTrue(x >= last - 1e-4f, "never moves backward (t=" + t + ")");
            assertTrue(x - last <= 1.0f, "no jump of a whole snapshot in one frame (t=" + t + ")");
            last = x;
        }
        interp.apply(shadow, start + 6 * PERIOD);
        assertEquals(4f, shadow.getPosition().x, 1e-4f, "ends on the newest snapshot");
    }
}
