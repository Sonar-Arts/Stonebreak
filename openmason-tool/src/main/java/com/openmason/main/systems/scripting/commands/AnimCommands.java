package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.animationEditor.io.OMAFormat;
import com.openmason.main.systems.menus.animationEditor.io.OMASerializer;
import com.openmason.main.systems.scripting.doc.ModelDocument;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Scripted animation authoring: builds DETACHED {@code .omanim} clips in
 * memory — identical semantics live and headless (clips never touch the
 * animation editor's open clip or its undo history; load a written file into
 * the editor to inspect it).
 *
 * <p>Tracks are keyed by the resolved part id AND carry the part name as a
 * binding hint (the game binds by id first, then name — names are the robust
 * key across saves). Omitted pose components default to the part's CURRENT
 * transform, so "key the rest pose at t=0" is just {@code clip.key(part, 0)}.
 *
 * <p>Saves are DEFERRED: {@code save()} validates and queues; the executor
 * flushes queued files only after the whole script succeeds, so a failing
 * script writes nothing.
 */
public final class AnimCommands {

    /** Trace hook shared with {@link ModelCommands} (single replayable funnel). */
    interface Tracer {
        void trace(String op, Consumer<ObjectNode> fill);
    }

    private record PendingSave(String clipName, Path absolutePath) {
    }

    private final ModelDocument doc;
    private final Tracer tracer;
    private final Path baseDir;

    private final Map<String, AnimationClip> clips = new LinkedHashMap<>();
    private final List<PendingSave> pendingSaves = new ArrayList<>();
    private String currentClip;

    AnimCommands(ModelDocument doc, Tracer tracer, Path baseDir) {
        this.doc = doc;
        this.tracer = tracer;
        this.baseDir = baseDir;
    }

    /** Compact clip digest. */
    public record ClipInfo(String name, float duration, float fps, boolean loop,
                           int tracks, int keyframes) {
    }

    // ===================== Commands =====================

    /** Create a new clip and make it current. Names must be unique per run. */
    public String createClip(String name, Float duration, Float fps, Boolean loop) {
        if (name == null || name.isBlank()) {
            throw new CommandException("clip name is required");
        }
        if (clips.containsKey(name)) {
            throw new CommandException("A clip named '" + name + "' already exists in this run",
                    "clip names identify the clip in later anim ops; pick a unique name");
        }
        AnimationClip clip = new AnimationClip(
                name,
                fps != null ? fps : 30f,
                duration != null ? duration : 1f,
                loop == null || loop,
                null);
        clips.put(name, clip);
        currentClip = name;

        tracer.trace("anim_clip", op -> {
            op.put("name", name);
            if (duration != null) op.put("duration", duration);
            if (fps != null) op.put("fps", fps);
            if (loop != null) op.put("loop", loop);
        });
        return name;
    }

    /**
     * Upsert a keyframe on a part's track. Omitted pose components come from
     * the part's current transform; easing defaults to LINEAR.
     */
    public void key(String clipOrNull, String partIdOrName, float time,
                    Vector3f position, Vector3f rotation, Vector3f scale, String easingName) {
        AnimationClip clip = resolveClip(clipOrNull);
        ModelPartDescriptor part = resolvePart(partIdOrName);
        if (time < 0) {
            throw new CommandException("keyframe time must be >= 0, got " + time);
        }
        if (time > clip.duration() + 1e-4f) {
            throw new CommandException("keyframe time " + time + " is past clip '"
                    + clip.name() + "' duration " + clip.duration(),
                    "extend the clip duration first (anim_clip / om.anim.clip(duration=...))");
        }
        Easing easing = parseEasing(easingName);

        PartTransform t = part.transform();
        Keyframe kf = new Keyframe(time,
                position != null ? new Vector3f(position) : new Vector3f(t.position()),
                rotation != null ? new Vector3f(rotation) : new Vector3f(t.rotation()),
                scale != null ? new Vector3f(scale) : new Vector3f(t.scale()),
                easing);
        Track track = clip.ensureTrack(part.id());
        track.setPartNameHint(part.name());
        track.upsert(kf);

        String clipName = clip.name();
        tracer.trace("anim_key", op -> {
            op.put("clip", clipName);
            op.put("part", part.name());
            op.put("time", time);
            op.set("position", vecNode(op, kf.position()));
            op.set("rotation", vecNode(op, kf.rotation()));
            op.set("scale", vecNode(op, kf.scale()));
            if (easing != Easing.LINEAR) op.put("easing", easing.name());
        });
    }

    /** Update the clip's mixing-layer metadata (any subset of fields). */
    public void setLayer(String clipOrNull, String type, List<String> maskParts,
                         Float fadeIn, Float fadeOut, Integer priority) {
        AnimationClip clip = resolveClip(clipOrNull);
        boolean any = false;
        if (type != null) {
            clip.setLayerType(parseLayerType(type));
            any = true;
        }
        if (maskParts != null) {
            List<String> names = new ArrayList<>();
            for (String maskEntry : maskParts) {
                names.add(resolvePart(maskEntry).name()); // mask is by part NAME
            }
            clip.setMaskParts(names);
            any = true;
        }
        if (fadeIn != null) {
            requireNonNegative(fadeIn, "fade_in");
            clip.setFadeInSeconds(fadeIn);
            any = true;
        }
        if (fadeOut != null) {
            requireNonNegative(fadeOut, "fade_out");
            clip.setFadeOutSeconds(fadeOut);
            any = true;
        }
        if (priority != null) {
            clip.setLayerPriority(priority);
            any = true;
        }
        if (!any) {
            throw new CommandException(
                    "pass at least one of type, mask, fade_in, fade_out, priority");
        }

        String clipName = clip.name();
        tracer.trace("anim_layer", op -> {
            op.put("clip", clipName);
            if (type != null) op.put("type", clip.layerType().name());
            if (maskParts != null) {
                var arr = op.arrayNode();
                clip.maskParts().forEach(arr::add);
                op.set("mask", arr);
            }
            if (fadeIn != null) op.put("fade_in", fadeIn);
            if (fadeOut != null) op.put("fade_out", fadeOut);
            if (priority != null) op.put("priority", priority);
        });
    }

    /**
     * Queue the clip for writing as {@code .omanim}. Validated now (path
     * resolvability, non-empty clip), written only after the script succeeds.
     */
    public void save(String clipOrNull, String path) {
        AnimationClip clip = resolveClip(clipOrNull);
        if (path == null || path.isBlank()) {
            throw new CommandException("save path is required",
                    "e.g. \"idle.omanim\" (relative to the CLI output) or an absolute path");
        }
        if (clip.tracks().isEmpty()) {
            throw new CommandException("clip '" + clip.name() + "' has no keyframes to save",
                    "add at least one key before saving");
        }
        Path resolved = Path.of(OMAFormat.ensureExtension(path));
        if (!resolved.isAbsolute()) {
            if (baseDir == null) {
                throw new CommandException("relative save path '" + path
                        + "' needs a working directory",
                        "live runs have none — use an absolute path");
            }
            resolved = baseDir.resolve(resolved);
        }
        pendingSaves.add(new PendingSave(clip.name(), resolved.normalize()));

        String clipName = clip.name();
        String tracePath = path;
        tracer.trace("anim_save", op -> {
            op.put("clip", clipName);
            op.put("path", tracePath);
        });
    }

    // ===================== Queries =====================

    public ClipInfo info(String clipOrNull) {
        AnimationClip clip = resolveClip(clipOrNull);
        int keys = clip.tracks().values().stream().mapToInt(Track::size).sum();
        return new ClipInfo(clip.name(), clip.duration(), clip.fps(), clip.loop(),
                clip.tracks().size(), keys);
    }

    public List<String> clipNames() {
        return List.copyOf(clips.keySet());
    }

    // ===================== Executor hooks =====================

    /**
     * Write all queued clips. Called by the executor only after the script
     * succeeded; throws on the first failure (earlier files stay written —
     * the failure names the path).
     *
     * @return absolute paths written, in save order
     */
    public List<String> flushSaves() {
        List<String> written = new ArrayList<>();
        OMASerializer serializer = new OMASerializer();
        for (PendingSave pending : pendingSaves) {
            AnimationClip clip = clips.get(pending.clipName());
            try {
                Path parent = pending.absolutePath().getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
            } catch (java.io.IOException e) {
                throw new CommandException("Cannot create directory for "
                        + pending.absolutePath() + ": " + e.getMessage());
            }
            boolean ok = serializer.save(clip, pending.absolutePath().toString(),
                    partId -> doc.parts().getPartById(partId)
                            .map(ModelPartDescriptor::name).orElse(null));
            if (!ok) {
                throw new CommandException("Failed to write " + pending.absolutePath(),
                        "check the directory exists and is writable");
            }
            written.add(pending.absolutePath().toString());
        }
        pendingSaves.clear();
        return written;
    }

    // ===================== Internals =====================

    private AnimationClip resolveClip(String clipOrNull) {
        String name = clipOrNull != null && !clipOrNull.isBlank() ? clipOrNull : currentClip;
        if (name == null) {
            throw new CommandException("no clip exists yet",
                    "create one first: anim_clip / om.anim.clip(name, duration=..)");
        }
        AnimationClip clip = clips.get(name);
        if (clip == null) {
            throw new CommandException("No clip '" + name + "'. Known clips: "
                    + String.join(", ", clips.keySet()),
                    "clip names come from anim_clip / om.anim.clip");
        }
        return clip;
    }

    private ModelPartDescriptor resolvePart(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new CommandException("part name is required");
        }
        return doc.parts().getPartById(idOrName)
                .or(() -> doc.parts().getPartByName(idOrName))
                .orElseThrow(() -> {
                    List<String> known = doc.parts().getAllParts().stream()
                            .map(ModelPartDescriptor::name).toList();
                    return new CommandException("No part '" + idOrName + "' (known parts: "
                            + String.join(", ", known.subList(0, Math.min(known.size(), 15)))
                            + ")", "animation tracks bind to model parts; create the part first");
                });
    }

    private static Easing parseEasing(String name) {
        if (name == null || name.isBlank()) return Easing.LINEAR;
        try {
            return Easing.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CommandException("Unknown easing '" + name + "'",
                    "valid: linear, ease_in, ease_out, ease_in_out");
        }
    }

    private static AnimLayerMeta.LayerType parseLayerType(String name) {
        try {
            return AnimLayerMeta.LayerType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CommandException("Unknown layer type '" + name + "'",
                    "valid: base (drives the whole model), overlay (owns only its masked parts)");
        }
    }

    private static void requireNonNegative(float value, String what) {
        if (value < 0) {
            throw new CommandException(what + " must be >= 0, got " + value);
        }
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode vecNode(ObjectNode owner, Vector3f v) {
        var arr = owner.arrayNode();
        arr.add(v.x).add(v.y).add(v.z);
        return arr;
    }
}
