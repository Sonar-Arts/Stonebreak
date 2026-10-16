package com.openmason.api.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** Reads a text resource of the caller's module, for {@link GuideTopic}/{@link KnowledgePack} contents. */
public final class ResourceText {

    private ResourceText() {
    }

    /**
     * The UTF-8 text of resource {@code path} next to {@code anchor} (an absolute path such as
     * {@code /mcp/guide/overview.md} is looked up from the root of {@code anchor}'s module). Pass a
     * class of the module that holds the resource: a module's resources are read through its own
     * classes.
     */
    public static Supplier<String> of(Class<?> anchor, String path) {
        return () -> {
            try (InputStream in = anchor.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Missing resource " + path + " (module of " + anchor.getName() + ")");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
