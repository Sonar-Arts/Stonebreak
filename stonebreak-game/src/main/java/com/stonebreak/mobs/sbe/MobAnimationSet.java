package com.stonebreak.mobs.sbe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.animgraph.AnimGraph;
import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.rig.Rig;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.player.PlayerCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every graph-animated mob of one type shares (#460): its model, rig and character, the animation graph its
 * character names, the clips bound to the graph's slots and the clips it plays as montages. Loaded once per type, kept
 * for the process; {@link #newInstance} makes one graph instance per mob ({@link MobBodyAnimator}).
 *
 * <p>A type is graph-animated when it has a {@link MobGraphSpec} ({@link EntityType#graphAnimation()}) and its SBE
 * carries a character ({@code .omchar}, SBE 1.7) naming an {@code animGraph}. The slots bind the spec's clip source's
 * clips of the same name, retargeted onto the mob ({@code character.Retargeter}: one per mob, a retargeter belongs to
 * one thread), or the mob's own clips when the spec names no source. The mob's own clips that do not loop (attacks)
 * are its MONTAGES, played into the graph's {@link #MONTAGE_SLOT} slot while their behaviour state lasts.
 *
 * <p>Gait: the graph's {@link #GAIT_NODE} blend names the walk and run points of its speed parameter (its first and
 * last samples) and the clips there; {@link #walkNatural}/{@link #runNatural} are how fast those clips carry THIS body
 * at play rate 1 ({@link GaitSpeeds#stanceSpeed}, model units per second), so a mob can play them at the rate its real
 * ground speed needs ({@link MobBodyAnimator}).
 */
public final class MobAnimationSet {

    private static final Logger LOG = LoggerFactory.getLogger(MobAnimationSet.class);

    /** The graph's montage slot the mob's own one-shot clips play into. */
    public static final String MONTAGE_SLOT = "fullBody";
    /** The walk/run blend whose samples are the gait points. */
    public static final String GAIT_NODE = "gait";
    public static final String SPEED = "speed";
    public static final String GROUNDED = "grounded";
    public static final String IN_WATER = "inWater";
    public static final String GAIT_RATE = "gaitRate";
    /** How long a gait clip is sampled to measure its speed, seconds. */
    private static final float GAIT_PROBE_SECONDS = 4f;

    private static final Map<EntityType, Optional<MobAnimationSet>> SETS = new ConcurrentHashMap<>();

    private final EntityType type;
    private final MobGraphSpec spec;
    private final EntityAsset asset;
    private final Rig rig;
    private final CharacterDefinition character;
    private final AnimGraph graph;
    private final Rig sourceRig;
    private final CharacterDefinition sourceCharacter;
    private final AnimClipDocument[] slotClips;
    private final Map<MobBehaviorState, AnimClipDocument> montages = new EnumMap<>(MobBehaviorState.class);
    final int speed, grounded, inWater, gaitRate, montageSlot;
    /** The graph's speed values of its walk and run (the gait blend's first and last samples). */
    final float walkPoint, runPoint;
    /** How fast the walk and run clips carry this body at play rate 1, model units per second. */
    final float walkNatural, runNatural;

    /**
     * The set of {@code type}, or null when the type is not graph-animated. Built on first use (any thread); a type
     * whose graph cannot be built logs why once and keeps its named clips.
     */
    public static MobAnimationSet of(EntityType type) {
        if (type == null || type.graphAnimation() == null) return null;
        return SETS.computeIfAbsent(type, t -> {
            try {
                EntityAsset asset = SbeEntityRegistry.get(t.getSbeObjectId());
                if (asset == null || !asset.hasCharacter()) {
                    LOG.warn("{}: graph animation declared but its SBE carries no character; named clips stay",
                            t.getSbeObjectId());
                    return Optional.empty();
                }
                return Optional.of(new MobAnimationSet(t, asset));
            } catch (RuntimeException e) {
                LOG.error("{}: cannot build its animation graph; named clips stay", t.getSbeObjectId(), e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** A set for {@code type} over {@code asset} (which must embed a character naming a graph). Public for tests. */
    public MobAnimationSet(EntityType type, EntityAsset asset) {
        this.type = type;
        this.spec = type.graphAnimation();
        if (spec == null) throw new IllegalArgumentException(type + " declares no graph animation");
        this.asset = asset;
        EntityGeometry geometry = asset.geometryFor(EntityAsset.DEFAULT_VARIANT);
        if (geometry == null || geometry.rig() == null) {
            throw new IllegalStateException(asset.objectId() + ": no rigged default geometry");
        }
        this.rig = geometry.rig().rig();
        this.character = characterOf(asset, rig);
        String graphPath = character.animGraph();
        if (graphPath == null || graphPath.isBlank()) {
            throw new IllegalStateException(asset.objectId() + ": its character names no animation graph");
        }
        byte[] graphBytes = resource(graphPath);
        try {
            this.graph = AnimGraph.read(graphBytes);
        } catch (IOException e) {
            throw new IllegalStateException("animation graph " + graphPath + " does not read", e);
        }

        // Where the slot clips come from.
        EntityAsset source = asset;
        if (spec.clipSource() != null) {
            if (PlayerCharacter.ASSET_ID.equals(spec.clipSource())) {
                PlayerCharacter pc = PlayerCharacter.get();
                source = pc.asset();
                this.sourceCharacter = pc.definition();
            } else {
                source = SbeEntityRegistry.get(spec.clipSource());
                if (source == null) throw new IllegalStateException("clip source " + spec.clipSource() + " not found");
                this.sourceCharacter = characterOf(source, rigOf(source));
            }
            this.sourceRig = rigOf(source);
        } else {
            this.sourceRig = null;
            this.sourceCharacter = null;
        }
        List<String> slots = graph.slots();
        this.slotClips = new AnimClipDocument[slots.size()];
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            byte[] bytes = source.clipBytesFor(slots.get(i));
            if (bytes == null) {
                missing.add(slots.get(i));
                continue;
            }
            slotClips[i] = readClip(source, slots.get(i), bytes);
        }
        if (!missing.isEmpty()) {
            LOG.debug("{}: graph {} slots without a clip in {} (rest pose where used): {}", asset.objectId(),
                    graphPath, source.objectId(), missing);
        }

        // Montages: the mob's own clips that do not loop, by the behaviour state that plays them.
        for (MobBehaviorState state : MobBehaviorState.values()) {
            String name = MobStateMapping.sbeState(type, state);
            byte[] bytes = asset.clipBytesFor(name);
            if (bytes == null) continue;
            AnimClipDocument clip = readClip(asset, name, bytes);
            if (!clip.header().loop()) montages.put(state, clip);
        }

        this.speed = graph.param(SPEED);
        this.grounded = graph.param(GROUNDED);
        this.inWater = graph.param(IN_WATER);
        this.gaitRate = graph.param(GAIT_RATE);
        this.montageSlot = graph.montageSlot(MONTAGE_SLOT);

        // Gait points and how fast their clips carry this body.
        float[] points = new float[2];
        int[] gaitSlots = gaitSlots(graphBytes, points);
        this.walkPoint = points[0];
        this.runPoint = points[1];
        try (Retargeter probe = newRetargeter()) {
            this.walkNatural = natural(gaitSlots[0], probe);
            this.runNatural = natural(gaitSlots[1], probe);
        }
        LOG.info("{}: animation graph {} (clips from {}), {} montages, walk {} at {} u/s, run {} at {} u/s",
                asset.objectId(), graphPath, source.objectId(), montages.keySet(), walkPoint, walkNatural, runPoint,
                runNatural);
    }

    private float natural(int slot, Retargeter probe) {
        if (slot < 0 || slotClips[slot] == null) return 0f;
        return GaitSpeeds.stanceSpeed(rig, character, slotClips[slot], probe, GAIT_PROBE_SECONDS);
    }

    /** The slots of the gait blend's first and last samples; their speed values into {@code points}. */
    private int[] gaitSlots(byte[] graphJson, float[] points) {
        try {
            JsonNode root = new ObjectMapper().readTree(graphJson);
            JsonNode gait = null;
            for (JsonNode n : root.path("nodes")) {
                if (GAIT_NODE.equals(n.path("id").asText())) gait = n;
            }
            JsonNode samples = gait == null ? null : gait.path("samples");
            if (samples == null || samples.size() < 2) {
                throw new IllegalStateException("graph has no '" + GAIT_NODE + "' blend with two samples");
            }
            JsonNode first = samples.get(0), last = samples.get(samples.size() - 1);
            points[0] = (float) first.path("value").asDouble();
            points[1] = (float) last.path("value").asDouble();
            return new int[]{slotOfNode(root, first.path("node").asText()), slotOfNode(root, last.path("node").asText())};
        } catch (IOException e) {
            throw new IllegalStateException("graph does not parse", e);
        }
    }

    private int slotOfNode(JsonNode root, String id) {
        for (JsonNode n : root.path("nodes")) {
            if (id.equals(n.path("id").asText())) return graph.slot(n.path("slot").asText());
        }
        return -1;
    }

    private static CharacterDefinition characterOf(EntityAsset asset, Rig rig) {
        CharacterDefinition def;
        try {
            def = CharacterDefinition.read(asset.character());
        } catch (IOException e) {
            throw new IllegalStateException(asset.objectId() + ": unreadable character definition", e);
        }
        List<CharacterDefinition.Issue> errors = def.check(rig).stream()
                .filter(i -> i.severity() == CharacterDefinition.Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new IllegalStateException(asset.objectId() + ": the character does not fit its skeleton: " + errors);
        }
        return def;
    }

    private static Rig rigOf(EntityAsset asset) {
        EntityGeometry g = asset.geometryFor(EntityAsset.DEFAULT_VARIANT);
        if (g == null || g.rig() == null) throw new IllegalStateException(asset.objectId() + ": no rigged geometry");
        return g.rig().rig();
    }

    private static AnimClipDocument readClip(EntityAsset asset, String name, byte[] bytes) {
        try {
            return AnimClipDocument.read(bytes);
        } catch (IOException e) {
            throw new IllegalStateException(asset.objectId() + ": clip '" + name + "' does not read", e);
        }
    }

    private static byte[] resource(String path) {
        String r = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = MobAnimationSet.class.getResourceAsStream(r)) {
            if (in == null) throw new IllegalStateException("animation graph not found: " + r);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("animation graph " + r + " does not read", e);
        }
    }

    /** A retargeter from the clip source onto this mob (owned by the caller), or null when the clips are its own. */
    public Retargeter newRetargeter() {
        return sourceRig == null ? null : Retargeter.create(sourceRig, sourceCharacter, rig, character);
    }

    /**
     * A new graph instance with every available slot bound (through {@code retargeter} when the clips come from a
     * source) and every montage added; the montage ids land in {@code montageIds}. Owned by the caller (close it).
     */
    public AnimGraphInstance newInstance(Retargeter retargeter, Map<MobBehaviorState, Integer> montageIds) {
        AnimGraphInstance instance = AnimGraphInstance.create(graph, rig, character);
        for (int i = 0; i < slotClips.length; i++) {
            if (slotClips[i] == null) continue;
            if (retargeter != null) instance.bindSlotRetargeted(i, slotClips[i], retargeter);
            else instance.bindSlot(i, slotClips[i]);
        }
        for (Map.Entry<MobBehaviorState, AnimClipDocument> m : montages.entrySet()) {
            montageIds.put(m.getKey(), instance.addMontage(m.getValue()));
        }
        return instance;
    }

    public EntityType type() { return type; }
    public MobGraphSpec spec() { return spec; }
    public EntityAsset asset() { return asset; }
    public Rig rig() { return rig; }
    public CharacterDefinition character() { return character; }
    public AnimGraph graph() { return graph; }

    /** The clip bound to graph slot {@code name} (retargeted at bind time when it comes from a source), or null. */
    public AnimClipDocument slotClip(String name) {
        int i = graph.slot(name);
        return i < 0 ? null : slotClips[i];
    }

    /** The behaviour states that play as montages. */
    public java.util.Set<MobBehaviorState> montageStates() { return montages.keySet(); }

    public float walkPoint() { return walkPoint; }
    public float runPoint() { return runPoint; }
    public float walkNatural() { return walkNatural; }
    public float runNatural() { return runNatural; }
}
