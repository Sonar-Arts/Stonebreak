package com.stonebreak.rendering.lighting;

import com.openmason.engine.rendering.shaders.ShaderResourceLoader;

/**
 * The dynamic point-light GLSL snippet as Java strings, for renderers that
 * assemble their shader source inline (the SBE entity renderer). File-backed
 * shaders include the same source directly:
 * {@code #include "/shaders/lighting/point_lights.glsl"}.
 *
 * <p>{@link #UNIFORMS} and {@link #FUNCTIONS} are split so the uniforms can be
 * declared ahead of any other shared block; both are loaded from the single
 * resource file so there is exactly one definition of the lighting math.
 */
public final class PointLightGlsl {

    public static final String PATH = "/shaders/lighting/point_lights.glsl";

    /**
     * Maximum lights the shaders accept per draw — must match {@code MAX_POINT_LIGHTS}.
     * Deliberately generous (effectively unbounded for the 64-block gather radius):
     * 256 lights is 2048 uniform components, inside every real driver's fragment
     * uniform budget. Moving to a UBO/SSBO is the next step if this ever binds.
     */
    public static final int MAX_LIGHTS = 256;

    public static final String UNIFORMS;
    public static final String FUNCTIONS;

    static {
        String source = ShaderResourceLoader.load(PATH, path -> PointLightGlsl.class.getResourceAsStream(path));
        int split = source.indexOf("vec3 pointLightContribution");
        if (split < 0) {
            throw new IllegalStateException("point_lights.glsl: missing pointLightContribution()");
        }
        UNIFORMS = source.substring(0, split) + "\n";
        FUNCTIONS = source.substring(split) + "\n";
    }

    private PointLightGlsl() {}
}
