package com.stonebreak.mobs.sbe;

import com.openmason.engine.format.oma.ParsedAnimClip;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAttackAnimationTest {
    @Test
    void shortGameplayPulseStillPlaysThroughImpactAndRecovery() {
        var animation = new PlayerAttackAnimation();
        animation.update(0.01f, true, null);
        animation.update(0.25f, false, null);
        assertTrue(animation.overlay().isVisible());
        animation.update(0.13f, false, null);
        assertEquals(0.38f, animation.overlay().time(), 1e-6f);
        assertEquals(1f, animation.overlay().weight(0.08f, 0.12f));
        animation.update(0.39f, false, null);
        assertTrue(animation.overlay().isVisible());
        animation.update(0.02f, false, null);
        assertFalse(animation.overlay().isVisible());
    }

    @Test
    void rapidMiningPulsesDoNotRestartThePunch() {
        var animation = new PlayerAttackAnimation();
        animation.update(0.01f, true, null);
        animation.update(0.1f, false, null);
        animation.update(0.1f, true, null);
        animation.update(0.1f, false, null);
        animation.update(0.1f, true, null);
        assertEquals(0.4f, animation.overlay().time(), 1e-6f);
    }

    @Test
    void heldAttackRepeatsAfterTheAuthoredDuration() {
        var asset = new SbeEntityAsset("test:player", Map.of(), Map.of("attacking",
                new ParsedAnimClip("Punch", 30f, 1.2f, false, List.of())));
        var animation = new PlayerAttackAnimation();
        animation.update(0.01f, true, asset);
        animation.update(0.8f, true, asset);
        assertTrue(animation.overlay().isVisible());
        assertEquals(0.8f, animation.overlay().time(), 1e-6f);
        animation.update(0.41f, true, asset);
        assertFalse(animation.overlay().isVisible());
        animation.update(0.01f, true, asset);
        assertTrue(animation.overlay().isVisible());
        assertEquals(0f, animation.overlay().time());
    }

    @Test
    void idleDoesNotGeneratePunches() {
        var animation = new PlayerAttackAnimation();
        animation.update(2f, false, null);
        assertFalse(animation.overlay().isVisible());
        animation.update(0.01f, true, null);
        animation.update(2f, false, null);
        animation.update(2f, false, null);
        assertFalse(animation.overlay().isVisible());
    }
}
