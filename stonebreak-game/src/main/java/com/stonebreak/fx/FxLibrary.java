package com.stonebreak.fx;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleImage;
import com.openmason.engine.rendering.particles.ParticleSpriteLoader;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.stonebreak.fx.sbfx.SbfxDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The FX systems this game ships (Stonebreak#435/#436), resolvable by the ids asset bindings use: the SBFX
 * {@code objectId} ({@code stonebreak:block_debris}) and the bare system id ({@code block_debris}). Immutable;
 * built once from the loaded {@code .sbfx} documents.
 *
 * <p>Also the source of what the shared particle texture array packs ({@link #effects()}, {@link #spriteLoader()}):
 * every document's embedded sprites, by texture name. Two documents embedding different images under one name
 * would share a layer, so the first one wins and the clash is logged.
 */
public final class FxLibrary {

    private static final Logger logger = LoggerFactory.getLogger(FxLibrary.class);

    /** No FX at all (no assets loaded yet, or a test). */
    public static final FxLibrary EMPTY = new FxLibrary(List.of());

    private final List<SbfxDocument> documents;
    private final Map<String, SbfxDocument> byId = new LinkedHashMap<>();
    private final Map<FxSystem, SbfxDocument> bySystem = new IdentityHashMap<>();
    private final Map<String, OmfxSprite> sprites = new LinkedHashMap<>();

    public FxLibrary(Collection<SbfxDocument> documents) {
        this.documents = List.copyOf(documents);
        for (SbfxDocument doc : this.documents) {
            if (byId.containsKey(doc.objectId())) {
                logger.warn("FX {} is shipped twice; keeping the first", doc.objectId());
                continue;
            }
            byId.put(doc.objectId(), doc);
            byId.putIfAbsent(doc.fxId(), doc);
            bySystem.put(doc.system(), doc);
            for (OmfxSprite sprite : doc.fx().sprites()) {
                OmfxSprite previous = sprites.putIfAbsent(sprite.name(), sprite);
                if (previous != null && !previous.equals(sprite)) {
                    logger.warn("FX sprite '{}' of {} clashes with another FX's image of that name; the first is used",
                            sprite.name(), doc.objectId());
                }
            }
        }
    }

    /** The system an asset binding names ({@code objectId} or bare id), or null when the game ships none. */
    public FxSystem find(String id) {
        SbfxDocument doc = id == null ? null : byId.get(id);
        return doc == null ? null : doc.system();
    }

    /** The shipped document {@code system} came from, or null. */
    public SbfxDocument documentOf(FxSystem system) {
        return bySystem.get(system);
    }

    public List<SbfxDocument> documents() {
        return documents;
    }

    public int size() {
        return documents.size();
    }

    /** Every effect any shipped system uses (emitters and their sub-emitter effects), for sprite packing. */
    public List<ParticleEffect> effects() {
        List<ParticleEffect> out = new ArrayList<>();
        Map<ParticleEffect, Boolean> seen = new IdentityHashMap<>();
        for (SbfxDocument doc : documents) {
            for (FxEmitter emitter : doc.system().emitters()) {
                collect(emitter.effect(), seen, out);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static void collect(ParticleEffect effect, Map<ParticleEffect, Boolean> seen, List<ParticleEffect> out) {
        if (effect == null || seen.put(effect, Boolean.TRUE) != null) {
            return;
        }
        out.add(effect);
        for (ParticleEffect.SubEmitter sub : effect.subEmitters()) {
            collect(sub.effect(), seen, out);
        }
    }

    /** The embedded sprites of every shipped FX, by texture name (null for a name none embeds). */
    public ParticleSpriteLoader spriteLoader() {
        return name -> {
            OmfxSprite sprite = sprites.get(name);
            return sprite == null ? null : decode(sprite);
        };
    }

    private static ParticleImage decode(OmfxSprite sprite) throws java.io.IOException {
        return sprite.decode();
    }
}
