package com.openmason.engine.ui.text;

/**
 * The clipboard a {@link TextEditModel} cuts, copies and pastes through. Hosts adapt their own
 * (Masonry's {@code MasonryEnvironment.clipboard()} in game and tool); tests pass a fake.
 */
public interface TextClipboard {

    /** Clipboard text; never null ({@code ""} when empty or unavailable). */
    String read();

    void write(String text);
}
