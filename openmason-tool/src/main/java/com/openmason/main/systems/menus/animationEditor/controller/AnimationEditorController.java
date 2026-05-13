package com.openmason.main.systems.menus.animationEditor.controller;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.commands.ClipMetaCommands;
import com.openmason.main.systems.menus.animationEditor.commands.KeyframeCommands;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.io.OMAClipIO;
import com.openmason.main.systems.menus.animationEditor.preview.AnimationPreviewPipeline;
import com.openmason.main.systems.menus.animationEditor.state.AnimationEditorState;
import com.openmason.main.systems.menus.textureCreator.commands.Command;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Advance the playhead by {@code dt} seconds when playing. Wraps when
     * looping; clamps when not. Should be called once per frame from the UI.
     */
    public void tickPlayback(float dt) {
        if (!state.playing()) return;
        AnimationClip clip = state.clip();
        if (clip == null) return;
        float dur = clip.duration();
        if (dur <= 0f) return;
        float t = state.playhead() + dt;
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
        boolean ok = io.save(state.clip(), filePath, partManager);
        if (ok) {
            state.setFilePath(filePath);
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
