package com.stonebreak.ui.startupIntro.entities;

import com.stonebreak.ui.startupIntro.IntroConfig;
import com.stonebreak.ui.startupIntro.render.IntroPainter;

/**
 * Expanding sonar ring. Port of SonarRing.cs without the green→purple color
 * transition (the intro stays in the submarine phase only, so rings are always
 * green).
 */
public final class SonarRing {

    private final float centerX;
    private final float centerY;
    private final int color;
    private final float maxRadius;
    private final float speed;

    private float radius;
    private float opacity;

    public SonarRing(float centerX, float centerY, int color, float maxRadius, float speedVariance0to1) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.color = color;
        this.maxRadius = maxRadius;
        this.speed = IntroConfig.sonarBaseSpeed() + speedVariance0to1 * IntroConfig.sonarSpeedVariance();
        this.radius = IntroConfig.sonarInitialRadius();
        this.opacity = 1f;
    }

    public float radius() { return radius; }

    public boolean isExpired() { return opacity <= 0f; }

    public void update(float deltaTime) {
        radius += speed * deltaTime;
        // Aggressive fade — fully gone by 30% of max radius (matches reference).
        float fadeProgress = radius / (maxRadius * 0.3f);
        opacity = Math.max(0f, Math.min(1f, 1f - fadeProgress));
    }

    public void draw(IntroPainter painter) {
        int ringColor = IntroConfig.multiplyAlpha(color, opacity);
        painter.drawCircleOutline(centerX, centerY, radius, ringColor, IntroConfig.siMin1(3));

        // Inner glow when bright.
        if (opacity > 0.5f) {
            int glowColor = IntroConfig.multiplyAlpha(color, (opacity - 0.5f) * 0.5f);
            painter.drawCircleOutline(centerX, centerY, radius - IntroConfig.s(5), glowColor, IntroConfig.siMin1(2));
        }
    }
}
