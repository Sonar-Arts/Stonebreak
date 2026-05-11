package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.animationEditor.state.AnimationEditorState;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-safe facade over the Animation Editor's controller. Every method
 * marshals to the main/GL thread via {@link MainThreadExecutor} so MCP HTTP
 * threads can safely drive clip edits, transport, and persistence.
 *
 * <p>Mirrors the contract established by {@link ModelEditingService}.
 */
public final class AnimationEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final MainImGuiInterface mainInterface;

    public AnimationEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    // ===================== Read =====================

    public AnimationInfo getAnimationInfo() {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = optController();
            if (c == null) return new AnimationInfo(false, null, 0, 0, false, null, null, 0, false, false, 0);
            AnimationEditorState s = c.state();
            AnimationClip clip = s.clip();
            return new AnimationInfo(
                    true,
                    clip.name(), clip.fps(), clip.duration(), clip.loop(), clip.modelRef(),
                    s.filePath(),
                    clip.tracks().size(),
                    s.dirty(), s.playing(), s.playhead());
        }));
    }

    public List<TrackView> listTracks() {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            ModelPartManager pm = c.partManager();
            AnimationClip clip = c.state().clip();
            List<TrackView> out = new ArrayList<>(clip.tracks().size());
            for (Map.Entry<String, Track> e : clip.tracks().entrySet()) {
                String partId = e.getKey();
                String partName = (pm != null)
                        ? pm.getPartById(partId).map(ModelPartDescriptor::name).orElse(e.getValue().partNameHint())
                        : e.getValue().partNameHint();
                out.add(new TrackView(partId, partName, e.getValue().size()));
            }
            return out;
        }));
    }

    public Optional<List<KeyframeView>> listKeyframes(String partIdOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<List<KeyframeView>>empty();
            Track track = c.state().clip().trackFor(partId);
            if (track == null) return Optional.of(List.<KeyframeView>of());
            List<KeyframeView> out = new ArrayList<>(track.size());
            for (int i = 0; i < track.size(); i++) {
                out.add(KeyframeView.from(i, track.get(i)));
            }
            return Optional.of(out);
        }));
    }

    public Optional<KeyframeView> getKeyframe(String partIdOrName, int index) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            Track track = c.state().clip().trackFor(partId);
            if (track == null || index < 0 || index >= track.size()) return Optional.<KeyframeView>empty();
            return Optional.of(KeyframeView.from(index, track.get(index)));
        }));
    }

    // ===================== Mutate: clip metadata =====================

    public AnimationInfo setClipName(String name) {
        return runAndDescribe(c -> c.setClipName(name));
    }

    public AnimationInfo setClipFps(float fps) {
        return runAndDescribe(c -> c.setClipFps(fps));
    }

    public AnimationInfo setClipDuration(float duration) {
        return runAndDescribe(c -> c.setClipDuration(duration));
    }

    public AnimationInfo setClipLoop(boolean loop) {
        return runAndDescribe(c -> c.setClipLoop(loop));
    }

    // ===================== Mutate: transport =====================

    public AnimationInfo setPlayhead(float seconds) {
        return runAndDescribe(c -> {
            c.state().setPlayhead(seconds);
            c.applyCurrentPose();
        });
    }

    public AnimationInfo play() {
        return runAndDescribe(c -> c.state().setPlaying(true));
    }

    public AnimationInfo pause() {
        return runAndDescribe(c -> c.state().setPlaying(false));
    }

    public AnimationInfo stop() {
        return runAndDescribe(c -> {
            c.state().setPlaying(false);
            c.state().setPlayhead(0f);
            c.applyCurrentPose();
        });
    }

    public AnimationInfo applyPoseAtPlayhead() {
        return runAndDescribe(AnimationEditorController::applyCurrentPose);
    }

    // ===================== Mutate: keyframes =====================

    public Optional<KeyframeView> insertKeyframeAtPlayhead(String partIdOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            c.insertKeyframeAtPlayhead(partId);
            Track track = c.state().clip().trackFor(partId);
            if (track == null || track.isEmpty()) return Optional.<KeyframeView>empty();
            int idx = indexAtTime(track, c.state().playhead());
            return idx >= 0 ? Optional.of(KeyframeView.from(idx, track.get(idx))) : Optional.<KeyframeView>empty();
        }));
    }

    public Optional<KeyframeView> insertKeyframe(String partIdOrName, float time,
                                                  Vector3f position, Vector3f rotation, Vector3f scale,
                                                  String easingName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            ModelPartManager pm = c.partManager();
            Vector3f pos = position;
            Vector3f rot = rotation;
            Vector3f scl = scale;
            if ((pos == null || rot == null || scl == null) && pm != null) {
                Optional<ModelPartDescriptor> p = pm.getPartById(partId);
                if (p.isPresent()) {
                    if (pos == null) pos = new Vector3f(p.get().transform().position());
                    if (rot == null) rot = new Vector3f(p.get().transform().rotation());
                    if (scl == null) scl = new Vector3f(p.get().transform().scale());
                }
            }
            if (pos == null) pos = new Vector3f();
            if (rot == null) rot = new Vector3f();
            if (scl == null) scl = new Vector3f(1, 1, 1);
            Easing easing = Easing.fromString(easingName);
            Keyframe kf = new Keyframe(time, pos, rot, scl, easing);
            c.insertKeyframe(partId, kf);
            Track refreshed = c.state().clip().trackFor(partId);
            if (refreshed == null || refreshed.isEmpty()) return Optional.<KeyframeView>empty();
            int idx = indexAtTime(refreshed, time);
            if (idx < 0) idx = refreshed.size() - 1;
            return Optional.of(KeyframeView.from(idx, refreshed.get(idx)));
        }));
    }

    public Optional<KeyframeView> editKeyframe(String partIdOrName, int index,
                                                Float time,
                                                Vector3f position, Vector3f rotation, Vector3f scale,
                                                String easingName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            Track track = c.state().clip().trackFor(partId);
            if (track == null || index < 0 || index >= track.size()) return Optional.<KeyframeView>empty();
            Keyframe current = track.get(index);
            float newTime = time != null ? time : current.time();
            Vector3f newPos = position != null ? position : new Vector3f(current.position());
            Vector3f newRot = rotation != null ? rotation : new Vector3f(current.rotation());
            Vector3f newScale = scale != null ? scale : new Vector3f(current.scale());
            Easing easing = easingName != null ? Easing.fromString(easingName) : current.easing();
            Keyframe updated = new Keyframe(newTime, newPos, newRot, newScale, easing);
            c.editKeyframe(partId, index, updated);
            Track refreshed = c.state().clip().trackFor(partId);
            if (refreshed == null) return Optional.<KeyframeView>empty();
            int newIndex = indexAtTime(refreshed, newTime);
            if (newIndex < 0) newIndex = Math.min(index, refreshed.size() - 1);
            return Optional.of(KeyframeView.from(newIndex, refreshed.get(newIndex)));
        }));
    }

    public boolean deleteKeyframe(String partIdOrName, int index) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return false;
            Track track = c.state().clip().trackFor(partId);
            if (track == null || index < 0 || index >= track.size()) return false;
            c.deleteKeyframe(partId, index);
            return true;
        }));
    }

    public boolean deleteTrack(String partIdOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return false;
            if (!c.state().clip().tracks().containsKey(partId)) return false;
            c.state().clip().removeTrack(partId);
            c.state().markDirty();
            c.applyCurrentPose();
            return true;
        }));
    }

    // ===================== Undo / Redo =====================

    public boolean undo() {
        return await(MainThreadExecutor.submit(() -> requireController().undo()));
    }

    public boolean redo() {
        return await(MainThreadExecutor.submit(() -> requireController().redo()));
    }

    // ===================== File I/O =====================

    public AnimationInfo newClip() {
        return runAndDescribe(AnimationEditorController::newClip);
    }

    public boolean save() {
        return await(MainThreadExecutor.submit(() -> requireController().save()));
    }

    public boolean saveAs(String filePath) {
        return await(MainThreadExecutor.submit(() -> requireController().saveAs(filePath)));
    }

    public boolean load(String filePath) {
        return await(MainThreadExecutor.submit(() -> requireController().load(filePath)));
    }

    // ===================== Helpers =====================

    private AnimationEditorController optController() {
        AnimationEditorImGui editor = mainInterface.getAnimationEditor();
        return editor != null ? editor.getController() : null;
    }

    private AnimationEditorController requireController() {
        AnimationEditorController c = optController();
        if (c == null) throw new IllegalStateException("Animation editor not initialized");
        return c;
    }

    private String resolvePartId(AnimationEditorController c, String partIdOrName) {
        if (partIdOrName == null || partIdOrName.isBlank()) return null;
        ModelPartManager pm = c.partManager();
        if (pm == null) return c.state().clip().tracks().containsKey(partIdOrName) ? partIdOrName : null;
        Optional<ModelPartDescriptor> p = pm.getPartById(partIdOrName);
        if (p.isPresent()) return p.get().id();
        return pm.getPartByName(partIdOrName).map(ModelPartDescriptor::id)
                .orElseGet(() -> c.state().clip().tracks().containsKey(partIdOrName) ? partIdOrName : null);
    }

    private static int indexAtTime(Track track, float time) {
        for (int i = 0; i < track.size(); i++) {
            if (Math.abs(track.get(i).time() - time) < 1e-4f) return i;
        }
        return -1;
    }

    private AnimationInfo runAndDescribe(java.util.function.Consumer<AnimationEditorController> action) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            action.accept(c);
            return describeOnThread(c);
        }));
    }

    private AnimationInfo describeOnThread(AnimationEditorController c) {
        AnimationEditorState s = c.state();
        AnimationClip clip = s.clip();
        return new AnimationInfo(
                true,
                clip.name(), clip.fps(), clip.duration(), clip.loop(), clip.modelRef(),
                s.filePath(),
                clip.tracks().size(),
                s.dirty(), s.playing(), s.playhead());
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // ===================== DTOs =====================

    public record AnimationInfo(
            boolean available,
            String name, float fps, float duration, boolean loop, String modelRef,
            String filePath,
            int trackCount,
            boolean dirty, boolean playing, float playhead
    ) {}

    public record Vec3(float x, float y, float z) {
        public static Vec3 from(Vector3f v) { return new Vec3(v.x, v.y, v.z); }
    }

    public record TrackView(String partId, String partName, int keyframeCount) {}

    public record KeyframeView(int index, float time, Vec3 position, Vec3 rotation, Vec3 scale, String easing) {
        public static KeyframeView from(int index, Keyframe kf) {
            return new KeyframeView(
                    index, kf.time(),
                    Vec3.from(kf.position()), Vec3.from(kf.rotation()), Vec3.from(kf.scale()),
                    kf.easing() != null ? kf.easing().name() : Easing.LINEAR.name());
        }
    }

}
