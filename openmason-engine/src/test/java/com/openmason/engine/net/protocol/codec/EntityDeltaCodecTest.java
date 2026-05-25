package com.openmason.engine.net.protocol.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quantization, clamping, and wrap behavior for {@link EntityDeltaCodec}.
 */
class EntityDeltaCodecTest {

    /** One position quantum = 1/4096 block. */
    private static final float POS_EPS = 1f / EntityDeltaCodec.POS_SCALE;

    @Test
    void positionQuantizesToWithinOneStep() {
        float[] samples = {0f, 0.5f, 1f, -1f, 3.25f, -7.5f, 0.00037f};
        for (float v : samples) {
            float decoded = EntityDeltaCodec.decodePosDelta(EntityDeltaCodec.encodePosDelta(v));
            assertEquals(v, decoded, POS_EPS,
                "position delta " + v + " should round-trip within one quantum");
        }
    }

    @Test
    void positionClampsToEightBlocks() {
        float maxBlocks = Short.MAX_VALUE / EntityDeltaCodec.POS_SCALE; // ~7.9998
        assertEquals(maxBlocks, EntityDeltaCodec.decodePosDelta(EntityDeltaCodec.encodePosDelta(100f)), 1e-4f);
        assertEquals(-8f, EntityDeltaCodec.decodePosDelta(EntityDeltaCodec.encodePosDelta(-100f)), 1e-3f);
        assertTrue(EntityDeltaCodec.decodePosDelta(EntityDeltaCodec.encodePosDelta(100f)) <= 8f);
        assertTrue(EntityDeltaCodec.decodePosDelta(EntityDeltaCodec.encodePosDelta(-100f)) >= -8f);
    }

    @Test
    void yawWrapsAndQuantizes() {
        assertEquals(0.1f, EntityDeltaCodec.decodeYawDeg(EntityDeltaCodec.encodeYawDeg(0.1f)), 1e-4f);
        // 3605° wraps to 5°.
        assertEquals(5f, EntityDeltaCodec.decodeYawDeg(EntityDeltaCodec.encodeYawDeg(3605f)), 0.05f);
        // 370° is within range and round-trips.
        assertEquals(370f, EntityDeltaCodec.decodeYawDeg(EntityDeltaCodec.encodeYawDeg(370f)), 0.05f);
        // Negative yaw keeps sign through the wrap.
        assertEquals(-5f, EntityDeltaCodec.decodeYawDeg(EntityDeltaCodec.encodeYawDeg(-5f)), 0.05f);
    }

    @Test
    void fitsInDeltaRespectsMargin() {
        assertTrue(EntityDeltaCodec.fitsInDelta(1f, -2f, 7.9f));
        assertFalse(EntityDeltaCodec.fitsInDelta(8.5f, 0f, 0f));
        assertFalse(EntityDeltaCodec.fitsInDelta(0f, 0f, -10f));
    }
}
