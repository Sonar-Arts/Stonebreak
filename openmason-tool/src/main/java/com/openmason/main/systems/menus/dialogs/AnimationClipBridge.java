package com.openmason.main.systems.menus.dialogs;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The SBE/SBO state editors' link to the Animation Editor, so a state's
 * embedded clip round-trips without going through a file on disk:
 * "From editor" embeds the clip currently open in the editor, "Edit in
 * editor" loads the state's embedded clip into it.
 *
 * @param fromEditor   the editor's current clip as .omanim bytes (null if unavailable)
 * @param openInEditor opens the editor (if needed) and loads these bytes, labelled
 * @param editorClipName name of the clip currently open in the editor (for labels)
 */
public record AnimationClipBridge(Supplier<byte[]> fromEditor,
                                  BiConsumer<byte[], String> openInEditor,
                                  Supplier<String> editorClipName) {
}
