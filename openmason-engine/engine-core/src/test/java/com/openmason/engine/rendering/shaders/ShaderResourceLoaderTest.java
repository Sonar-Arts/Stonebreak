package com.openmason.engine.rendering.shaders;

import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import com.openmason.engine.rendering.shadow.ShadowGlsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shader sources live as classpath resources and pull shared snippets in with
 * {@code #include}, which GLSL itself has no notion of — the splice happens here,
 * before the source reaches the driver.
 */
class ShaderResourceLoaderTest {

    private static final String ROOT = "/shaders/loadertest/root.glsl";

    @Test
    void loadsAPlainResource() {
        assertEquals("float leafValue() { return 1.0; }\n",
                ShaderResourceLoader.load("/shaders/loadertest/leaf.glsl"));
    }

    @Test
    void splicesNestedIncludesInPlace() {
        String source = ShaderResourceLoader.load(ROOT);

        assertTrue(source.contains("float leafValue()"), source);
        assertTrue(source.contains("float middleValue()"), source);
        assertFalse(source.contains("#include"), "directives must be consumed, not passed to the driver");
        // The #version line has to stay first, and the include lands where it was written.
        assertTrue(source.startsWith("#version 330 core\n"), source);
        assertTrue(source.indexOf("leafValue()") < source.indexOf("void main()"), source);
    }

    /** An indented directive is still a directive — the leaf sits inside an indented include. */
    @Test
    void recognisesIndentedDirectives() {
        assertTrue(ShaderResourceLoader.load(ROOT).contains("// middle before"));
    }

    /** Including the same snippet twice is a textual splice, not a guard — both copies land. */
    @Test
    void repeatedIncludesAreSplicedEachTime() {
        String source = ShaderResourceLoader.load("/shaders/loadertest/twice.glsl");
        assertEquals(2, source.split("float leafValue\\(\\)", -1).length - 1, source);
    }

    @Test
    void rejectsIncludeCycles() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ShaderResourceLoader.load("/shaders/loadertest/cycle_a.glsl"));
        assertTrue(error.getMessage().contains("Cyclic"), error.getMessage());
        assertTrue(error.getMessage().contains("cycle_b.glsl"), error.getMessage());
    }

    @Test
    void rejectsUnquotedIncludePaths() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ShaderResourceLoader.load("/shaders/loadertest/malformed.glsl"));
        assertTrue(error.getMessage().contains("quoted path"), error.getMessage());
    }

    @Test
    void reportsMissingResources() {
        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> ShaderResourceLoader.load("/shaders/loadertest/does_not_exist.glsl"));
        assertTrue(error.getMessage().contains("does_not_exist.glsl"), error.getMessage());
    }

    /** The shared shadow snippets are real resources, so they can be included by name. */
    @Test
    void shadowSnippetsResolveFromTheClasspath() {
        assertTrue(ShaderResourceLoader.load(ShadowGlsl.UNIFORMS_PATH).contains("uniform bool u_shadowsEnabled;"));
        assertTrue(ShaderResourceLoader.load(ShadowGlsl.FUNCTIONS_PATH).contains("float csmShadowFactor("));
        assertEquals(ShaderResourceLoader.load(ShadowGlsl.UNIFORMS_PATH), ShadowGlsl.UNIFORMS);
        assertEquals(ShaderResourceLoader.load(ShadowGlsl.FUNCTIONS_PATH), ShadowGlsl.FUNCTIONS);
    }
}
