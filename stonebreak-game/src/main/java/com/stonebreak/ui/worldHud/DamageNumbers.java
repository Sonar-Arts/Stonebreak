package com.stonebreak.ui.worldHud;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Damage numbers floating up from hit entities: spawned by {@code LivingEntityCombat}, aged on the
 * render path ({@code FrameRenderer}) and collected each frame as {@link WorldMarker.Kind#DAMAGE}
 * markers for the world HUD document.
 */
public class DamageNumbers {

    private static final DamageNumbers INSTANCE = new DamageNumbers();

    private static final float LIFETIME   = 0.75f;
    private static final float GRAVITY_PX = 640f;
    private static final float VY_BASE    = -200f;
    private static final float VY_VARY    =   50f;
    private static final float VX_RANGE   =   70f;
    private static final int   MAX_ACTIVE = 30;

    private final List<DamageNumber> active = new ArrayList<>();
    private final java.util.Random random = new java.util.Random();

    private DamageNumbers() {}

    public static DamageNumbers getInstance() {
        return INSTANCE;
    }

    public void spawn(float wx, float wy, float wz, float damage) {
        if (active.size() >= MAX_ACTIVE) return;
        float vx = (random.nextFloat() * 2f - 1f) * VX_RANGE;
        float vy = VY_BASE + (random.nextFloat() * 2f - 1f) * VY_VARY;
        active.add(new DamageNumber(wx, wy, wz, damage, vx, vy));
    }

    public void update(float deltaTime) {
        active.removeIf(n -> {
            n.age += deltaTime;
            return n.age >= LIFETIME;
        });
    }

    /** The live numbers, projected and offset by their arc, as {@link WorldMarker.Kind#DAMAGE} markers (#301). */
    public void collect(Matrix4f vp, int screenW, int screenH, List<WorldMarker> out) {
        Vector4f clip = new Vector4f();
        for (DamageNumber n : active) {
            clip.set(n.wx, n.wy, n.wz, 1f);
            vp.transform(clip);
            if (clip.w <= 0f) continue;

            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            if (Math.abs(ndcX) > 1f || Math.abs(ndcY) > 1f) continue;

            float t    = n.age / LIFETIME;
            float age  = n.age;
            float offX = n.vx * age;
            float offY = n.vy * age + 0.5f * GRAVITY_PX * age * age;
            float sx   = (ndcX + 1f) * 0.5f * screenW + offX;
            float sy   = (1f - ndcY) * 0.5f * screenH + offY;

            float alpha = t < 0.5f ? 1f : 1f - (t - 0.5f) * 2f;
            float fontSize = Math.min(28f + n.damage * 1.5f, 44f);

            float baseline = sy + fontSize * 0.35f;
            String text = String.valueOf((int) n.damage);

            int textColor   = (int)(alpha * 255)         << 24 | 0x00FFFFFF;
            int shadowColor = (int)(alpha * 153)         << 24 | 0x001A1A1A;

            out.add(WorldMarker.text(WorldMarker.id(WorldMarker.Kind.DAMAGE, n), WorldMarker.Kind.DAMAGE, sx, baseline,
                text, fontSize, textColor, shadowColor));
        }
    }

    private static final class DamageNumber {
        final float wx, wy, wz, damage, vx, vy;
        float age;

        DamageNumber(float wx, float wy, float wz, float damage, float vx, float vy) {
            this.wx = wx;
            this.wy = wy;
            this.wz = wz;
            this.damage = damage;
            this.vx = vx;
            this.vy = vy;
        }
    }
}
