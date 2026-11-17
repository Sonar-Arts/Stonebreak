package com.stonebreak.fx.content;

import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.fx.TrailFx;
import com.stonebreak.fx.sbfx.SbfxDocument;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.openmason.engine.rendering.particles.FxBinding.Target.BURST;
import static com.openmason.engine.rendering.particles.FxBinding.Target.DIRECTION;
import static com.openmason.engine.rendering.particles.FxBinding.Target.RATE;

/**
 * The four effects that replaced the hand-rolled emitters of {@code rendering/effects} (Stonebreak#441), written as
 * engine FX systems: the bootstrap source of the {@code sbfx/} files {@link GameplayFxContentGeneratorTest} writes.
 * Each keeps the old emitter's numbers (lifetimes, speeds, drift, colours, counts) as its reference; the old
 * screen-pixel point sizes became world sizes that read the same at a few blocks.
 *
 * <ul>
 *   <li>{@code fire_trail} — flames streaming back from a flying fire bolt ({@code FireTrailParticles}), plus a
 *       short-lived hot core; follows the bolt ({@code TrailFx}), launched along its {@value TrailFx#PARAM_DIRECTION}.</li>
 *   <li>{@code fire_burst} — the radial puff where a bolt strikes, now with sparks and a wisp of smoke.</li>
 *   <li>{@code illusion_smoke} — the pale violet puff Mirrored Deceit decoys appear and vanish in
 *       ({@code IllusionSmokeParticles}), now soft billows with a few glints.</li>
 *   <li>{@code water_ripple} — a ring spreading on the surface around a swimmer ({@code WaterRippleParticles}), a
 *       real textured ring lying flat instead of 24 dots. Bound to the player SBE's {@code ripple} event.</li>
 * </ul>
 * The water entry splash was already an FX ({@code water_splash}, {@link FirstFxContent}).
 */
public final class GameplayFxContent {

    /** File name (in {@code sbfx/}) → document. */
    public static Map<String, SbfxDocument> all() {
        Map<String, SbfxDocument> out = new LinkedHashMap<>();
        out.put("SB_Fire_Trail.sbfx", doc(fireTrail()));
        out.put("SB_Fire_Burst.sbfx", doc(fireBurst()));
        out.put("SB_Illusion_Smoke.sbfx", doc(illusionSmoke()));
        out.put("SB_Water_Ripple.sbfx", SbfxDocument.of(OmfxDocument.of(waterRipple())
                .withAuthor(FirstFxContent.AUTHOR).withSprite(new OmfxSprite(RING_SPRITE, ringPng()))));
        return out;
    }

    private static SbfxDocument doc(FxSystem system) {
        return SbfxDocument.of(OmfxDocument.of(system).withAuthor(FirstFxContent.AUTHOR));
    }

    private static FxParameter density() {
        return FxParameter.scalar(FxRuntime.PARAM_DENSITY, 1f);
    }

    /** Seconds a trail emits at most: longer than a bolt flies (5 s), so a trail whose bolt vanished still ends. */
    static final float TRAIL_MAX_SECONDS = 6.5f;

    /** Old trail: 2-3 per frame (~150/s), 0.3-0.8 s, back along the bolt at 0.5-1 + jitter, rising 0.6/s². */
    static FxSystem fireTrail() {
        ParticleEffect flames = ParticleEffect.builder("sb-fire-trail-flames")
                .lifetime(0.3f, 0.8f).rate(150).sphere(0.08f)
                .speed(0.5f, 1.0f).direction(0, 0, 1).spread(22)
                .gravity(0, 0.6f, 0)
                .startSize(0.15f, 0.22f).endSize(0.04f, 0.07f)
                .colors(0xFF5A00D9, 0xFF0000D9).colorJitter(0.1f)
                .fade(0f, 1f).additive(1f).emissive(true)
                .rotation(0, 360).spin(-120, 120)
                .lodDistance(64).maxLive(6000).build();
        ParticleEffect core = ParticleEffect.builder("sb-fire-trail-core")
                .lifetime(0.08f, 0.16f).rate(60).point()
                .speed(0f, 0.2f).spread(180)
                .startSize(0.3f, 0.36f).endSize(0.12f, 0.16f)
                .colors(0xFFE8A0E0, 0xFF8020C0)
                .fade(0f, 0.6f).additive(1f).emissive(true)
                .lodDistance(64).maxLive(2000).build();
        return FxSystem.builder("fire_trail")
                .emitter(FxEmitter.builder("flames", flames).duration(TRAIL_MAX_SECONDS).build())
                .emitter(FxEmitter.builder("core", core).duration(TRAIL_MAX_SECONDS).build())
                .parameter(FxParameter.vector(TrailFx.PARAM_DIRECTION, 0, 0, 1))
                .parameter(density())
                .bind(FxBinding.of(TrailFx.PARAM_DIRECTION, DIRECTION, "flames"))
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, RATE))
                .build();
    }

    /** Old burst: 20 flames, 0.25-0.75 s, out ±2 and up 0-3 blocks/s, rising 0.6/s². */
    static FxSystem fireBurst() {
        ParticleEffect flames = ParticleEffect.builder("sb-fire-burst-flames")
                .lifetime(0.25f, 0.75f).rate(0).sphere(0.1f)
                .speed(1.0f, 3.4f).direction(0, 1, 0).spread(75)
                .gravity(0, 0.6f, 0).drag(1.5f)
                .startSize(0.22f, 0.32f).endSize(0.05f, 0.09f)
                .colors(0xFF5A00D9, 0xFF0000D9).colorJitter(0.1f)
                .fade(0f, 1f).additive(1f).emissive(true)
                .rotation(0, 360).spin(-120, 120)
                .lodDistance(64).build();
        ParticleEffect sparks = ParticleEffect.builder("sb-fire-burst-sparks")
                .lifetime(0.4f, 0.8f).rate(0).point()
                .speed(2.0f, 4.5f).direction(0, 1, 0).spread(80)
                .gravity(0, -9f, 0).drag(0.6f)
                .size(0.045f, 0.02f).stretched(0.03f).colors(0xFFD070FF, 0xFF4010FF)
                .fade(0f, 0.3f).additive(1f).emissive(true)
                .sprite(ParticleEffect.Sprite.SQUARE).collides(0.35f, 0.6f)
                .lodDistance(48).build();
        ParticleEffect smoke = ParticleEffect.builder("sb-fire-burst-smoke")
                .lifetime(0.8f, 1.4f).rate(0).sphere(0.15f)
                .speed(0.3f, 0.7f).direction(0, 1, 0).spread(40)
                .gravity(0, 0.5f, 0).drag(1.2f).wind(0.8f)
                .startSize(0.25f, 0.35f).endSize(0.6f, 0.8f)
                .colors(0x3A3A3C90, 0x6A6A6E30).colorJitter(0.1f)
                .fade(0.1f, 0.6f).softness(0.3f).rotation(0, 360).spin(-30, 30)
                .lodDistance(48).build();
        return FxSystem.builder("fire_burst")
                .emitter(FxEmitter.builder("flames", flames).burst(20).duration(0).build())
                .emitter(FxEmitter.builder("sparks", sparks).burst(8).duration(0).build())
                .emitter(FxEmitter.builder("smoke", smoke).burst(3).duration(0).delay(0.1f).build())
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, BURST))
                .build();
    }

    /**
     * Old smoke: 18 puffs over a 0.4-wide, 1.4-high column above the point, drifting out ±0.8 and up 0-0.8,
     * rising 0.4/s² under drag (×0.96 a frame at 60 fps ≈ 2.4/s), 0.5-1.2 s, pale violet at 55 %.
     */
    static FxSystem illusionSmoke() {
        ParticleEffect puff = ParticleEffect.builder("sb-illusion-smoke-puff")
                .lifetime(0.5f, 1.2f).rate(0).box(0.2f, 0.7f, 0.2f)
                .speed(0.3f, 1.0f).direction(0, 1, 0).spread(80)
                .gravity(0, 0.4f, 0).drag(2.4f).wind(0.4f)
                .startSize(0.24f, 0.34f).endSize(0.55f, 0.75f)
                .colors(0xB8A8D98C, 0xB8A8D98C).colorJitter(0.08f)
                .fade(0.08f, 0.9f).emissive(true).softness(0.3f)
                .rotation(0, 360).spin(-40, 40)
                .lodDistance(48).build();
        ParticleEffect glints = ParticleEffect.builder("sb-illusion-smoke-glints")
                .lifetime(0.5f, 1.0f).rate(0).box(0.25f, 0.7f, 0.25f)
                .speed(0.2f, 0.6f).direction(0, 1, 0).spread(180)
                .gravity(0, 0.3f, 0).drag(1.2f)
                .startSize(0.07f, 0.1f).endSize(0.01f, 0.03f)
                .colors(0xD8C8FFFF, 0x9C80F0FF).colorJitter(0.2f)
                .fade(0.15f, 0.5f).additive(1f).emissive(true)
                .lodDistance(32).build();
        return FxSystem.builder("illusion_smoke")
                .emitter(FxEmitter.builder("puff", puff).burst(18).duration(0).offset(0, 0.7f, 0).build())
                .emitter(FxEmitter.builder("glints", glints).burst(6).duration(0).offset(0, 0.7f, 0).build())
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, BURST))
                .build();
    }

    /** Embedded sprite of {@link #waterRipple}. */
    static final String RING_SPRITE = "ring";
    /** Old ripple: radius grows 1.8 blocks/s to 2.4, held, gone after 1.63 s; 0.15 s fade-in, 55 % pale blue. */
    static final float RIPPLE_LIFE = 2.4f / 1.8f + 0.3f;

    static FxSystem waterRipple() {
        // Size is the quad's edge = the ring's diameter in the sprite's outer ring (radius 0.42 of the quad):
        // 2.4 / 0.42 × 2 ≈ 5.7 at full growth, reached at 82 % of life and held (the key takes the end back).
        float full = 5.7f;
        float grownAt = (2.4f / 1.8f) / RIPPLE_LIFE;
        float end = full / grownAt;
        ParticleEffect ring = ParticleEffect.builder("sb-water-ripple-ring")
                .lifetime(RIPPLE_LIFE, RIPPLE_LIFE).rate(0).point().speed(0, 0).spread(0)
                .startSize(0.1f, 0.1f).endSize(end, end)
                .sizeKey(0f, 1f).sizeKey(grownAt, 1f).sizeKey(1f, full / end)
                .orientation(ParticleEffect.Orientation.FLAT).texture(RING_SPRITE)
                .colors(0xD9EBFF8C, 0xD9EBFF8C)
                .fade(0.09f, 0.9f) // ~0.15 s in, then out over the rest, as before
                .priority(ParticleEffect.Priority.AMBIENT).lodDistance(48).maxLive(512).build();
        return FxSystem.builder("water_ripple")
                .emitter(FxEmitter.builder("ring", ring).burst(1).duration(0).offset(0, 0.02f, 0).build())
                .build();
    }

    /** A 64x64 white ring with soft edges and a fainter inner ring trailing it; the effect colours it. */
    static byte[] ringPng() {
        int n = 64;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double u = (x + 0.5) / n - 0.5, v = (y + 0.5) / n - 0.5;
                double r = Math.hypot(u, v);
                double outer = band(r, 0.42, 0.035);
                double inner = band(r, 0.33, 0.025) * 0.45;
                double a = Math.min(1.0, outer + inner);
                if (a <= 0) {
                    continue;
                }
                int alpha = (int) Math.round(255 * a);
                img.setRGB(x, y, (alpha << 24) | 0xFFFFFF);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 1 at {@code center}, smoothly 0 at {@code ±halfWidth}. */
    private static double band(double r, double center, double halfWidth) {
        double t = 1 - Math.abs(r - center) / halfWidth;
        return t <= 0 ? 0 : t * t * (3 - 2 * t);
    }

    private GameplayFxContent() {
    }
}
