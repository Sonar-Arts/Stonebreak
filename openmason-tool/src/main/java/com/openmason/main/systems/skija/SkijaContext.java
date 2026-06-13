package com.openmason.main.systems.skija;

import io.github.humbleui.skija.DirectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the single Skia {@link DirectContext} wrapping the application's GLFW
 * OpenGL context. One instance per application, created on the GL thread after
 * ImGui initialization and disposed before the GL context is destroyed.
 *
 * Offscreen surfaces ({@link SkijaOffscreenSurface}) share this context; they
 * must all be created, painted, and disposed on the same GL thread.
 */
public final class SkijaContext implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SkijaContext.class);

    private static SkijaContext instance;

    private DirectContext context;

    private SkijaContext(DirectContext context) {
        this.context = context;
    }

    /**
     * Create the application-wide context. Must be called on the GL thread
     * with a current OpenGL context.
     */
    public static synchronized SkijaContext initialize() {
        if (instance != null) {
            logger.warn("SkijaContext already initialized — reusing existing instance");
            return instance;
        }
        instance = new SkijaContext(DirectContext.makeGL());
        logger.info("Skija DirectContext created");
        return instance;
    }

    /**
     * The application-wide context, or null if not initialized (e.g. Skija
     * unavailable). Callers must handle null and fall back to ImGui rendering.
     */
    public static synchronized SkijaContext getInstance() {
        return instance;
    }

    public DirectContext get() {
        if (context == null) {
            throw new IllegalStateException("SkijaContext has been disposed");
        }
        return context;
    }

    public boolean isAlive() {
        return context != null;
    }

    @Override
    public synchronized void close() {
        if (context != null) {
            context.close();
            context = null;
            logger.info("Skija DirectContext disposed");
        }
        if (instance == this) {
            instance = null;
        }
    }
}
