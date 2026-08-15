package com.stonebreak.rendering.core;

import com.stonebreak.ui.Font;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;

/**
 * Owns the shared world shader program and the UI font.
 *
 * <p>The shader's GLSL lives in {@code resources/shaders/world/}, like every other
 * renderer's; this class only compiles it and registers its uniforms.</p>
 */
public class ResourceManager {
    private static final String VERTEX_SHADER = "/shaders/world/world.vert";
    private static final String FRAGMENT_SHADER = "/shaders/world/world.frag";

    private ShaderProgram shaderProgram;
    private Font font;

    public ResourceManager() {
    }

    public void initialize(int textureAtlasSize) {
        font = new Font("fonts/Roboto-VariableFont_wdth,wght.ttf", 24f);
    }

    public void initializeShaderProgram() {
        shaderProgram = new ShaderProgram();
        shaderProgram.createVertexShader(loadWorldShader(VERTEX_SHADER));
        shaderProgram.createFragmentShader(loadWorldShader(FRAGMENT_SHADER));
        shaderProgram.link();

        createShaderUniforms();
    }
    
    /**
     * Reads a world shader and splices in its {@code #include}s. The lambda keeps the
     * caller-sensitive resource lookup inside this module; shared engine snippets
     * (e.g. the shadow GLSL) fall through to the engine's own resources.
     */
    static String loadWorldShader(String resourcePath) {
        return ShaderResourceLoader.load(resourcePath, path -> ResourceManager.class.getResourceAsStream(path));
    }

    private void createShaderUniforms() {
        // Bind the program before any setUniform call below — glUniform* operates
        // on the *currently bound* program, and nothing has bound it since link().
        // Without this, sampler assignments (e.g. block_sampler -> unit 1) silently
        // fail, leaving block_sampler and texture_sampler both on unit 0. Two
        // different sampler types on one unit make every draw call a no-op
        // (GL_INVALID_OPERATION).
        shaderProgram.bind();
        shaderProgram.createUniform("projectionMatrix");
        shaderProgram.createUniform("viewMatrix");
        shaderProgram.createUniform("modelMatrix");
        shaderProgram.createUniform("texture_sampler");
        // Block texture array sampler (texture unit 1). World/voxel geometry
        // samples this; text/UI keep using the 2D texture_sampler on unit 0.
        shaderProgram.createUniform("block_sampler");
        shaderProgram.setUniform("block_sampler", 1);
        shaderProgram.createUniform("u_useTextureArray");
        shaderProgram.setUniform("u_useTextureArray", false);
        // Layer override for geometry without a per-vertex layer attribute
        // (e.g. flat UI quads). -1 = use the per-vertex v_layer.
        shaderProgram.createUniform("u_layerOverride");
        shaderProgram.setUniform("u_layerOverride", -1.0f);
        // Forces alpha-test discard for geometry without a per-vertex alpha
        // flag (e.g. flower cross meshes used for drops/icons).
        shaderProgram.createUniform("u_forceAlphaTest");
        shaderProgram.setUniform("u_forceAlphaTest", false);
        shaderProgram.createUniform("u_color");
        shaderProgram.createUniform("u_useSolidColor");
        shaderProgram.createUniform("u_isText");
        shaderProgram.createUniform("u_transformUVsForItem");
        shaderProgram.createUniform("u_atlasUVOffset");
        shaderProgram.createUniform("u_atlasUVScale");
        shaderProgram.createUniform("u_renderPass");
        shaderProgram.createUniform("u_isUIElement");
        shaderProgram.createUniform("u_cameraPos");
        shaderProgram.createUniform("u_underwaterFogDensity");
        shaderProgram.createUniform("u_underwaterFogColor");
        shaderProgram.createUniform("u_ambientLight");
        shaderProgram.createUniform("u_sunDirection");
        shaderProgram.createUniform("u_viewPos");
        shaderProgram.createUniform("u_playerLight");
        // Default to -1 so terrain (which never sets this) falls through to the per-vertex light.
        shaderProgram.setUniform("u_playerLight", -1.0f);
        // FastLOD crossfade opacity — 1.0 for everything except LOD nodes
        // mid-transition (screen-door dither discard, no blending). The LOD
        // pass sets it per node and restores 1.0 before the next pass.
        shaderProgram.createUniform("u_lodFade");
        shaderProgram.setUniform("u_lodFade", 1.0f);
        // Atmospheric distance fog — fogEnd <= fogStart disables; WorldRenderer
        // sets these per frame (sky color + LOD ring bounds).
        shaderProgram.createUniform("u_fogColor");
        shaderProgram.createUniform("u_fogStart");
        shaderProgram.createUniform("u_fogEnd");
        shaderProgram.setUniform("u_fogStart", 0.0f);
        shaderProgram.setUniform("u_fogEnd", 0.0f);
        // Shadow map sampler MUST be moved off unit 0 immediately: it is a
        // sampler2DArrayShadow, and leaving it on the same unit as the 2D
        // texture_sampler makes every draw GL_INVALID_OPERATION on strict
        // drivers even while shadows are disabled. Remaining shadow uniforms
        // are auto-registered by ShadowUniforms' tolerant setters per frame.
        shaderProgram.setInt("u_shadowMap",
                com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer.SHADOW_TEXTURE_UNIT);
        shaderProgram.setBool("u_shadowsEnabled", false);
    }
    
    public ShaderProgram getShaderProgram() {
        return shaderProgram;
    }
    
    public Font getFont() {
        return font;
    }
    
    public void cleanup() {
        if (shaderProgram != null) {
            shaderProgram.cleanup();
        }
        if (font != null) {
            font.cleanup();
        }
    }
}
