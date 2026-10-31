package com.openmason.api.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Reads a UTF-8 text resource for {@link GuideTopic}/{@link KnowledgePack} contents.
 *
 * <p>Resource lookup in a named module is caller-sensitive: a module's resources in a folder that
 * counts as one of its packages ({@code /mcp/kb/x.md} = package {@code mcp.kb}) are only visible to
 * code in that module. {@link #of(Class, String)} reads from inside the tool, so it only serves the
 * tool's own resources; a plugin passes a lambda written in its own module to
 * {@link #of(Opener, String)}: {@code ResourceText.of(MyPlugin.class::getResourceAsStream, "/kb/x.md")}.
 */
public final class ResourceText {

    /** Opens a resource by absolute path; null when it is missing. Write it in the module that owns the resource. */
    @FunctionalInterface
    public interface Opener {
        InputStream open(String path) throws IOException;
    }

    private ResourceText() {
    }

    /** The tool's own resource {@code path} (an absolute path is looked up from the root of {@code anchor}'s module). */
    public static Supplier<String> of(Class<?> anchor, String path) {
        return of(anchor::getResourceAsStream, path);
    }

    /** Resource {@code path} through {@code opener}, read on each call. */
    public static Supplier<String> of(Opener opener, String path) {
        return () -> {
            try (InputStream in = opener.open(path)) {
                if (in == null) {
                    throw new IllegalStateException("Missing resource " + path);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
