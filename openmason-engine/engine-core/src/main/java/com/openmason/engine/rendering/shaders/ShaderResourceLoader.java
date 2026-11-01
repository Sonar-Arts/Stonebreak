package com.openmason.engine.rendering.shaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loads GLSL shader source files from the classpath, resolving {@code #include} directives.
 *
 * <p>Shared by every renderer that keeps its shaders as resources (e.g. {@code /shaders/sky/sky.vert});
 * renderers should use this instead of duplicating the read-resource boilerplate.</p>
 *
 * <p>A line whose first non-whitespace token is {@code #include} is replaced by the contents of the
 * quoted classpath resource, which may itself include further files:</p>
 *
 * <pre>{@code #include "/shaders/shadow/csm_functions.glsl"}</pre>
 *
 * <p>GLSL has no include mechanism of its own, so this is a plain textual splice performed before the
 * source reaches the driver. Paths are absolute classpath locations; include cycles are rejected.</p>
 *
 * <h2>Shaders outside the engine</h2>
 *
 * <p>Resources are encapsulated per module and {@link Class#getResourceAsStream} is caller-sensitive,
 * so this class cannot read another module's resources on its behalf. A caller outside the engine
 * passes its own {@link ResourceOpener}, written as a lambda so the read happens in the caller's
 * module:</p>
 *
 * <pre>{@code ShaderResourceLoader.load("/shaders/world/world.frag",
 *         path -> MyRenderer.class.getResourceAsStream(path));}</pre>
 *
 * <p>Anything the caller's opener cannot find falls back to the engine's own resources, so a game or
 * tool shader can include the shared engine snippets by path.</p>
 */
public final class ShaderResourceLoader {

    private static final String INCLUDE_DIRECTIVE = "#include";

    /**
     * Opens a classpath resource from the caller's module.
     *
     * <p>Implement as a lambda (not a method reference) so the caller-sensitive resource lookup is
     * performed by the calling module rather than by whoever invokes it.</p>
     */
    @FunctionalInterface
    public interface ResourceOpener {
        /** @return the resource stream, or null if this module does not have it */
        InputStream open(String resourcePath);
    }

    private ShaderResourceLoader() {
    }

    /**
     * Reads an engine shader source file and splices in its includes.
     *
     * @param resourcePath absolute classpath location, e.g. {@code /shaders/postfx/fullscreen.vert}
     * @return the fully resolved shader source as a UTF-8 string
     * @throws UncheckedIOException if the resource (or one of its includes) is missing or unreadable
     * @throws IllegalStateException if the includes form a cycle
     */
    public static String load(String resourcePath) {
        return load(resourcePath, path -> ShaderResourceLoader.class.getResourceAsStream(path));
    }

    /**
     * Reads a shader source file through the caller's own resource lookup and splices in its includes.
     *
     * @param resourcePath absolute classpath location, e.g. {@code /shaders/world/world.frag}
     * @param opener       the calling module's resource lookup; see {@link ResourceOpener}
     * @return the fully resolved shader source as a UTF-8 string
     * @throws UncheckedIOException if the resource (or one of its includes) is missing or unreadable
     * @throws IllegalStateException if the includes form a cycle
     */
    public static String load(String resourcePath, ResourceOpener opener) {
        return resolve(resourcePath, opener, new LinkedHashSet<>());
    }

    private static String resolve(String resourcePath, ResourceOpener opener, Set<String> includeChain) {
        if (!includeChain.add(resourcePath)) {
            throw new IllegalStateException(
                    "Cyclic shader #include: " + String.join(" -> ", includeChain) + " -> " + resourcePath);
        }
        StringBuilder resolved = new StringBuilder();
        String[] lines = read(resourcePath, opener).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                resolved.append('\n');
            }
            String included = includedPath(lines[i]);
            if (included == null) {
                resolved.append(lines[i]);
            } else {
                // The included file ends with its own newline; the separator above supplies the
                // line break, so drop it rather than opening a blank line at every splice.
                resolved.append(stripTrailingNewline(resolve(included, opener, includeChain)));
            }
        }
        includeChain.remove(resourcePath);
        return resolved.toString();
    }

    private static String stripTrailingNewline(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    /** The quoted path of an {@code #include} line, or null if this is not an include directive. */
    private static String includedPath(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(INCLUDE_DIRECTIVE)) {
            return null;
        }
        int open = trimmed.indexOf('"');
        int close = trimmed.lastIndexOf('"');
        if (open < 0 || close <= open) {
            throw new IllegalStateException("Malformed shader #include (expected a quoted path): " + trimmed);
        }
        return trimmed.substring(open + 1, close);
    }

    private static String read(String resourcePath, ResourceOpener opener) {
        try (InputStream inputStream = openWithEngineFallback(resourcePath, opener)) {
            if (inputStream == null) {
                throw new IOException("Shader resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load shader resource: " + resourcePath, e);
        }
    }

    /** Caller's module first, then the engine — a caller's shader may include a shared engine snippet. */
    private static InputStream openWithEngineFallback(String resourcePath, ResourceOpener opener) {
        InputStream stream = opener.open(resourcePath);
        return stream != null ? stream : ShaderResourceLoader.class.getResourceAsStream(resourcePath);
    }
}
