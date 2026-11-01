package com.openmason.engine.rendering.viewer.scene;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Produces a loaded model for {@link ModelCache}.
 *
 * <p>Same reasoning as {@link TextureUploader}: the cache's real logic is refcounting and
 * eviction, and pinning that down should not require parsing an OMO or holding a GL
 * context. {@link OmoModelLoader} is the production implementation.
 */
public interface ModelSource {

    OmoModelLoader.Loaded load(Path path) throws IOException;

    OmoModelLoader.Loaded load(byte[] omoBytes, String displayName) throws IOException;
}
