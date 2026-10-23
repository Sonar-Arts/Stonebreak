package com.stonebreak.network.client;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.rendering.Renderer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shadow that stopped moving gets no packet until the 2 s resync. It used to hold 25 %
 * past its last authoritative position for that whole time (drops sank into the ground) and
 * then oscillate around it on each resync; it now eases back onto the target.
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
        interp.seed(0f, 0f, 0f, 0f, 0f);
        long start = System.nanoTime();
        interp.receive(1f, 0f, 0f, 0f, shadow);

        interp.apply(shadow, start + PERIOD);
        assertEquals(1f, shadow.getPosition().x, 0.02f);

        interp.apply(shadow, start + PERIOD + PERIOD / 4);
        assertTrue(shadow.getPosition().x > 1.15f, "keeps moving while the next snapshot is due");

        interp.apply(shadow, start + 3 * PERIOD);
        assertEquals(1f, shadow.getPosition().x, 1e-4f, "no snapshot came: back on the last target");

        interp.apply(shadow, start + 40 * PERIOD);
        assertEquals(1f, shadow.getPosition().x, 1e-4f);
    }
}
