package com.stonebreak.rendering.effects;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Manages fire trail particles spawned behind a moving fire bolt.
 * Particles drift upward and fade out over their lifetime.
 */
public class FireTrailParticles {

    private static final int MAX_PARTICLES = 150;
    private static final float UPWARD_DRIFT = 0.6f;

    private final List<FireParticle> particles = Collections.synchronizedList(new ArrayList<>());
    private final Random random = new Random();

    public static class FireParticle {
        private final Vector3f position;
        private final Vector3f velocity;
        private float lifetime;
        private final float initialLifetime;
        private final float size;

        FireParticle(Vector3f position, Vector3f velocity, float lifetime, float size) {
            this.position = new Vector3f(position);
            this.velocity = new Vector3f(velocity);
            this.lifetime = lifetime;
            this.initialLifetime = lifetime;
            this.size = size;
        }

        public void update(float deltaTime) {
            velocity.y += UPWARD_DRIFT * deltaTime;
            position.add(new Vector3f(velocity).mul(deltaTime));
            lifetime -= deltaTime;
        }

        public boolean isDead() { return lifetime <= 0f; }
        public Vector3f getPosition() { return position; }
        public float getOpacity() { return Math.max(0f, lifetime / initialLifetime); }
        public float getSize() { return size; }
    }

    /**
     * Spawns 2-3 particles behind the fire bolt at the given position.
     *
     * @param boltPosition current position of the fire bolt
     * @param backDir      unit vector pointing opposite to bolt travel direction
     */
    public void spawn(Vector3f boltPosition, Vector3f backDir) {
        if (particles.size() >= MAX_PARTICLES) return;

        int count = 2 + random.nextInt(2); // 2 or 3
        for (int i = 0; i < count && particles.size() < MAX_PARTICLES; i++) {
            Vector3f pos = new Vector3f(boltPosition)
                    .add(backDir.x * 0.1f + (random.nextFloat() - 0.5f) * 0.05f,
                         (random.nextFloat() - 0.5f) * 0.05f,
                         backDir.z * 0.1f + (random.nextFloat() - 0.5f) * 0.05f);

            Vector3f vel = new Vector3f(
                    backDir.x * (0.5f + random.nextFloat() * 0.5f) + (random.nextFloat() - 0.5f) * 0.3f,
                    random.nextFloat() * 0.4f,
                    backDir.z * (0.5f + random.nextFloat() * 0.5f) + (random.nextFloat() - 0.5f) * 0.3f
            );

            float lifetime = 0.3f + random.nextFloat() * 0.5f;
            float size = 3.0f + random.nextFloat() * 4.0f;
            particles.add(new FireParticle(pos, vel, lifetime, size));
        }
    }

    /**
     * Spawns a short radial puff of particles at the given position, used when
     * the fire bolt impacts a block or entity so the trail ends with a burst
     * rather than vanishing.
     *
     * @param origin world position of the impact
     */
    public void burst(Vector3f origin) {
        int count = Math.min(20, MAX_PARTICLES - particles.size());
        for (int i = 0; i < count; i++) {
            Vector3f vel = new Vector3f(
                    (random.nextFloat() - 0.5f) * 4.0f,
                    random.nextFloat() * 3.0f,
                    (random.nextFloat() - 0.5f) * 4.0f
            );
            float lifetime = 0.25f + random.nextFloat() * 0.5f;
            float size = 4.0f + random.nextFloat() * 5.0f;
            particles.add(new FireParticle(origin, vel, lifetime, size));
        }
    }

    public void update(float deltaTime) {
        synchronized (particles) {
            particles.removeIf(FireParticle::isDead);
            for (FireParticle p : particles) {
                p.update(deltaTime);
            }
        }
    }

    /**
     * Returns a defensive copy of the live particles taken under the list's
     * monitor, safe to iterate on a different thread (e.g. the render thread)
     * while {@link #update} mutates the backing list.
     */
    public List<FireParticle> snapshot() {
        synchronized (particles) {
            return new ArrayList<>(particles);
        }
    }

    public boolean isEmpty() { return particles.isEmpty(); }
}
