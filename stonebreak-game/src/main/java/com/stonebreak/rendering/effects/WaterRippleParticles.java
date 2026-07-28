package com.stonebreak.rendering.effects;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages expanding ripple rings that trail the player across the water surface. Each ring
 * grows outward from its spawn point and fades with age; a sample point on a ring is hidden
 * once it falls inside another still-growing ring's disc — i.e. that ring's wavefront already
 * swept past there — so two ripples visibly collide and cancel where they meet rather than
 * passing through each other, like real water interference.
 */
public class WaterRippleParticles {

    private static final int MAX_RIPPLES = 8;
    private static final int SAMPLES_PER_RING = 24;
    private static final float RADIUS_SPEED = 1.8f;   // blocks/sec outward growth
    private static final float MAX_RADIUS = 2.4f;
    private static final float LIFETIME = MAX_RADIUS / RADIUS_SPEED + 0.3f;
    private static final float RING_BAND = 0.18f;      // collision tolerance at a ring's edge

    private final List<Ripple> ripples = Collections.synchronizedList(new ArrayList<>());

    private static class Ripple {
        final Vector3f center; // world position; y = surface height at spawn
        float age;

        Ripple(Vector3f center) {
            this.center = new Vector3f(center);
        }

        float radius() { return Math.min(MAX_RADIUS, age * RADIUS_SPEED); }
        boolean isDead() { return age >= LIFETIME; }

        float getOpacity() {
            float fadeIn = Math.min(1f, age / 0.15f);
            float fadeOut = Math.max(0f, 1f - age / LIFETIME);
            return Math.min(fadeIn, fadeOut);
        }
    }

    /** A single visible ring sample, in world space with its current fade opacity. */
    public record RipplePoint(float x, float y, float z, float opacity) {}

    /** Spawns a new ripple ring centered at {@code origin} (e.g. the player's position). */
    public void spawn(Vector3f origin) {
        synchronized (ripples) {
            if (ripples.size() >= MAX_RIPPLES) {
                ripples.remove(0);
            }
            ripples.add(new Ripple(origin));
        }
    }

    public void update(float deltaTime) {
        synchronized (ripples) {
            ripples.removeIf(Ripple::isDead);
            for (Ripple r : ripples) {
                r.age += deltaTime;
            }
        }
    }

    /**
     * Samples every active ring into visible points, dropping any sample already swept over by
     * another ring's wavefront (their collision boundary). Safe to call from the render thread.
     */
    public List<RipplePoint> snapshotPoints() {
        List<Ripple> snapshot;
        synchronized (ripples) {
            snapshot = new ArrayList<>(ripples);
        }

        List<RipplePoint> points = new ArrayList<>();
        for (Ripple ring : snapshot) {
            float radius = ring.radius();
            if (radius <= 0.05f) continue;
            float opacity = ring.getOpacity();
            if (opacity <= 0f) continue;

            for (int i = 0; i < SAMPLES_PER_RING; i++) {
                float angle = (float) (i * (Math.PI * 2.0 / SAMPLES_PER_RING));
                float px = ring.center.x + (float) Math.cos(angle) * radius;
                float pz = ring.center.z + (float) Math.sin(angle) * radius;

                if (isCollided(px, pz, ring, snapshot)) continue;

                points.add(new RipplePoint(px, ring.center.y, pz, opacity));
            }
        }
        return points;
    }

    /** True if (px, pz) already lies inside another still-growing ring's disc. */
    private boolean isCollided(float px, float pz, Ripple self, List<Ripple> all) {
        for (Ripple other : all) {
            if (other == self) continue;
            float dx = px - other.center.x;
            float dz = pz - other.center.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist < other.radius() - RING_BAND) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        synchronized (ripples) {
            return ripples.isEmpty();
        }
    }
}
