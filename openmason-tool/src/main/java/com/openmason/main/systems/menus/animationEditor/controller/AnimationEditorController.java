package com.openmason.main.systems.menus.animationEditor.controller;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.commands.ClipMetaCommands;
import com.openmason.main.systems.menus.animationEditor.commands.CompositeCommand;
import com.openmason.main.systems.menus.animationEditor.commands.KeyframeCommands;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.io.OMAClipIO;
import com.openmason.main.systems.menus.animationEditor.io.OMAFormat;
import com.openmason.main.systems.menus.animationEditor.preview.AnimationPreviewPipeline;
import com.openmason.main.systems.menus.animationEditor.state.AnimationEditorState;
import com.openmason.main.systems.menus.animationEditor.state.KeyframeClipboard;
import com.openmason.main.systems.menus.animationEditor.state.KeyframeSelection;
import com.openmason.main.systems.menus.textureCreator.commands.Command;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brokers all mutations between the Animation Editor UI and the underlying
 * data + viewport. Owns the command history, the preview pipeline, and
 * delegates persistence to {@link OMAClipIO}.
 *
 * <p>Decoupled from ImGui — the window calls into it.
 */
public final class AnimationEditorController {

    private static final Logger logger = LoggerFactory.getLogger(AnimationEditorController.class);

    private final AnimationEditorState state = new AnimationEditorState();
    private final CommandHistory history = new CommandHistory();
    private final OMAClipIO io = new OMAClipIO();

    private ModelPartManager partManager;
    private AnimationPreviewPipeline preview;

    /**
     * Bind the controller to a viewport's part manager. Should be called
     * whenever the active model changes; safe to call repeatedly.
     */
    public void bindViewport(ModelPartManager partManager) {
        if (this.preview != null) {
            this.preview.release();
        }
        this.partManager = partManager;
        this.preview = partManager != null ? new AnimationPreviewPipeline(partManager) : null;
    }

    /**
     * Handle the active model changing while the editor stays open. The old
     * preview snapshot is abandoned (its parts no longer exist — writing it
     * back would corrupt the new model), tracks are re-resolved against the
     * new model by name hint, stale selection is dropped, and a fresh rest
     * pose is captured so the preview keeps working.
     */
    public void onModelChanged(ModelPartManager newPartManager) {
        if (this.preview != null) {
            this.preview.abandon();
        }
        this.partManager = newPartManager;
        this.preview = newPartManager != null ? new AnimationPreviewPipeline(newPartManager) : null;

        if (newPartManager == null) return;

        OMAClipIO.rebindTracksByName(state.clip(), newPartManager);
        String selected = state.selectedPartId();
        if (selected != null && newPartManager.getPartById(selected).isEmpty()) {
            state.setSelectedPartId(null);
        }
        beginSession();
        applyCurrentPose();
    }

    public AnimationEditorState state() { return state; }
    public CommandHistory history() { return history; }
    public ModelPartManager partManager() { return partManager; }

    // ====================== Session ======================

    public void beginSession() {
        if (preview != null && !preview.hasCapturedRestPose()) {
            preview.captureRestPose();
        }
    }

    public void endSession() {
        if (preview != null) {
            preview.release();
        }
        state.setPlaying(false);
    }

    public void applyCurrentPose() {
        if (preview == null) return;
        preview.applyPose(state.clip(), state.playhead());
    }

    /**
     * Advance the playhead by {@code dt} seconds (scaled by the playback
     * speed) when playing. Wraps when looping; clamps when not. Should be
     * called once per frame from the UI.
     */
    public void tickPlayback(float dt) {
        if (!state.playing()) return;
        AnimationClip clip = state.clip();
        if (clip == null) return;
        float dur = clip.duration();
        if (dur <= 0f) return;
        float t = state.playhead() + dt * state.playbackSpeed();
        if (clip.loop()) {
            t = ((t % dur) + dur) % dur;
        } else if (t >= dur) {
            t = dur;
            state.setPlaying(false);
        }
        state.setPlayhead(t);
        applyCurrentPose();
    }

    // ====================== Editing ======================

    /**
     * Insert (or upsert) an arbitrary keyframe on the given part's track.
     * Used by external drivers (MCP, scripting) that supply their own pose.
     */
    public void insertKeyframe(String partId, Keyframe keyframe) {
        if (keyframe == null) return;
        execute(KeyframeCommands.insert(state.clip(), partId, keyframe));
    }

    public void insertKeyframeAtPlayhead(String partId) {
        if (partManager == null) return;
        ModelPartDescriptor part = partManager.getPartById(partId).orElse(null);
        if (part == null) {
            logger.warn("Insert keyframe: unknown part {}", partId);
            return;
        }
        Keyframe kf = Keyframe.fromPartTransform(state.playhead(), part.transform(), Easing.LINEAR);
        execute(KeyframeCommands.insert(state.clip(), partId, kf));
    }

    public void deleteKeyframe(String partId, int index) {
        execute(KeyframeCommands.delete(state.clip(), partId, index));
    }

    public void editKeyframe(String partId, int index, Keyframe newKf) {
        execute(KeyframeCommands.edit(state.clip(), partId, index, newKf));
    }

    /**
     * Delete an entire track, undoably.
     *
     * @return false if no such track exists
     */
    public boolean deleteTrack(String partId) {
        if (state.clip().trackFor(partId) == null) return false;
        if (partId.equals(state.selectedPartId())) {
            state.setSelectedKeyframeIndex(-1);
        }
        execute(KeyframeCommands.deleteTrack(state.clip(), partId));
        return true;
    }

    /**
     * Commit a bulk keyframe move as one undo step: for every affected track,
     * the whole keyframe list is swapped {@code before -> after}. Selection
     * refs are re-pointed by the caller (the timeline knows old/new times).
     */
    public void moveKeyframes(Map<String, List<Keyframe>> beforeByPart,
                              Map<String, List<Keyframe>> afterByPart) {
        List<Command> parts = new ArrayList<>();
        for (var entry : afterByPart.entrySet()) {
            List<Keyframe> before = beforeByPart.get(entry.getKey());
            if (before == null) continue;
            parts.add(KeyframeCommands.replaceTrackKeyframes(
                    state.clip(), entry.getKey(), before, entry.getValue()));
        }
        if (parts.isEmpty()) return;
        execute(new CompositeCommand("Move " + parts.size() + " track(s)", parts));
    }

    /** Delete every keyframe in the multi-selection as one undo step. */
    public void deleteSelectedKeyframes() {
        List<KeyframeSelection.ResolvedKey> resolved = state.selection().resolve(state.clip());
        if (resolved.isEmpty()) return;

        // Group per track, rebuild each track's list without the selected keys.
        Map<String, List<Keyframe>> beforeByPart = new LinkedHashMap<>();
        Map<String, List<Keyframe>> afterByPart = new LinkedHashMap<>();
        for (KeyframeSelection.ResolvedKey key : resolved) {
            var track = state.clip().trackFor(key.partId());
            if (track == null) continue;
            beforeByPart.computeIfAbsent(key.partId(), id -> List.copyOf(track.keyframes()));
            afterByPart.computeIfAbsent(key.partId(),
                    id -> new ArrayList<>(track.keyframes())).remove(key.keyframe());
        }
        List<Command> parts = new ArrayList<>();
        for (var entry : afterByPart.entrySet()) {
            parts.add(KeyframeCommands.replaceTrackKeyframes(
                    state.clip(), entry.getKey(), beforeByPart.get(entry.getKey()), entry.getValue()));
        }
        if (parts.isEmpty()) return;
        execute(new CompositeCommand("Delete " + resolved.size() + " keyframe(s)", parts));
        state.setSelectedKeyframeIndex(-1);
    }

    /**
     * Copy the current selection to the editor clipboard.
     *
     * @return number of keyframes copied
     */
    public int copySelection() {
        return state.clipboard().copyFrom(state.clip(), state.selection());
    }

    /**
     * Paste the clipboard at the playhead: each copied keyframe lands at
     * {@code playhead + normalizedTime} on its source track, clamped to the
     * clip duration. One undo step; the pasted keys become the selection.
     */
    public void pasteAtPlayhead() {
        var clipboard = state.clipboard();
        if (clipboard.isEmpty()) return;

        float base = state.playhead();
        float duration = state.clip().duration();
        List<Command> parts = new ArrayList<>();
        KeyframeSelection pasted = new KeyframeSelection();
        String primaryPart = null;
        float primaryTime = -1f;

        for (KeyframeClipboard.Entry entry : clipboard.entries()) {
            float t = Math.min(base + entry.keyframe().time(), duration);
            Keyframe kf = entry.keyframe().withTime(t);
            parts.add(KeyframeCommands.insert(state.clip(), entry.partId(), kf));
            pasted.add(new KeyframeSelection.KeyRef(entry.partId(), t));
            if (primaryPart == null) {
                primaryPart = entry.partId();
                primaryTime = t;
            }
        }
        execute(new CompositeCommand("Paste " + parts.size() + " keyframe(s)", parts));

        if (primaryPart != null) {
            int idx = indexAtTime(primaryPart, primaryTime);
            state.selectKeyframes(pasted, primaryPart, idx);
        }
    }

    /**
     * Set the easing on every selected keyframe (falling back to the primary
     * selection) as one undo step.
     */
    public void setSelectionEasing(Easing easing) {
        List<KeyframeSelection.ResolvedKey> resolved = state.selection().resolve(state.clip());
        if (resolved.isEmpty()) return;
        List<Command> parts = new ArrayList<>();
        for (KeyframeSelection.ResolvedKey key : resolved) {
            if (key.keyframe().easing() == easing) continue;
            parts.add(KeyframeCommands.edit(state.clip(), key.partId(), key.index(),
                    key.keyframe().withEasing(easing)));
        }
        if (parts.isEmpty()) return;
        execute(new CompositeCommand("Set easing " + easing + " on " + parts.size() + " keyframe(s)", parts));
    }

    /** Step the playhead by whole frames at the clip's fps. */
    public void stepFrames(int delta) {
        AnimationClip clip = state.clip();
        if (clip == null || clip.fps() <= 0f) return;
        state.setPlaying(false);
        float frameLen = 1f / clip.fps();
        // Snap the current playhead to a frame first so repeated steps land on the grid.
        float frame = Math.round(state.playhead() * clip.fps());
        state.setPlayhead((frame + delta) * frameLen);
        applyCurrentPose();
    }

    private int indexAtTime(String partId, float time) {
        var track = state.clip().trackFor(partId);
        if (track == null) return -1;
        for (int i = 0; i < track.size(); i++) {
            if (Math.abs(track.get(i).time() - time) < KeyframeSelection.TIME_EPS) return i;
        }
        return -1;
    }

    public void setClipName(String name) {
        if (name == null || name.equals(state.clip().name())) return;
        execute(ClipMetaCommands.setName(state.clip(), name));
    }

    public void setClipFps(float fps) {
        if (fps == state.clip().fps()) return;
        execute(ClipMetaCommands.setFps(state.clip(), fps));
    }

    public void setClipDuration(float duration) {
        if (duration == state.clip().duration()) return;
        execute(ClipMetaCommands.setDuration(state.clip(), duration));
    }

    public void setClipLoop(boolean loop) {
        if (loop == state.clip().loop()) return;
        execute(ClipMetaCommands.setLoop(state.clip(), loop));
    }

    // ---------- layering metadata (format v1.1) ----------

    public void setLayerType(com.openmason.engine.format.oma.AnimLayerMeta.LayerType type) {
        if (type == null || type == state.clip().layerType()) return;
        execute(ClipMetaCommands.setLayerType(state.clip(), type));
    }

    public void setMaskParts(List<String> maskParts) {
        execute(ClipMetaCommands.setMaskParts(state.clip(), maskParts));
    }

    /** Toggle one part name in the overlay mask, undoably. */
    public void toggleMaskPart(String partName) {
        if (partName == null || partName.isBlank()) return;
        List<String> mask = new ArrayList<>(state.clip().maskParts());
        boolean removed = mask.removeIf(p -> p.equalsIgnoreCase(partName));
        if (!removed) mask.add(partName);
        execute(ClipMetaCommands.setMaskParts(state.clip(), mask));
    }

    public void setLayerFadeIn(float seconds) {
        if (seconds == state.clip().fadeInSeconds()) return;
        execute(ClipMetaCommands.setFadeIn(state.clip(), seconds));
    }

    public void setLayerFadeOut(float seconds) {
        if (seconds == state.clip().fadeOutSeconds()) return;
        execute(ClipMetaCommands.setFadeOut(state.clip(), seconds));
    }

    public void setLayerPriority(int priority) {
        if (priority == state.clip().layerPriority()) return;
        execute(ClipMetaCommands.setLayerPriority(state.clip(), priority));
    }

    public boolean undo() {
        boolean changed = history.undo();
        if (changed) {
            state.markDirty();
            applyCurrentPose();
        }
        return changed;
    }

    public boolean redo() {
        boolean changed = history.redo();
        if (changed) {
            state.markDirty();
            applyCurrentPose();
        }
        return changed;
    }

    private void execute(Command cmd) {
        history.executeCommand(cmd);
        state.markDirty();
        applyCurrentPose();
    }

    // ====================== File I/O ======================

    public void newClip() {
        state.setClip(AnimationClip.blank());
        state.setFilePath(null);
        state.markClean();
        history.clear();
        applyCurrentPose();
    }

    public boolean save() {
        if (state.filePath() == null) return false;
        boolean ok = io.save(state.clip(), state.filePath(), partManager);
        if (ok) state.markClean();
        return ok;
    }

    public boolean saveAs(String filePath) {
        // Normalize before recording: the native save dialog may return a path
        // without the .omanim extension, but the serializer always writes with
        // it — the stored path must match the file that actually exists.
        String resolved = OMAFormat.ensureExtension(filePath);
        boolean ok = io.save(state.clip(), resolved, partManager);
        if (ok) {
            state.setFilePath(resolved);
            state.markClean();
        }
        return ok;
    }

    public boolean load(String filePath) {
        AnimationClip loaded = io.load(filePath, partManager);
        if (loaded == null) return false;
        state.setClip(loaded);
        state.setFilePath(filePath);
        state.markClean();
        history.clear();
        applyCurrentPose();
        return true;
    }
}
