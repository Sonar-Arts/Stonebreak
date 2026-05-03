package com.openmason.main.systems.menus.animationEditor.controller;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.commands.KeyframeCommands;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.animationEditor.io.OMADeserializer;
import com.openmason.main.systems.menus.animationEditor.io.OMASerializer;
import com.openmason.main.systems.menus.animationEditor.preview.AnimationPreviewPipeline;
import com.openmason.main.systems.menus.animationEditor.state.AnimationEditorState;
import com.openmason.main.systems.menus.textureCreator.commands.Command;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brokers all mutations between the Animation Editor UI and the underlying
 * data + viewport. Owns the command history (shared with the texture editor's
 * pattern), the preview pipeline, and file I/O.
 *
 * <p>The controller is decoupled from ImGui — the window calls into it.
 */
public final class AnimationEditorController {

    private static final Logger logger = LoggerFactory.getLogger(AnimationEditorController.class);

    private final AnimationEditorState state = new AnimationEditorState();
    private final CommandHistory history = new CommandHistory();
    private final OMASerializer serializer = new OMASerializer();
    private final OMADeserializer deserializer = new OMADeserializer();

    private ModelPartManager partManager;
    private AnimationPreviewPipeline preview;

    /**
     * Bind the controller to a viewport's part manager. Should be called
     * whenever the active model changes; safe to call repeatedly.
     */
    public void bindViewport(ModelPartManager partManager) {
        // Release any prior session before swapping managers.
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

    /**
     * Begin a preview session — captures rest pose so {@link #applyCurrentPose()}
     * can be called repeatedly without losing the original transforms.
     */
    public void beginSession() {
        if (preview != null && !preview.hasCapturedRestPose()) {
            preview.captureRestPose();
        }
    }

    /**
     * Restore the rest pose and end the preview session.
     */
    public void endSession() {
        if (preview != null) {
            preview.release();
        }
        state.setPlaying(false);
    }

    /**
     * Push the clip's pose at the current playhead into the viewport.
     */
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
        float t = state.playhead() + dt;
        if (clip.loop()) {
            float dur = clip.duration();
            if (dur <= 0f) return;
            while (t >= dur) t -= dur;
        } else if (t >= clip.duration()) {
            t = clip.duration();
            state.setPlaying(false);
        }
        state.setPlayhead(t);
        applyCurrentPose();
    }

    // ====================== Editing ======================

    /**
     * Insert a keyframe at the playhead for the given part, using its current
     * local transform (or rest pose) as the pose values.
     */
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

    /**
     * Insert an explicit pose keyframe (used by the inspector when the user
     * types values manually).
     */
    public void insertKeyframe(String partId, float time, Vector3f position,
                               Vector3f rotation, Vector3f scale) {
        Keyframe kf = new Keyframe(time, position, rotation, scale, Easing.LINEAR);
        execute(KeyframeCommands.insert(state.clip(), partId, kf));
    }

    public void deleteKeyframe(String partId, int index) {
        execute(KeyframeCommands.delete(state.clip(), partId, index));
    }

    public void editKeyframe(String partId, int index, Keyframe newKf) {
        execute(KeyframeCommands.edit(state.clip(), partId, index, newKf));
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
        boolean ok = serializer.save(state.clip(), state.filePath(), this::partNameFor);
        if (ok) state.markClean();
        return ok;
    }

    public boolean saveAs(String filePath) {
        boolean ok = serializer.save(state.clip(), filePath, this::partNameFor);
        if (ok) {
            state.setFilePath(filePath);
            state.markClean();
        }
        return ok;
    }

    private String partNameFor(String partId) {
        if (partManager == null) return null;
        return partManager.getPartById(partId).map(ModelPartDescriptor::name).orElse(null);
    }

    public boolean load(String filePath) {
        AnimationClip loaded = deserializer.load(filePath);
        if (loaded == null) return false;
        // Animations saved before stable part IDs landed — or animations authored
        // against a different .omo — may carry track partIds that don't match the
        // currently-loaded model. Try to rebind by part name as a fallback so the
        // load isn't a silent no-op when the user opens an existing clip.
        rebindTracksByName(loaded);
        state.setClip(loaded);
        state.setFilePath(filePath);
        state.markClean();
        history.clear();
        applyCurrentPose();
        return true;
    }

    /**
     * Walk the loaded clip's tracks; for each track whose partId is not present
     * in the current part manager, look up a part by display name and rewrite
     * the track's key. Names with no match are left alone (user can rebind
     * manually later).
     */
    private void rebindTracksByName(AnimationClip clip) {
        if (partManager == null) return;
        java.util.Map<String, com.openmason.main.systems.menus.animationEditor.data.Track> remap = new java.util.LinkedHashMap<>();
        for (var entry : new java.util.ArrayList<>(clip.tracks().entrySet())) {
            String savedId = entry.getKey();
            com.openmason.main.systems.menus.animationEditor.data.Track savedTrack = entry.getValue();

            if (partManager.getPartById(savedId).isPresent()) {
                remap.put(savedId, savedTrack);
                continue;
            }

            // No direct ID match. Use the saved partNameHint (written by
            // OMASerializer) as the fallback rebind key — handles clips authored
            // before stable-ID save/load landed, or clips moved between models
            // whose part naming overlaps.
            String nameHint = savedTrack.partNameHint();
            java.util.Optional<com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor> nameMatch =
                    java.util.Optional.empty();
            if (nameHint != null && !nameHint.isBlank()) {
                nameMatch = partManager.getAllParts().stream()
                        .filter(p -> nameHint.equalsIgnoreCase(p.name()))
                        .findFirst();
            }

            if (nameMatch.isPresent()) {
                String newId = nameMatch.get().id();
                com.openmason.main.systems.menus.animationEditor.data.Track rebound =
                        new com.openmason.main.systems.menus.animationEditor.data.Track(newId);
                rebound.setPartNameHint(nameMatch.get().name());
                for (var kf : savedTrack.keyframes()) {
                    rebound.upsert(kf);
                }
                remap.put(newId, rebound);
                logger.info("Rebound animation track '{}' (saved id '{}') to current part id '{}'",
                        nameMatch.get().name(), savedId, newId);
            } else {
                // No match by ID or name — keep the orphan track so the user can see
                // it and rebind manually later, but warn so it's not silent.
                remap.put(savedId, savedTrack);
                logger.warn("Animation track for partId '{}' (name hint '{}') has no matching part in the loaded model",
                        savedId, nameHint);
            }
        }
        clip.tracks().clear();
        clip.tracks().putAll(remap);
    }

    /**
     * Find the keyframe at (or within {@code epsilon}) of {@code time} on the
     * given track. Returns -1 if none.
     */
    public int findKeyframeAt(String partId, float time, float epsilon) {
        Track track = state.clip().trackFor(partId);
        if (track == null) return -1;
        for (int i = 0; i < track.size(); i++) {
            if (Math.abs(track.get(i).time() - time) <= epsilon) return i;
        }
        return -1;
    }
}
