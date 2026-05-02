package com.stonebreak.ui.startupIntro.entities;

import com.stonebreak.ui.startupIntro.IntroConfig;
import com.stonebreak.ui.startupIntro.render.IntroPainter;

/**
 * Static centered submarine silhouette. Port of Submarine.cs.
 */
public final class Submarine {

    private final int screenWidth;
    private final int screenHeight;

    public Submarine(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public void draw(IntroPainter p) {
        float cx = screenWidth * 0.5f;
        float cy = screenHeight * 0.5f;

        float hullW = IntroConfig.s(180);
        float hullH = IntroConfig.s(50);

        // Hull
        p.drawFilledEllipse(cx, cy, hullW, hullH, IntroConfig.SUBMARINE_COLOR);

        // Conning tower
        float towerW = IntroConfig.s(35);
        float towerH = IntroConfig.s(30);
        p.drawRectangle(
                cx - towerW / 2f,
                cy - hullH / 2f - towerH + IntroConfig.s(5),
                towerW,
                towerH,
                IntroConfig.SUBMARINE_COLOR);

        // Tower top (rounded)
        p.drawFilledEllipse(
                cx,
                cy - hullH / 2f - towerH + IntroConfig.s(5),
                towerW,
                IntroConfig.s(15),
                IntroConfig.SUBMARINE_COLOR);

        // Propeller area
        float propX = cx + hullW / 2f - IntroConfig.s(10);
        p.drawFilledEllipse(propX, cy, IntroConfig.s(20), IntroConfig.s(35), IntroConfig.SUBMARINE_COLOR);

        // Periscope
        p.drawRectangle(
                cx + IntroConfig.s(5),
                cy - hullH / 2f - towerH - IntroConfig.s(15),
                IntroConfig.siMin1(3),
                IntroConfig.si(20),
                IntroConfig.SUBMARINE_COLOR);

        // Hull highlight
        int highlight = IntroConfig.multiplyAlpha(IntroConfig.SUBMARINE_HIGHLIGHT, 0.3f);
        p.drawFilledEllipse(cx - IntroConfig.s(20), cy - IntroConfig.s(10),
                IntroConfig.s(100), IntroConfig.s(15), highlight);
    }
}
