package com.stonebreak.rendering.UI.backend;

/**
 * Abstraction over the immediate-mode UI backend (NanoVG, Skija, …).
 *
 * Screens that opt in to a backend bracket their drawing with
 * {@link #beginFrame(int, int, float)} / {@link #endFrame()}. Implementations
 * are responsible for saving and restoring any GL state they touch so the
 * surrounding world / overlay rendering is unaffected.
 */
public interface UIBackend {

    void beginFrame(int width, int height, float pixelRatio);

    void endFrame();

    void resize(int width, int height);

    void dispose();

    boolean isAvailable();
}
