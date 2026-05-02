package com.stonebreak.ui.startupIntro.entities;

import com.stonebreak.ui.startupIntro.IntroConfig;
import com.stonebreak.ui.startupIntro.render.IntroPainter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages a set of expanding sonar rings emanating from screen center. Direct
 * port of SonarSystem.cs minus the color transition machinery (rings stay
 * green for the entire intro).
 */
public final class SonarSystem {

    private final List<SonarRing> rings = new ArrayList<>();
    private final float centerX;
    private final float centerY;
    private final float maxRadius;
    private float spawnTimer;

    public SonarSystem(int screenWidth, int screenHeight) {
        this.centerX = screenWidth * 0.5f;
        this.centerY = screenHeight * 0.5f;
        this.maxRadius = Math.max(screenWidth, screenHeight) * 0.8f;
    }

    public float centerX() { return centerX; }

    public float centerY() { return centerY; }

    public void update(float deltaTime, boolean useInternalSpawning) {
        if (useInternalSpawning) {
            spawnTimer += deltaTime;
            if (spawnTimer >= IntroConfig.SONAR_SPAWN_INTERVAL) {
                spawnTimer = 0f;
                spawnRing();
            }
        }
        Iterator<SonarRing> it = rings.iterator();
        while (it.hasNext()) {
            SonarRing r = it.next();
            r.update(deltaTime);
            if (r.isExpired()) it.remove();
        }
    }

    public void spawnRing() {
        rings.add(new SonarRing(
                centerX, centerY,
                IntroConfig.SONAR_GREEN,
                maxRadius,
                ThreadLocalRandom.current().nextFloat()
        ));
    }

    public void draw(IntroPainter painter) {
        for (SonarRing r : rings) r.draw(painter);
    }

    public float largestRadius() {
        float max = 0f;
        for (SonarRing r : rings) if (r.radius() > max) max = r.radius();
        return max;
    }
}
