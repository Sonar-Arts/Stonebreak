package com.stonebreak.fx.content;

import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary.Presets;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.fx.sbfx.SbfxDocument;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.rendering.particles.FxBinding.Target.BURST;
import static com.openmason.engine.rendering.particles.FxBinding.Target.RATE;
import static com.openmason.engine.rendering.particles.FxBinding.Target.SPEED;
import static com.openmason.engine.rendering.particles.FxBinding.Target.TINT;

/**
 * The first shipped FX (Stonebreak#437), written as engine FX systems: the bootstrap source of the
 * {@code sbfx/} files {@link FirstFxContentGeneratorTest} writes. From then on the assets are ordinary Open Mason
 * content — open the generated {@code .omfx} sources in the Particles tab, edit, and export as Stonebreak FX.
 *
 * <p>Every system follows the game's parameter conventions ({@link FxRuntime}): {@code density} thins emission,
 * {@code tint} takes the block colour, {@code intensity}/{@code amount} scale a burst.
 */
public final class FirstFxContent {

    public static final String AUTHOR = "Sonar Arts";

    /** File name (in {@code sbfx/}) → document. */
    public static Map<String, SbfxDocument> all() {
        Map<String, SbfxDocument> out = new LinkedHashMap<>();
        out.put("SB_Block_Debris.sbfx", doc(blockDebris()));
        out.put("SB_Leaf_Litter.sbfx", leafLitter());
        out.put("SB_Torch_Flame.sbfx", doc(torchFlame()));
        out.put("SB_Furnace_Smoke.sbfx", doc(furnaceSmoke()));
        out.put("SB_Water_Splash.sbfx", doc(waterSplash()));
        out.put("SB_Footstep_Dust.sbfx", doc(footstepDust()));
        return out;
    }

    private static SbfxDocument doc(FxSystem system) {
        return SbfxDocument.of(OmfxDocument.of(system).withAuthor(AUTHOR));
    }

    private static FxParameter density() {
        return FxParameter.scalar(FxRuntime.PARAM_DENSITY, 1f);
    }

    /** Chunks of the broken block (tinted like it) and a little puff of its dust; {@code amount} scales both. */
    static FxSystem blockDebris() {
        ParticleEffect chunks = Presets.DEBRIS.toBuilder().name("sb-debris-chunks")
                .lifetime(0.7f, 1.5f).box(0.32f, 0.32f, 0.32f).speed(1.4f, 3.4f).spread(60)
                .startSize(0.06f, 0.13f).endSize(0.05f, 0.1f).colorJitter(0.18f)
                .lodDistance(48).maxLive(5000).build();
        ParticleEffect puff = Presets.DUST.toBuilder().name("sb-debris-puff")
                .colors(0xFFFFFF70, 0xFFFFFF20).colorJitter(0.05f).disc(0.35f)
                .startSize(0.2f, 0.3f).endSize(0.45f, 0.6f).lodDistance(32).build();
        return FxSystem.builder("block_debris")
                .emitter(FxEmitter.builder("chunks", chunks).burst(22).duration(0).build())
                .emitter(FxEmitter.builder("puff", puff).burst(4).duration(0).build())
                .parameter(FxParameter.color(FxRuntime.PARAM_TINT, 0xFFFFFFFF))
                .parameter(FxParameter.scalar("amount", 1f))
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_TINT, TINT))
                .bind(FxBinding.of("amount", BURST))
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, BURST))
                .build();
    }

    /** Leaves torn loose: textured flakes that flutter down and settle; {@code amount} scales them. */
    static SbfxDocument leafLitter() {
        ParticleEffect flakes = ParticleEffect.builder("sb-leaf-flakes")
                .lifetime(1.4f, 2.4f).rate(0).box(0.35f, 0.35f, 0.35f)
                .speed(0.8f, 2.0f).direction(0, 1, 0).spread(70)
                .gravity(0, -4.5f, 0).drag(1.6f).wind(1.2f)
                .size(0.13f, 0.11f).color(0xFFFFFFFF).colorJitter(0.15f)
                .fade(0f, 0.2f).texture("leaf").rotation(0, 360).spin(-240, 240)
                .collides(0.05f, 0.9f).lodDistance(48).maxLive(3000).build();
        FxSystem system = FxSystem.builder("leaf_litter")
                .emitter(FxEmitter.builder("flakes", flakes).burst(16).duration(0).build())
                .parameter(FxParameter.color(FxRuntime.PARAM_TINT, 0xFFFFFFFF))
                .parameter(FxParameter.scalar("amount", 1f))
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_TINT, TINT))
                .bind(FxBinding.of("amount", BURST))
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, BURST))
                .build();
        return SbfxDocument.of(OmfxDocument.of(system).withAuthor(AUTHOR)
                .withSprite(new OmfxSprite("leaf", leafPng())));
    }

    /** A placed torch's live flame tongue and the embers it sheds. No FX light: the torch already lights. */
    static FxSystem torchFlame() {
        ParticleEffect flame = Presets.FLAME.toBuilder().name("sb-torch-flame")
                .rate(26).disc(0.025f).size(0.085f, 0.015f).lifetime(0.22f, 0.4f)
                .lodDistance(40).build();
        ParticleEffect embers = Presets.EMBERS.toBuilder().name("sb-torch-embers")
                .rate(1.6f).disc(0.04f).speed(0.3f, 0.8f).size(0.035f, 0.015f).lifetime(0.7f, 1.4f)
                .lodDistance(24).build();
        return FxSystem.builder("torch_flame")
                .emitter(FxEmitter.builder("flame", flame).build())
                .emitter(FxEmitter.builder("embers", embers).offset(0, 0.05f, 0).build())
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, RATE))
                .build();
    }

    /** Thin smoke curling up from a lit furnace. */
    static FxSystem furnaceSmoke() {
        ParticleEffect smoke = Presets.SMOKE.toBuilder().name("sb-furnace-smoke")
                .rate(3.5f).disc(0.12f).speed(0.3f, 0.6f).lifetime(2.4f, 3.8f)
                .startSize(0.22f, 0.32f).endSize(0.9f, 1.25f).colors(0x46464AB0, 0x8E8E9450)
                .priority(ParticleEffect.Priority.AMBIENT).lodDistance(64).build();
        return FxSystem.builder("furnace_smoke")
                .emitter(FxEmitter.builder("smoke", smoke).build())
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, RATE))
                .build();
    }

    /** Breaking the water surface: droplets thrown up and a ring; {@code intensity} = how hard. */
    static FxSystem waterSplash() {
        ParticleEffect droplets = Presets.SPLASH.toBuilder().name("sb-splash-droplets")
                .disc(0.3f).speed(1.8f, 4.4f).lodDistance(64).build();
        ParticleEffect ring = Presets.RIPPLE.toBuilder().name("sb-splash-ring")
                .size(0.15f, 1.35f).lifetime(0.6f, 0.8f).priority(ParticleEffect.Priority.NORMAL).build();
        ParticleEffect mist = Presets.DUST.toBuilder().name("sb-splash-mist")
                .colors(0xE8F4FF60, 0xE8F4FF10).gravity(0, -0.6f, 0).speed(0.6f, 1.4f)
                .startSize(0.25f, 0.35f).endSize(0.6f, 0.8f).lodDistance(48).build();
        return FxSystem.builder("water_splash")
                .emitter(FxEmitter.builder("droplets", droplets).burst(30).duration(0).build())
                .emitter(FxEmitter.builder("ring", ring).burst(1).duration(0).offset(0, 0.02f, 0).build())
                .emitter(FxEmitter.builder("mist", mist).burst(6).duration(0).build())
                .parameter(FxParameter.scalar(FxRuntime.PARAM_INTENSITY, 1f))
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_INTENSITY, BURST, "droplets"))
                .bind(FxBinding.of(FxRuntime.PARAM_INTENSITY, SPEED, "droplets"))
                .bind(FxBinding.of(FxRuntime.PARAM_INTENSITY, BURST, "mist"))
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, BURST))
                .build();
    }

    /** A small kick of dust under a footstep on loose ground, coloured like the ground. */
    static FxSystem footstepDust() {
        ParticleEffect dust = Presets.DUST.toBuilder().name("sb-footstep-dust")
                .colors(0xFFFFFF60, 0xFFFFFF18).colorJitter(0.06f).disc(0.18f)
                .speed(0.25f, 0.7f).spread(75).startSize(0.1f, 0.16f).endSize(0.24f, 0.34f)
                .lifetime(0.45f, 0.8f).lodDistance(32).build();
        return FxSystem.builder("footstep_dust")
                .emitter(FxEmitter.builder("dust", dust).burst(3).duration(0).build())
                .parameter(FxParameter.color(FxRuntime.PARAM_TINT, 0xFFFFFFFF))
                .parameter(density())
                .bind(FxBinding.of(FxRuntime.PARAM_TINT, TINT))
                .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, BURST))
                .build();
    }

    /** A 16x16 white leaf (veined, soft-edged): the block tint colours it. */
    static byte[] leafPng() {
        int n = 16;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                // Leaf = intersection of two discs (a lens) along the diagonal.
                double u = (x + 0.5) / n - 0.5, v = (y + 0.5) / n - 0.5;
                double a = (u + v) / Math.sqrt(2), b = (u - v) / Math.sqrt(2);
                double d1 = Math.hypot(a, b - 0.26), d2 = Math.hypot(a, b + 0.26);
                double inside = Math.min(0.48 - d1, 0.48 - d2);
                if (inside <= 0) {
                    continue;
                }
                double edge = Math.min(1, inside / 0.05);
                boolean vein = Math.abs(b) < 0.03 && Math.abs(a) < 0.4;
                int shade = vein ? 205 : (int) (232 + 23 * (a + 0.5) / 1.0);
                shade = Math.min(255, shade);
                int alpha = (int) Math.round(255 * edge);
                img.setRGB(x, y, (alpha << 24) | (shade << 16) | (shade << 8) | shade);
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

    /** Ids of every first-content system, for binding tests. */
    public static List<String> objectIds() {
        return all().values().stream().map(SbfxDocument::objectId).toList();
    }

    private FirstFxContent() {
    }
}
