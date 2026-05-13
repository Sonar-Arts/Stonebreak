package com.openmason.main.systems.menus.animationEditor.io;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * File I/O for {@code .oma} animation clips. Owns the serializer pair and the
 * post-load rebind step that maps saved partIds onto the currently-loaded model.
 *
 * <p>Extracted from the controller so editing/history concerns and persistence
 * concerns live in separate classes.
 */
public final class OMAClipIO {

    private static final Logger logger = LoggerFactory.getLogger(OMAClipIO.class);

    private final OMASerializer serializer = new OMASerializer();
    private final OMADeserializer deserializer = new OMADeserializer();

    /**
     * Save {@code clip} to {@code filePath}. Part-name hints are populated from
     * {@code partManager} so future loads can rebind by name if IDs drift.
     */
    public boolean save(AnimationClip clip, String filePath, ModelPartManager partManager) {
        return serializer.save(clip, filePath, id -> partNameFor(partManager, id));
    }

    /**
     * Load a clip from {@code filePath}, then rebind any tracks whose saved
     * partId no longer matches a part on {@code partManager}, using the saved
     * name hint as a fallback. Returns {@code null} if the file fails to parse.
     */
    public AnimationClip load(String filePath, ModelPartManager partManager) {
        AnimationClip loaded = deserializer.load(filePath);
        if (loaded == null) return null;
        rebindTracksByName(loaded, partManager);
        return loaded;
    }

    private static String partNameFor(ModelPartManager partManager, String partId) {
        if (partManager == null) return null;
        return partManager.getPartById(partId).map(ModelPartDescriptor::name).orElse(null);
    }

    /**
     * For each track whose partId is missing on the current model, look up by
     * name hint and rewrite the track's key. Tracks with no match are kept as
     * orphans so the user can rebind manually.
     */
    private static void rebindTracksByName(AnimationClip clip, ModelPartManager partManager) {
        if (partManager == null) return;
        Map<String, Track> remap = new LinkedHashMap<>();
        for (var entry : new ArrayList<>(clip.tracks().entrySet())) {
            String savedId = entry.getKey();
            Track savedTrack = entry.getValue();

            if (partManager.getPartById(savedId).isPresent()) {
                remap.put(savedId, savedTrack);
                continue;
            }

            Optional<ModelPartDescriptor> nameMatch = findByName(partManager, savedTrack.partNameHint());
            if (nameMatch.isPresent()) {
                ModelPartDescriptor match = nameMatch.get();
                Track rebound = new Track(match.id());
                rebound.setPartNameHint(match.name());
                for (Keyframe kf : savedTrack.keyframes()) {
                    rebound.upsert(kf);
                }
                remap.put(match.id(), rebound);
                logger.info("Rebound animation track '{}' (saved id '{}') to current part id '{}'",
                        match.name(), savedId, match.id());
            } else {
                remap.put(savedId, savedTrack);
                logger.warn("Animation track for partId '{}' (name hint '{}') has no matching part in the loaded model",
                        savedId, savedTrack.partNameHint());
            }
        }
        clip.tracks().clear();
        clip.tracks().putAll(remap);
    }

    private static Optional<ModelPartDescriptor> findByName(ModelPartManager pm, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return pm.getAllParts().stream()
                .filter(p -> name.equalsIgnoreCase(p.name()))
                .findFirst();
    }
}
