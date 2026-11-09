package com.stonebreak.rendering.gameWorld.particles;

import com.openmason.engine.rendering.particles.ParticleShaderExtension;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.lighting.PointLightGlsl;

/**
 * The game's GLSL for the engine particle shader (Stonebreak#436), spliced into its vertex shader — both hooks run
 * per quad corner, never per fragment:
 * <ul>
 *   <li>{@code particleLight}: the scene ambient (already scaled for caves by the pass), plus this frame's torch
 *       point lights ({@code DynamicLights} uploads the same {@code u_pointLight*} arrays the world shader reads),
 *       omnidirectional and unshadowed — particles are tiny and have no surface normal. Same falloff curve, the
 *       same daylight suppression and the same soft cap as {@code point_lights.glsl}.</li>
 *   <li>{@code particleColor}: the rain/snow fog ({@code precip_fog.glsl}, shared with every world surface) and
 *       the atmospheric distance fog toward the sky colour, measured horizontally like the world shader's. Applied
 *       to every particle, premultiplied: additive ones (a = 0) fade, others are covered. Then the particle is
 *       composited behind any cloud nearer than it ({@code clouds_in_front.glsl}'s rule, once per particle: the
 *       clouds were drawn before the particles and write no depth).</li>
 * </ul>
 */
public final class ParticleShaderGlsl {

    /** {@code precip_fog.glsl}, loaded from the game's resources (the engine cannot read them, JPMS). */
    static final String PRECIP_FOG = ShaderResourceLoader.load("/shaders/weather/precip_fog.glsl",
            path -> ParticleShaderGlsl.class.getResourceAsStream(path));

    static final String GLSL = """
            // ---- Stonebreak particle hooks (ParticleShaderGlsl) ----
            #define SB_MAX_POINT_LIGHTS %d
            uniform int u_pointLightCount;
            uniform vec4 u_pointLightPos[SB_MAX_POINT_LIGHTS];   // xyz = render-space position, w = radius
            uniform vec4 u_pointLightColor[SB_MAX_POINT_LIGHTS]; // rgb = colour * intensity
            uniform float u_sbSkyExposure;   // how open to the sky the camera is (0 = cave, 1 = outdoors)
            uniform float u_sbFogStart;      // distance fog, horizontal blocks (end <= start = off)
            uniform float u_sbFogEnd;
            uniform vec3 u_sbFogColor;
            // clouds_in_front.glsl's inputs (CloudsInFront units); u_cloudsInFront false = no clouds this frame.
            uniform sampler2D u_cloudBuffer;
            uniform sampler2D u_cloudDistanceBuffer;
            uniform bool u_cloudsInFront;
            uniform float u_cloudSlabBottom;
            uniform float u_cloudSlabTop;

            %s

            // clouds_in_front.glsl's behindClouds for a premultiplied particle at renderPos. Vertex stage, so the
            // screen position comes from the projection instead of gl_FragCoord. The frame holds
            // cloud + T * scene; writing T * color + a * cloud at alpha a gives cloud + T * (color + (1 - a) scene).
            vec4 sbBehindClouds(vec4 color, vec3 renderPos) {
                if (!u_cloudsInFront) return color;
                vec3 ray = renderPos - u_camPos;
                float d = length(ray);
                float dy = ray.y / max(d, 1e-4);
                if (u_camPos.y < u_cloudSlabBottom) {
                    if (dy <= 1e-5 || d * dy < u_cloudSlabBottom - u_camPos.y) return color;
                } else if (u_camPos.y > u_cloudSlabTop) {
                    if (dy >= -1e-5 || d * dy > u_cloudSlabTop - u_camPos.y) return color;
                }
                vec4 clip = u_viewProj * vec4(renderPos, 1.0);
                if (clip.w <= 1e-4) return color;
                vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return color;
                float cloudDist = textureLod(u_cloudDistanceBuffer, uv, 0.0).r;
                if (cloudDist < 0.0) return color;
                float inFront = 1.0 - smoothstep(d * 0.95, d * 1.05, cloudDist);
                vec4 cloud = textureLod(u_cloudBuffer, uv, 0.0);
                return mix(color, vec4(color.rgb * cloud.a + cloud.rgb * color.a, color.a), inFront);
            }

            vec3 particleLight(vec3 renderPos) {
                vec3 light = vec3(u_ambient);
                float daylight = clamp((u_ambient - 0.3) / 0.7, 0.0, 1.0);
                float weight = 1.0 - daylight * clamp(u_sbSkyExposure, 0.0, 1.0);
                if (weight <= 0.0 || u_pointLightCount == 0) return light;
                vec3 sum = vec3(0.0);
                for (int i = 0; i < SB_MAX_POINT_LIGHTS; i++) {
                    if (i >= u_pointLightCount) break;
                    vec4 l = u_pointLightPos[i];
                    vec3 d = l.xyz - renderPos;
                    float distSq = dot(d, d);
                    if (distSq >= l.w * l.w) continue;
                    float x = 1.0 - sqrt(distSq) / l.w;
                    float falloff = x * x * (3.0 - 2.0 * x);
                    sum += u_pointLightColor[i].rgb * (falloff * falloff);
                }
                // point_lights.glsl's soft cap (POINT_LIGHT_MAX 0.95), into the remaining headroom.
                vec3 torch = 0.95 * (vec3(1.0) - exp(-(sum * weight) / 0.95));
                return light + max(vec3(0.95) - light, vec3(0.0)) * (torch / 0.95);
            }

            vec4 particleColor(vec4 color, vec3 renderPos) {
                vec3 toP = renderPos - u_camPos;
                float d = length(toP);
                vec4 f = precipFog(u_camPos, toP / max(d, 1e-4), d);
                vec3 rgb = color.rgb * f.a + f.rgb * color.a;
                if (u_sbFogEnd > u_sbFogStart) {
                    float h = clamp((length(toP.xz) - u_sbFogStart) / (u_sbFogEnd - u_sbFogStart), 0.0, 1.0);
                    rgb = mix(rgb, u_sbFogColor * color.a, h);
                }
                return sbBehindClouds(vec4(rgb, color.a), renderPos);
            }
            """.formatted(PointLightGlsl.MAX_LIGHTS, PRECIP_FOG);

    private ParticleShaderGlsl() {
    }

    /** The extension: both hooks. */
    public static ParticleShaderExtension extension() {
        return ParticleShaderExtension.of(GLSL, true, true);
    }
}
