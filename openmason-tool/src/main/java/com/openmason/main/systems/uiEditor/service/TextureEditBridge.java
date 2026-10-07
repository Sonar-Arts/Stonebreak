package com.openmason.main.systems.uiEditor.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Edit texture" between the UI editor and the Texture Editor (#294). OMT textures open as they
 * are. An SBT opens through its layered OMT source ({@link UiImageAssets#omtSourceFor}); when the
 * Texture Editor saves that OMT the SBT is re-wrapped with its own identity, so the UI keeps
 * referencing the SBT and the layers stay editable. The pixel and layer editor is never
 * duplicated: this only routes files.
 *
 * <p>The link is the file convention, not session memory: {@code <name>.omt} beside
 * {@code <name>.sbt} is that SBT's source ({@link UiImageAssets#omtSiblingOf}). Saving the OMT
 * re-wraps the SBT whether it was opened through "Edit texture" in this session, after a restart,
 * or straight from the Texture Editor.
 */
public final class TextureEditBridge {

    /** Opens an OMT in the Texture Editor; returns null when it opened, else why it did not. */
    @FunctionalInterface
    public interface Opener {
        String open(Path omt);
    }

    private final Map<Path, Path> sbtForOmt = new HashMap<>();

    /** @return null when the Texture Editor opened the texture, else a message for the user */
    public String edit(UiImageAssets.EditableTexture t, Opener opener) {
        if (!t.ok()) {
            return t.problem();
        }
        if (opener == null) {
            return "The Texture Editor is not available";
        }
        Path omt = t.file();
        if (t.sbt()) {
            try {
                omt = UiImageAssets.omtSourceFor(t.file());
            } catch (IOException e) {
                return "Cannot open the layered source of " + t.file().getFileName() + ": " + e.getMessage();
            }
            sbtForOmt.put(key(omt), t.file());
        }
        return opener.open(omt);
    }

    /**
     * The Texture Editor saved {@code file}. Re-wraps the SBT it stands for, if any.
     *
     * @return every file whose bytes changed (the OMT, plus the SBT when one was re-wrapped)
     */
    public List<Path> saved(Path file) throws IOException {
        List<Path> changed = new ArrayList<>(List.of(file));
        Path sbt = sbtFor(file);
        if (sbt != null) {
            UiImageAssets.rewrapSbt(sbt, file);
            changed.add(sbt);
        }
        return changed;
    }

    /** The SBT an OMT is the source of (linked this session, else its sibling by convention), or null. */
    public Path sbtFor(Path omt) {
        Path linked = sbtForOmt.get(key(omt));
        return linked != null ? linked : UiImageAssets.sbtSiblingOf(omt);
    }

    private static Path key(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
