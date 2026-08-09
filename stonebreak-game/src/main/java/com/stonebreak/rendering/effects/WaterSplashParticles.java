package com.stonebreak.rendering.effects;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Manages the droplet burst spawned when the player enters water (e.g. jumping in). Particles
 * fly outward/upward from the entry point and fall back under gravity, mirroring the structure
 * of {@link FireTrailParticles} but with a ballistic arc suited to splashing water.
 */
public class WaterSplashParticles {

    private static final int MAX_PARTICLES = 320;
    private static final float GRAVITY = 9.8f;

    private final List<SplashParticle> particles = Collections.synchronizedList(new ArrayList<>());
    private final Random random = new Random();

    public static class SplashParticle {
        private final Vector3f position;
        private final Vector3f velocity;
        private float lifetime;
        private final float initialLifetime;
        private final float size;

        SplashParticle(Vector3f position, Vector3f velocity, float lifetime, float size) {
            this.position = new Vector3f(position);
            this.velocity = new Vector3f(velocity);
            this.lifetime = lifetime;
            this.initialLifetime = lifetime;
            this.size = size;
        }

        public void update(float deltaTime) {
            velocity.y -= GRAVITY * deltaTime;
            position.add(new Vector3f(velocity).mul(deltaTime));
            lifetime -= deltaTime;
        }

        public boolean isDead() { return lifetime <= 0f; }
        public Vector3f getPosition() { return position; }
        public float getOpacity() { return Math.max(0f, lifetime / initialLifetime); }
        public float getSize() { return size; }
    }

    /**
     * Emits a radial burst of droplets at the water entry point, scaled by how fast the player
     * was falling when they hit the surface.
     *
     * @param origin      world position of the water surface impact
     * @param impactSpeed downward speed at entry (blocks/sec), non-negative
     */
    public void burst(Vector3f origin, float impactSpeed) {
        float intensity = Math.min(1.0f, Math.max(0.35f, impactSpeed / 8.0f));
        int count = Math.min(16 + (int) (intensity * 40), MAX_PARTICLES - particles.size());
        for (int i = 0; i < count; i++) {
            float angle = random.nextFloat() * (float) Math.PI * 2f;
            float outward = (1.4f + random.nextFloat() * 3.6f) * intensity;

            Vector3f pos = new Vector3f(origin).add(
                    (random.nextFloat() - 0.5f) * 0.4f,
                    0.05f,
                    (random.nextFloat() - 0.5f) * 0.4f);
            Vector3f vel = new Vector3f(
                    (float) Math.cos(angle) * outward,
                    (2.5f + random.nextFloat() * 3.5f) * intensity,
                    (float) Math.sin(angle) * outward);

            float lifetime = 0.45f + random.nextFloat() * 0.45f;
            float size = 5.0f + random.nextFloat() * 6.5f;
            particles.add(new SplashParticle(pos, vel, lifetime, size));
        }
    }

    public void update(float deltaTime) {
        synchronized (particles) {
            particles.removeIf(SplashParticle::isDead);
            for (SplashParticle p : particles) {
                p.update(deltaTime);
            }
        }
    }

    /**
     * Returns a defensive copy of the live particles taken under the list's monitor, safe to
     * iterate on a different thread (e.g. the render thread) while {@link #update} mutates the
     * backing list.
     */
    public List<SplashParticle> snapshot() {
        synchronized (particles) {
            return new ArrayList<>(particles);
        }
    }

    public boolean isEmpty() { return particles.isEmpty(); }
}
