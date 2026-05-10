package com.openmason.main.systems.menus.animationEditor.commands;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.textureCreator.commands.Command;

/**
 * Undo/redo-able mutations for clip-level metadata (name, fps, duration, loop).
 * Routed through the same {@link Command} pipeline as keyframe edits so the
 * inspector's metadata fields participate in the editor's history.
 */
public final class ClipMetaCommands {

    private ClipMetaCommands() {}

    public static Command setName(AnimationClip clip, String newName) {
        return new Command() {
            private String before;
            @Override public void execute() { before = clip.name(); clip.setName(newName); }
            @Override public void undo() { clip.setName(before); }
            @Override public String getDescription() { return "Set clip name"; }
        };
    }

    public static Command setFps(AnimationClip clip, float newFps) {
        return new Command() {
            private float before;
            @Override public void execute() { before = clip.fps(); clip.setFps(newFps); }
            @Override public void undo() { clip.setFps(before); }
            @Override public String getDescription() { return "Set clip fps"; }
        };
    }

    public static Command setDuration(AnimationClip clip, float newDuration) {
        return new Command() {
            private float before;
            @Override public void execute() { before = clip.duration(); clip.setDuration(newDuration); }
            @Override public void undo() { clip.setDuration(before); }
            @Override public String getDescription() { return "Set clip duration"; }
        };
    }

    public static Command setLoop(AnimationClip clip, boolean newLoop) {
        return new Command() {
            private boolean before;
            @Override public void execute() { before = clip.loop(); clip.setLoop(newLoop); }
            @Override public void undo() { clip.setLoop(before); }
            @Override public String getDescription() { return "Set clip loop"; }
        };
    }
}
