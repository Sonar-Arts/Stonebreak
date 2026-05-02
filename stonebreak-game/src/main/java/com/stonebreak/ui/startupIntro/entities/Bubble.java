package com.stonebreak.ui.startupIntro.entities;

import com.stonebreak.ui.startupIntro.IntroConfig;
import com.stonebreak.ui.startupIntro.render.IntroPainter;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Rising bubble particle. Port of Bubble.cs.
 */
public final class Bubble {

    private float x;
    private float y;
    private final float radius;
    private final float speed;
    private final float phase;
    private float opacity;

    public Bubble(float x, float y) {
        this.x = x;
        this.y = y;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        this.radius = IntroConfig.s(3) + rng.nextFloat() * IntroConfig.s(8);
        this.speed = IntroConfig.s(40) + rng.nextFloat() * IntroConfig.s(60);
        this.opacity = 0.4f + rng.nextFloat() * 0.4f;
        this.phase = rng.nextFloat() * (float) Math.PI * 2f;
    }

    public boolean isExpired() {
        return y < -IntroConfig.s(20) || opacity <= 0f;
    }

    public void update(float deltaTime, float totalElapsed) {
        y -= speed * deltaTime;
        x += (float) Math.sin(totalElapsed * 2f + phase) * IntroConfig.s(0.5f);
        opacity -= deltaTime * 0.3f;
    }

    public void draw(IntroPainter p) {
        int color = IntroConfig.multiplyAlpha(IntroConfig.BUBBLE_COLOR, opacity);
        p.drawCircleOutline(x, y, radius, color, IntroConfig.siMin1(1));

        int highlight = IntroConfig.multiplyAlpha(0xFFFFFFFF, opacity * 0.5f);
        float hlX = x - radius * 0.3f;
        float hlY = y - radius * 0.3f;
        p.drawRectangle(hlX, hlY, IntroConfig.siMin1(2), IntroConfig.siMin1(2), highlight);
    }
}
