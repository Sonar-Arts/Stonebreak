package com.openmason.main.systems.menus.animationEditor.commands;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.textureCreator.commands.Command;

import java.util.List;

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

    // ---------- layering metadata (format v1.1) ----------

    public static Command setLayerType(AnimationClip clip, AnimLayerMeta.LayerType newType) {
        return new Command() {
            private AnimLayerMeta.LayerType before;
            @Override public void execute() { before = clip.layerType(); clip.setLayerType(newType); }
            @Override public void undo() { clip.setLayerType(before); }
            @Override public String getDescription() { return "Set layer type " + newType; }
        };
    }

    public static Command setMaskParts(AnimationClip clip, List<String> newMask) {
        return new Command() {
            private List<String> before;
            @Override public void execute() {
                before = List.copyOf(clip.maskParts());
                clip.setMaskParts(newMask);
            }
            @Override public void undo() { clip.setMaskParts(before); }
            @Override public String getDescription() { return "Set overlay mask"; }
        };
    }

    public static Command setFadeIn(AnimationClip clip, float newFadeIn) {
        return new Command() {
            private float before;
            @Override public void execute() { before = clip.fadeInSeconds(); clip.setFadeInSeconds(newFadeIn); }
            @Override public void undo() { clip.setFadeInSeconds(before); }
            @Override public String getDescription() { return "Set layer fade-in"; }
        };
    }

    public static Command setFadeOut(AnimationClip clip, float newFadeOut) {
        return new Command() {
            private float before;
            @Override public void execute() { before = clip.fadeOutSeconds(); clip.setFadeOutSeconds(newFadeOut); }
            @Override public void undo() { clip.setFadeOutSeconds(before); }
            @Override public String getDescription() { return "Set layer fade-out"; }
        };
    }

    public static Command setLayerPriority(AnimationClip clip, int newPriority) {
        return new Command() {
            private int before;
            @Override public void execute() { before = clip.layerPriority(); clip.setLayerPriority(newPriority); }
            @Override public void undo() { clip.setLayerPriority(before); }
            @Override public String getDescription() { return "Set layer priority"; }
        };
    }
}
