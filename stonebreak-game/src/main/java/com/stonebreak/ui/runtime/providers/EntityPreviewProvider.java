package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.sbe.EntityAttachments;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code stonebreak:entity-preview}: a slowly orbiting 3D model, as the character creation
 * screen, the character sheet Overview tab and the glossary drew it with raw GL (ledger Hard
 * visual #1). A {@code DrawProvider} element chooses the model with its {@code params}:
 *
 * <ul>
 *   <li>{@code entity}: {@code "player"} (the local player with their equipped hair and hat) or
 *       an {@link EntityType} name ({@code "COW"}, case-insensitive)</li>
 *   <li>{@code variant} (default {@code "Default"}), {@code state} (mobs; default {@code "Idle"})</li>
 *   <li>{@code elevation} camera elevation in degrees (default 12 for the player, 18 for mobs),
 *       {@code orbitSpeed} radians per second (default 0.6), {@code margin} framing factor
 *       (default 1.15 player / 1.3 with a hat / 1.25 mobs), {@code yaw} fixed azimuth in degrees
 *       instead of orbiting</li>
 * </ul>
 *
 * Framing, field of view and the orbit clock ({@code getTotalTimeElapsed}) match the legacy
 * renderers, so a migrated screen reproduces their look.
 */
public final class EntityPreviewProvider extends GlPreviewProvider {

    public static final String ID = "stonebreak:entity-preview";
    public static final int VERSION = 1;
    public static final String PLAYER = "player";

    private static final float FOV = (float) Math.toRadians(35.0);

    private final Map<String, float[]> boundsCache = new HashMap<>();

    @Override
    protected boolean render(UiElement element, int width, int height) {
        Renderer renderer = Game.getRenderer();
        EntityRenderer entities = renderer == null ? null : renderer.getEntityRenderer();
        Game game = Game.getInstance();
        if (entities == null || game == null) {
            return false;
        }
        UiValue.Obj p = element.prop("params") instanceof UiValue.Obj o ? o : UiValue.Obj.EMPTY;
        String entity = text(p, "entity", PLAYER);
        String variant = text(p, "variant", SbeEntityAsset.DEFAULT_VARIANT);
        boolean player = PLAYER.equalsIgnoreCase(entity);
        EntityType type = player ? EntityType.REMOTE_PLAYER : entityType(entity);
        if (type == null) {
            return false;
        }
        float[] b = bounds(type, player ? SbeEntityAsset.DEFAULT_VARIANT : variant);
        if (b == null) {
            return false;
        }
        float time = game.getTotalTimeElapsed();
        Orbit orbit = orbit(p, player, b, time, (float) width / height);

        if (player) {
            entities.renderPlayerPreview(null, time, new Vector3f(), 0f, new Vector3f(1f, 1f, 1f),
                orbit.view(), orbit.projection(), EntityAttachments.LOCAL_PLAYER);
        } else {
            entities.renderEntityPreview(type, variant, text(p, "state", "Idle"), time, new Vector3f(), 0f,
                new Vector3f(1f, 1f, 1f), orbit.view(), orbit.projection());
        }
        return true;
    }

    /** Camera of the legacy previews: look at the AABB centre from a distance that frames its sphere. */
    record Orbit(Matrix4f view, Matrix4f projection) {
    }

    static Orbit orbit(UiValue.Obj p, boolean player, float[] b, float time, float aspect) {
        float defaultMargin = player
            ? (EntityAttachments.get(EntityAttachments.LOCAL_PLAYER).isEmpty() ? 1.15f : 1.3f)
            : 1.25f;
        float margin = (float) number(p, "margin", defaultMargin);
        float el = (float) Math.toRadians(number(p, "elevation", player ? 12.0 : 18.0));
        UiValue yaw = p.get("yaw");
        float az = yaw instanceof UiValue.Num n
            ? (float) Math.toRadians(n.value())
            : time * (float) number(p, "orbitSpeed", 0.6);
        return frame(b, az, el, margin, aspect);
    }

    static Orbit frame(float[] b, float az, float el, float margin, float aspect) {
        float halfFovTan = (float) Math.tan(FOV / 2f);
        float ctrX = (b[0] + b[3]) / 2f, ctrY = (b[1] + b[4]) / 2f, ctrZ = (b[2] + b[5]) / 2f;
        float ex = b[3] - b[0], ey = b[4] - b[1], ez = b[5] - b[2];
        float radius = 0.5f * (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (radius <= 0f) {
            radius = 0.5f;
        }
        float dist = radius / halfFovTan * margin;
        float horiz = dist * (float) Math.cos(el);
        float eyeX = ctrX + horiz * (float) Math.sin(az);
        float eyeZ = ctrZ + horiz * (float) Math.cos(az);
        float eyeY = ctrY + dist * (float) Math.sin(el);
        Matrix4f view = new Matrix4f().setLookAt(eyeX, eyeY, eyeZ, ctrX, ctrY, ctrZ, 0f, 1f, 0f);
        Matrix4f proj = new Matrix4f().setPerspective(FOV, aspect, 0.05f, dist + radius * 4f);
        return new Orbit(view, proj);
    }

    static EntityType entityType(String name) {
        try {
            return EntityType.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Model AABB {minX, minY, minZ, maxX, maxY, maxZ} of a variant, cached; null when unavailable. */
    private float[] bounds(EntityType type, String variant) {
        String key = type.getSbeObjectId() + "/" + variant;
        float[] cached = boundsCache.get(key);
        if (cached != null) {
            return cached;
        }
        SbeEntityAsset asset = SbeEntityRegistry.get(type.getSbeObjectId());
        SbeModelGeometry geo = asset == null ? null : asset.geometryFor(variant);
        float[] v = geo == null ? null : geo.vertices();
        if (v == null || v.length < 3) {
            return null;
        }
        float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i + 2 < v.length; i += 3) {
            for (int a = 0; a < 3; a++) {
                out[a] = Math.min(out[a], v[i + a]);
                out[a + 3] = Math.max(out[a + 3], v[i + a]);
            }
        }
        boundsCache.put(key, out);
        return out;
    }

    private static String text(UiValue.Obj p, String key, String fallback) {
        return p.get(key) instanceof UiValue.Str s && !s.value().isBlank() ? s.value() : fallback;
    }

    private static double number(UiValue.Obj p, String key, double fallback) {
        return p.get(key) instanceof UiValue.Num n ? n.value() : fallback;
    }
}
