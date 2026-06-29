package com.stonebreak.rendering.effects;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Manages the brief smoke puff emitted when the Illusionist's Mirrored Deceit decoys appear (and
 * when they are destroyed). Particles drift gently upward and fade out, mirroring the structure of
 * {@link FireTrailParticles} but with slower, lighter motion suited to illusory smoke.
 */
public class IllusionSmokeParticles {

    private static final int MAX_PARTICLES = 200;
    private static final float UPWARD_DRIFT = 0.4f;

    private final List<SmokeParticle> particles = Collections.synchronizedList(new ArrayList<>());
    private final Random random = new Random();

    public static class SmokeParticle {
        private final Vector3f position;
        private final Vector3f velocity;
        private float lifetime;
        private final float initialLifetime;
        private final float size;

        SmokeParticle(Vector3f position, Vector3f velocity, float lifetime, float size) {
            this.position = new Vector3f(position);
            this.velocity = new Vector3f(velocity);
            this.lifetime = lifetime;
            this.initialLifetime = lifetime;
            this.size = size;
        }

        public void update(float deltaTime) {
            velocity.y += UPWARD_DRIFT * deltaTime;
            velocity.mul(0.96f); // gentle drag — smoke settles rather than shooting outward
            position.add(new Vector3f(velocity).mul(deltaTime));
            lifetime -= deltaTime;
        }

        public boolean isDead() { return lifetime <= 0f; }
        public Vector3f getPosition() { return position; }
        public float getOpacity() { return Math.max(0f, lifetime / initialLifetime); }
        public float getSize() { return size; }
    }

    /** Emits a radial puff of smoke around {@code origin} (decoy spawn or death). */
    public void burst(Vector3f origin) {
        int count = Math.min(18, MAX_PARTICLES - particles.size());
        for (int i = 0; i < count; i++) {
            Vector3f pos = new Vector3f(origin).add(
                    (random.nextFloat() - 0.5f) * 0.4f,
                    random.nextFloat() * 1.4f,
                    (random.nextFloat() - 0.5f) * 0.4f);
            Vector3f vel = new Vector3f(
                    (random.nextFloat() - 0.5f) * 1.6f,
                    random.nextFloat() * 0.8f,
                    (random.nextFloat() - 0.5f) * 1.6f);
            float lifetime = 0.5f + random.nextFloat() * 0.7f;
            float size = 5.0f + random.nextFloat() * 6.0f;
            particles.add(new SmokeParticle(pos, vel, lifetime, size));
        }
    }

    public void update(float deltaTime) {
        synchronized (particles) {
            particles.removeIf(SmokeParticle::isDead);
            for (SmokeParticle p : particles) {
                p.update(deltaTime);
            }
        }
    }

    /** Defensive copy taken under the list monitor — safe to iterate on the render thread. */
    public List<SmokeParticle> snapshot() {
        synchronized (particles) {
            return new ArrayList<>(particles);
        }
    }

    public boolean isEmpty() { return particles.isEmpty(); }
}
