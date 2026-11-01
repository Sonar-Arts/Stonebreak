package com.openmason.engine.rendering.shadow;

import com.openmason.engine.rendering.shaders.ShaderResourceLoader;

/**
 * Classpath locations of the reusable cascaded-shadow-map GLSL, plus the loaded
 * source for shaders that are still assembled in Java.
 *
 * <p>Shaders that live as resource files should include the snippets directly,
 * which keeps the GLSL in one place and out of Java string literals:</p>
 *
 * <pre>{@code
 * #include "/shaders/shadow/csm_uniforms.glsl"
 * #include "/shaders/shadow/csm_functions.glsl"
 * }</pre>
 *
 * <p>Then call {@code csmShadowFactor(worldPos, normal, viewDepth)} and multiply the
 * result into the direct-light (diffuse/specular) terms — never into ambient.
 * {@code viewDepth} is the positive view-space distance of the fragment
 * ({@code -(viewMatrix * worldPos).z}). Uniform values are applied host-side via
 * {@link ShadowUniforms}. Requires GLSL 330 and hard-codes
 * {@link ShadowSettings#CASCADE_COUNT} (3) cascades.</p>
 */
public final class ShadowGlsl {

    private ShadowGlsl() {}

    /** Classpath path of the uniform declarations, for {@code #include}. */
    public static final String UNIFORMS_PATH = "/shaders/shadow/csm_uniforms.glsl";

    /** Classpath path of {@code csmSampleCascade} + {@code csmShadowFactor}, for {@code #include}. */
    public static final String FUNCTIONS_PATH = "/shaders/shadow/csm_functions.glsl";

    /** Uniform declarations. Defaults (all-zero) mean "shadows off" — safe unconfigured. */
    public static final String UNIFORMS = ShaderResourceLoader.load(UNIFORMS_PATH);

    /** {@code csmSampleCascade} + {@code csmShadowFactor}. Paste above {@code main()}. */
    public static final String FUNCTIONS = ShaderResourceLoader.load(FUNCTIONS_PATH);
}
