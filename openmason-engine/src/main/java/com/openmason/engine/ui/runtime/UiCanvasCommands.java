package com.openmason.engine.ui.runtime;

/**
 * The draw commands of one {@code Canvas} element (#292): floats its code-behind wrote this frame,
 * plus the strings and texture references those commands name by index. The script runtime
 * attaches one per canvas ({@link UiDocumentInstance#attachCanvas}); the painter reads it in
 * place, so a frame's drawing costs no copy and no per-command object.
 */
public interface UiCanvasCommands {

    /** Floats written this frame. */
    int size();

    float get(int index);

    /** A string registered with {@code canvas:str(s)}, or null. */
    String string(int id);

    /** An asset reference registered with {@code canvas:texture(ref)}, or null. */
    String texture(int id);
}
