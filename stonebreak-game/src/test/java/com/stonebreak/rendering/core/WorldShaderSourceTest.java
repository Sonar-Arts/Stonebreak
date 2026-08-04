package com.stonebreak.rendering.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world shader is loaded from resources at startup, so a renamed file or an
 * unresolvable {@code #include} would only surface as a crash on the first frame
 * with a GL context. These checks catch it headlessly.
 */
class WorldShaderSourceTest {

    private static final String VERTEX = "/shaders/world/world.vert";
    private static final String FRAGMENT = "/shaders/world/world.frag";

    @Test
    void bothStagesResolveFromTheClasspath() {
        assertTrue(ResourceManager.loadWorldShader(VERTEX).startsWith("#version 330 core"));
        assertTrue(ResourceManager.loadWorldShader(FRAGMENT).startsWith("#version 330 core"));
    }

    @Test
    void fragmentStagePullsInTheSharedShadowGlsl() {
        String source = ResourceManager.loadWorldShader(FRAGMENT);

        assertFalse(source.contains("#include"), "includes must be resolved before compilation");
        assertTrue(source.contains("uniform sampler2DArrayShadow u_shadowMap;"), "shadow uniforms missing");
        assertTrue(source.contains("float csmShadowFactor("), "shadow functions missing");
        // csmShadowFactor is called from main(), so its definition has to precede it.
        assertTrue(source.indexOf("float csmShadowFactor(") < source.indexOf("void main()"), source);
    }

    /**
     * Every uniform {@link ResourceManager} registers must exist in the source, or the
     * registration silently binds nothing and the value never reaches the GPU.
     */
    @Test
    void declaresEveryUniformTheRendererSets() {
        String combined = ResourceManager.loadWorldShader(VERTEX) + "\n" + ResourceManager.loadWorldShader(FRAGMENT);

        for (String uniform : new String[] {
                "projectionMatrix", "viewMatrix", "modelMatrix", "texture_sampler", "block_sampler",
                "u_useTextureArray", "u_layerOverride", "u_forceAlphaTest", "u_color", "u_useSolidColor",
                "u_isText", "u_transformUVsForItem", "u_atlasUVOffset", "u_atlasUVScale", "u_renderPass",
                "u_isUIElement", "u_cameraPos", "u_underwaterFogDensity", "u_underwaterFogColor",
                "u_ambientLight", "u_sunDirection", "u_viewPos", "u_playerLight", "u_lodFade",
                "u_fogColor", "u_fogStart", "u_fogEnd", "u_shadowMap", "u_shadowsEnabled" }) {
            assertTrue(combined.contains("uniform ") && combined.contains(uniform),
                    "shader source is missing uniform " + uniform);
        }
    }

    /** The vertex stage feeds these varyings; a rename on either side is a silent link failure. */
    @Test
    void varyingsMatchAcrossStages() {
        String vertex = ResourceManager.loadWorldShader(VERTEX);
        String fragment = ResourceManager.loadWorldShader(FRAGMENT);

        for (String varying : new String[] {
                "outTexCoord", "outNormal", "fragPos", "v_isWater", "v_isAlphaTested",
                "v_isTranslucent", "v_light", "v_layer", "v_viewDepth" }) {
            assertTrue(vertex.contains("out float " + varying)
                            || vertex.contains("out vec2 " + varying)
                            || vertex.contains("out vec3 " + varying),
                    "vertex stage does not output " + varying);
            assertTrue(fragment.contains("in float " + varying)
                            || fragment.contains("in vec2 " + varying)
                            || fragment.contains("in vec3 " + varying),
                    "fragment stage does not read " + varying);
        }
    }
}
