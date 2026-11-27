package com.stonebreak.mobs.sbe;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntitySockets;
import com.openmason.engine.rig.SocketSet;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The sockets of a host model (#459): its OMO attachment points plus, for a model with an embedded {@code .omchar},
 * the character's sockets (which win over an attachment point of the same name). One native {@link SocketSet} per
 * geometry, built once, shared by every body of that model.
 */
public final class HostSockets {

    private static final Map<EntityGeometry, SocketSet> WITH_CHARACTER =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private HostSockets() {
    }

    /** {@code asset}'s sockets in {@code variant} (default when null); null when the variant has no geometry. */
    public static SocketSet of(EntityAsset asset, String variant) {
        if (asset == null) return null;
        EntityGeometry geometry = asset.geometryFor(variant != null ? variant : EntityAsset.DEFAULT_VARIANT);
        if (geometry == null) return null;
        if (!asset.hasCharacter()) return EntitySockets.of(geometry);
        return WITH_CHARACTER.computeIfAbsent(geometry, g -> {
            try (CharacterDefinition character = CharacterDefinition.read(asset.character())) {
                return EntitySockets.build(g, character);
            } catch (Exception e) {
                throw new IllegalStateException(asset.objectId() + ": unreadable character definition", e);
            }
        });
    }
}
