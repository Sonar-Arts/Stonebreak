package com.openmason.main.systems.menus.animationEditor.controller;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.menus.animationEditor.commands.AutoKeyCommand;
import com.openmason.main.systems.menus.animationEditor.commands.ClipMetaCommands;
import com.openmason.main.systems.menus.animationEditor.commands.CompositeCommand;
import com.openmason.main.systems.menus.animationEditor.commands.KeyframeCommands;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
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
import java.util.function.Supplier;

/**
 * Brokers all mutations between the Animation Editor UI and the underlying
 * data + viewport. Owns the command history, the preview pipeline, and
 * delegates persistence to {@link OMAClipIO}.
 *
 * <p>Decoupled from ImGui — the window calls into it.
 *
 * <p><b>Dirty tracking</b> is history-position based: the command on top of
 * the undo stack at save time is remembered, and the clip is clean whenever
 * that same command is on top again (so undoing back to the saved state
 * clears the flag, and undoing past it or redoing away sets it).
 */
public final class AnimationEditorController {

    private static final Logger logger = LoggerFactory.getLogger(AnimationEditorController.class);

    private final AnimationEditorState state = new AnimationEditorState();
    private final CommandHistory history = new CommandHistory();
    private final OMAClipIO io = new OMAClipIO();

    private ModelPartManager partManager;
    private AnimationPreviewPipeline preview;
    /** Where the clip's {@code modelRef} comes from at save time (model file path or name). */
    private Supplier<String> modelRefSupplier = () -> null;

    /** History top at the last save/load; clean iff it is still the top. */
    private Command savedTop;
    /** Set by non-command mutations (e.g. a clip imported from bytes) until the next save. */
    private boolean forceDirty;

    /**
     * Bind the controller to a viewport's part manager. Should be called
     * whenever the active model changes; safe to call repeatedly.
     */
    public void bindViewport(ModelPartManager partManager) {
        if (this.preview != null) {
            this.preview.release();
        }
        this.partManager = partManager;
        this.preview = newPreview(partManager);
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
        this.preview = newPreview(newPartManager);

        if (newPartManager == null) return;

        OMAClipIO.rebindTracksByName(state.clip(), newPartManager);
        if (state.basePreviewClip() != null) {
            OMAClipIO.rebindTracksByName(state.basePreviewClip(), newPartManager);
        }
        String selected = state.selectedPartId();
        if (selected != null && newPartManager.getPartById(selected).isEmpty()
                && state.clip().trackFor(selected) == null) {
            state.setSelectedPartId(null);
        }
        beginSession();
        applyCurrentPose();
    }

    /** Supplies the model identity written as the clip's {@code modelRef} on save. */
    public void setModelRefSupplier(Supplier<String> supplier) {
        this.modelRefSupplier = supplier != null ? supplier : () -> null;
    }

    private AnimationPreviewPipeline newPreview(ModelPartManager pm) {
        if (pm == null) return null;
        AnimationPreviewPipeline p = new AnimationPreviewPipeline(pm);
        p.setExternalEditListener(this::onExternalPartEdit);
        return p;
    }

    public AnimationEditorState state() { return state; }
    public CommandHistory history() { return history; }
    public ModelPartManager partManager() { return partManager; }

    // ====================== Session ======================

    /** Called once per UI frame by the window. */
    public void beginSession() {
        if (preview == null) return;
        if (!preview.hasCapturedRestPose()) {
            preview.captureRestPose();
        }
        preview.frameTick();
    }

    public void endSession() {
        if (preview != null) {
            preview.release();
        }
        state.setPlaying(false);
        state.setUnkeyedPartId(null);
    }

    public void applyCurrentPose() {
        if (preview == null) return;
        preview.applyPose(state.clip(), state.playhead(), state.basePreviewClip());
    }

    /**
     * Put the viewport back at the model's rest pose without ending the
     * session — call before the model is serialized so the preview pose is
     * never baked into the .omo. Pair with {@link #resumePreview()}.
     */
    public void suspendPreview() {
        if (preview != null) preview.restoreRestPose();
    }

    /** Re-apply the preview pose after {@link #suspendPreview()}. */
    public void resumePreview() {
        applyCurrentPose();
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

    // ====================== Viewport edits (auto-key) ======================

    /**
     * A part was written by something other than the preview (gizmo,
     * property panel, model undo). With auto-key on, the pose is upserted as
     * a keyframe at the playhead — consecutive edits of the same part at the
     * same time amend one history entry, so a gizmo drag is one undo step.
     * Otherwise, if the part is showing an animated pose, remember it so the
     * UI can hint that the edit will be lost on the next scrub.
     *
     * @return true when the edit was consumed as a keyframe
     */
    private boolean onExternalPartEdit(String partId, PartTransform transform) {
        if (partManager == null || partManager.getPartById(partId).isEmpty()) return false;
        if (!state.autoKey()) {
            if (preview != null && preview.isAnimated(partId)) {
                state.setUnkeyedPartId(partId);
            }
            return false;
        }
        float t = state.playhead();
        Keyframe kf = Keyframe.fromPartTransform(t, transform, currentEasingFor(partId, t));
        if (history.peekUndo() instanceof AutoKeyCommand top && top.matches(partId, t)) {
            top.amend(kf);
            refreshDirty();
        } else {
            execute(new AutoKeyCommand(state.clip(), partId, kf));
        }
        state.setUnkeyedPartId(null);
        return true;
    }

    /** Easing of an existing key at {@code t} on the track, else LINEAR. */
    private Easing currentEasingFor(String partId, float t) {
        int idx = indexAtTime(partId, t);
        if (idx < 0) return Easing.LINEAR;
        return state.clip().trackFor(partId).get(idx).easing();
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
        insertKeyframeAt(partId, state.playhead());
    }

    /**
     * Insert a keyframe at an arbitrary time capturing the part's pose there:
     * the part's current viewport transform when {@code time} is the playhead
     * (so a viewport pose is what gets keyed), otherwise the track's sampled
     * pose at {@code time} (or the current transform if the track is empty).
     */
    public void insertKeyframeAt(String partId, float time) {
        if (partManager == null) return;
        ModelPartDescriptor part = partManager.getPartById(partId).orElse(null);
        if (part == null) {
            logger.warn("Insert keyframe: unknown part {}", partId);
            return;
        }
        time = Math.min(Math.max(time, 0f), state.clip().duration());
        Track track = state.clip().trackFor(partId);
        Keyframe kf;
        if (Math.abs(time - state.playhead()) < KeyframeSelection.TIME_EPS || track == null) {
            kf = Keyframe.fromPartTransform(time, part.transform(), Easing.LINEAR);
        } else {
            Track.Sample s = track.sample(time);
            kf = new Keyframe(time, s.position(), s.rotation(), s.scale(), Easing.LINEAR);
        }
        execute(KeyframeCommands.insert(state.clip(), partId, kf));
        if (partId.equals(state.unkeyedPartId())) state.setUnkeyedPartId(null);
    }

    public void deleteKeyframe(String partId, int index) {
        execute(KeyframeCommands.delete(state.clip(), partId, index));
    }

    public void editKeyframe(String partId, int index, Keyframe newKf) {
        execute(KeyframeCommands.edit(state.clip(), partId, index, newKf));
    }

    /**
     * Live-preview a keyframe edit without touching history (the inspector
     * calls this while a field is being dragged/typed, then commits once with
     * {@link #editKeyframe} on release). Writes straight into the track.
     */
    public void previewKeyframeEdit(String partId, int index, Keyframe newKf) {
        Track track = state.clip().trackFor(partId);
        if (track == null || index < 0 || index >= track.size()) return;
        track.set(index, newKf);
        applyCurrentPose();
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
     * Tracks whose partId matches no part on the bound model — loaded from a
     * file authored against a different model, or left behind by a part
     * deletion. Shown as unbound rows so they can be rebound or removed.
     */
    public List<Track> orphanTracks() {
        List<Track> out = new ArrayList<>();
        for (Track t : state.clip().tracks().values()) {
            if (partManager == null || partManager.getPartById(t.partId()).isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Re-key an orphan track under a real part (merging into an existing track). */
    public boolean rebindTrack(String fromPartId, String toPartId) {
        if (partManager == null || state.clip().trackFor(fromPartId) == null) return false;
        ModelPartDescriptor target = partManager.getPartById(toPartId).orElse(null);
        if (target == null) return false;
        if (fromPartId.equals(state.selectedPartId())) state.setSelectedPartId(toPartId);
        execute(KeyframeCommands.rebindTrack(state.clip(), fromPartId, toPartId, target.name()));
        return true;
    }

    /** Keyframes sitting past the clip's duration (unreachable on the timeline). */
    public int keyframesBeyondDuration() {
        float d = state.clip().duration();
        int n = 0;
        for (Track t : state.clip().tracks().values()) {
            for (Keyframe kf : t.keyframes()) {
                if (kf.time() > d + KeyframeSelection.TIME_EPS) n++;
            }
        }
        return n;
    }

    /** Remove every keyframe past the clip's duration, as one undo step. */
    public void trimKeyframesBeyondDuration() {
        Command cmd = KeyframeCommands.trimBeyond(state.clip(), state.clip().duration());
        if (cmd != null) {
            execute(cmd);
            state.setSelectedKeyframeIndex(-1);
        }
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

    /** Select every keyframe on every track (primary = first key of the first track). */
    public void selectAll() {
        KeyframeSelection all = new KeyframeSelection();
        String primaryPart = null;
        for (Track track : state.clip().tracks().values()) {
            for (Keyframe kf : track.keyframes()) {
                all.add(new KeyframeSelection.KeyRef(track.partId(), kf.time()));
            }
            if (primaryPart == null && !track.isEmpty()) primaryPart = track.partId();
        }
        if (all.isEmpty()) return;
        state.selectKeyframes(all, primaryPart, 0);
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
        pasteEntries(state.clipboard().entries(), "Paste");
    }

    /**
     * Duplicate the selection to the playhead (relative spacing kept) without
     * disturbing the clipboard. One undo step; the copies become the selection.
     */
    public void duplicateSelectionToPlayhead() {
        KeyframeClipboard temp = new KeyframeClipboard();
        if (temp.copyFrom(state.clip(), state.selection()) == 0) return;
        pasteEntries(temp.entries(), "Duplicate");
    }

    private void pasteEntries(List<KeyframeClipboard.Entry> entries, String verb) {
        if (entries.isEmpty()) return;

        float base = state.playhead();
        float duration = state.clip().duration();
        List<Command> parts = new ArrayList<>();
        KeyframeSelection pasted = new KeyframeSelection();
        String primaryPart = null;
        float primaryTime = -1f;

        for (KeyframeClipboard.Entry entry : entries) {
            float t = Math.min(base + entry.keyframe().time(), duration);
            Keyframe kf = entry.keyframe().withTime(t);
            parts.add(KeyframeCommands.insert(state.clip(), entry.partId(), kf));
            pasted.add(new KeyframeSelection.KeyRef(entry.partId(), t));
            if (primaryPart == null) {
                primaryPart = entry.partId();
                primaryTime = t;
            }
        }
        execute(new CompositeCommand(verb + " " + parts.size() + " keyframe(s)", parts));

        if (primaryPart != null) {
            int idx = indexAtTime(primaryPart, primaryTime);
            state.selectKeyframes(pasted, primaryPart, idx);
        }
    }

    /**
     * Mirror the selected keyframes in time about the selection's own span
     * ({@code t' = min + max - t}) so the motion plays backwards. Per-track
     * whole-list replace, one undo step; the selection is re-pointed.
     */
    public void reverseSelection() {
        List<KeyframeSelection.ResolvedKey> resolved = state.selection().resolve(state.clip());
        if (resolved.size() < 2) return;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (KeyframeSelection.ResolvedKey key : resolved) {
            min = Math.min(min, key.keyframe().time());
            max = Math.max(max, key.keyframe().time());
        }
        final float lo = min, hi = max;

        Map<String, List<Keyframe>> beforeByPart = new LinkedHashMap<>();
        Map<String, List<Keyframe>> afterByPart = new LinkedHashMap<>();
        for (KeyframeSelection.ResolvedKey key : resolved) {
            Track track = state.clip().trackFor(key.partId());
            if (track == null) continue;
            beforeByPart.computeIfAbsent(key.partId(), id -> List.copyOf(track.keyframes()));
            afterByPart.computeIfAbsent(key.partId(), id -> new ArrayList<>(track.keyframes()));
        }
        KeyframeSelection moved = new KeyframeSelection();
        for (var entry : afterByPart.entrySet()) {
            List<Keyframe> after = entry.getValue();
            for (int i = 0; i < after.size(); i++) {
                Keyframe kf = after.get(i);
                if (state.selection().contains(entry.getKey(), kf.time())) {
                    float t = lo + hi - kf.time();
                    after.set(i, kf.withTime(t));
                    moved.add(new KeyframeSelection.KeyRef(entry.getKey(), t));
                }
            }
        }
        moveKeyframes(beforeByPart, afterByPart);
        String primary = state.selectedPartId();
        int idx = primary != null ? indexAtTime(primary, lo + hi - primaryTimeOr(primary, lo)) : -1;
        state.selectKeyframes(moved, primary, idx);
    }

    private float primaryTimeOr(String partId, float fallback) {
        Track track = state.clip().trackFor(partId);
        int idx = state.selectedKeyframeIndex();
        return (track != null && idx >= 0 && idx < track.size()) ? track.get(idx).time() : fallback;
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

    /**
     * Step the playhead by whole frames at the clip's fps. Looping clips wrap
     * in both directions (stepping back from frame 0 lands on the last frame).
     */
    public void stepFrames(int delta) {
        AnimationClip clip = state.clip();
        if (clip == null || clip.fps() <= 0f) return;
        state.setPlaying(false);
        float frameLen = 1f / clip.fps();
        // Snap the current playhead to a frame first so repeated steps land on the grid.
        float frame = Math.round(state.playhead() * clip.fps());
        float t = (frame + delta) * frameLen;
        if (clip.loop() && t < 0f) {
            float dur = clip.duration();
            t = ((t % dur) + dur) % dur;
        }
        state.setPlayhead(t);
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
        if (name == null) return;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals(state.clip().name())) return;
        execute(ClipMetaCommands.setName(state.clip(), trimmed));
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

    // ---------- layered preview ----------

    /**
     * Load a BASE clip the preview layers the current OVERLAY on top of. Not
     * part of the edited clip or its history — purely a preview aid.
     *
     * @return false if the file failed to parse
     */
    public boolean loadBasePreviewClip(String filePath) {
        AnimationClip base = io.load(filePath, partManager);
        if (base == null) return false;
        state.setBasePreviewClip(base, filePath);
        applyCurrentPose();
        return true;
    }

    public void clearBasePreviewClip() {
        state.setBasePreviewClip(null, null);
        applyCurrentPose();
    }

    public boolean undo() {
        boolean changed = history.undo();
        if (changed) {
            refreshDirty();
            applyCurrentPose();
        }
        return changed;
    }

    public boolean redo() {
        boolean changed = history.redo();
        if (changed) {
            refreshDirty();
            applyCurrentPose();
        }
        return changed;
    }

    private void execute(Command cmd) {
        history.executeCommand(cmd);
        refreshDirty();
        applyCurrentPose();
    }

    private void refreshDirty() {
        state.setDirty(forceDirty || history.peekUndo() != savedTop);
    }

    private void markSaved() {
        forceDirty = false;
        savedTop = history.peekUndo();
        state.markClean();
    }

    // ====================== File I/O ======================

    public void newClip() {
        state.setClip(AnimationClip.blank());
        state.setFilePath(null);
        history.clear();
        markSaved();
        applyCurrentPose();
    }

    public boolean save() {
        if (state.filePath() == null) return false;
        stampModelRef();
        boolean ok = io.save(state.clip(), state.filePath(), partManager);
        if (ok) markSaved();
        return ok;
    }

    public boolean saveAs(String filePath) {
        // Normalize before recording: the native save dialog may return a path
        // without the .omanim extension, but the serializer always writes with
        // it — the stored path must match the file that actually exists.
        String resolved = OMAFormat.ensureExtension(filePath);
        stampModelRef();
        boolean ok = io.save(state.clip(), resolved, partManager);
        if (ok) {
            state.setFilePath(resolved);
            markSaved();
        }
        return ok;
    }

    public boolean load(String filePath) {
        AnimationClip loaded = io.load(filePath, partManager);
        if (loaded == null) return false;
        state.setClip(loaded);
        state.setFilePath(filePath);
        history.clear();
        markSaved();
        applyCurrentPose();
        return true;
    }

    /**
     * The current clip as {@code .omanim} archive bytes (for embedding in an
     * SBE/SBO state). Null on serialization failure.
     */
    public byte[] exportClipBytes() {
        stampModelRef();
        return io.toBytes(state.clip(), partManager);
    }

    /**
     * Replace the current clip with one parsed from archive bytes (an SBE/SBO
     * state's embedded clip). The clip has no file path and is reported as
     * unsaved so the user knows it must be written back explicitly.
     *
     * @return false if the bytes failed to parse
     */
    public boolean importClipBytes(byte[] omaBytes, String sourceLabel) {
        AnimationClip loaded = io.fromBytes(omaBytes, sourceLabel, partManager);
        if (loaded == null) return false;
        state.setClip(loaded);
        state.setFilePath(null);
        history.clear();
        savedTop = null;
        forceDirty = true;
        refreshDirty();
        applyCurrentPose();
        return true;
    }

    private void stampModelRef() {
        String ref = modelRefSupplier.get();
        if (ref != null && !ref.isBlank()) state.clip().setModelRef(ref);
    }
}
