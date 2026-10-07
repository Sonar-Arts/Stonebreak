package com.openmason.engine.ui.runtime.input;

/**
 * A callback registered on an element. Lua handlers (#292) are registered through the same
 * API and receive the same event objects, so scripts and Java share one dispatch.
 */
@FunctionalInterface
public interface UiEventHandler {

    void handle(UiEvent event);
}
