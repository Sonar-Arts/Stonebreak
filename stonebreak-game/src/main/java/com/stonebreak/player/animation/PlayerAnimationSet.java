package com.stonebreak.player.animation;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.animgraph.AnimGraph;
import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.rig.Rig;
import com.stonebreak.mobs.sbe.GameSbeAssets;
import com.stonebreak.player.PlayerCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What every body of one player model shares (#457): the model, its rig, its character definition, its animation
 * graph ({@code .omanimgraph}, OpenMason #157) and the clips bound to the graph's slots. Loaded once, kept for the
 * process; {@link #newInstance()} makes one graph instance per body ({@link PlayerBodyAnimator}).
 *
 * <p>A graph slot binds the asset's clip of the SAME name (the SBE state name). A slot the asset has no clip for stays
 * unbound and logs once: the graph itself must fall back to clips that exist (OpenMason CLAUDE.md "Animation graph";
 * Java never substitutes clips).
 */
public final class PlayerAnimationSet {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerAnimationSet.class);

    /** The first-person arm model, until #458 draws first person from the player character itself. */
    public static final String FP_ARM_ASSET = "/sbe/Player/SB_FPArm.sbe";
    /** Its graph (the arm model carries no character definition to reference one). */
    public static final String FP_ARM_GRAPH = "animgraphs/SB_FPArm.omanimgraph";

    /** Parameter names the game sets (a graph may leave any out). */
    public static final String SPEED = "speed";
    public static final String GROUNDED = "grounded";
    public static final String IN_WATER = "inWater";
    public static final String SEATED = "seated";
    public static final String CROUCHED = "crouched";
    public static final String ATTACKING = "attacking";
    /** The walk/run blend whose phase is the gait (footsteps, first-person bob) while clips carry no notifies. */
    public static final String GAIT_NODE = "gait";
    /** The notify that plants a foot; every other notify name plays the asset's FX/sound event of that name. */
    public static final String FOOTSTEP = "footstep";

    private static volatile PlayerAnimationSet player;
    private static volatile PlayerAnimationSet firstPersonArm;

    private final EntityAsset asset;
    private final EntityGeometry geometry;
    private final Rig rig;
    private final CharacterDefinition character;
    private final AnimGraph graph;
    private final AnimClipDocument[] slotClips;
    private final boolean footstepNotifies;
    final int speed, grounded, inWater, seated, crouched, attacking;
    final int gaitNode;
    final int headJoint;

    /** The player character's set (SB_Player + the graph its .omchar {@code animGraph} names). Any thread. */
    public static PlayerAnimationSet player() {
        PlayerAnimationSet s = player;
        if (s == null) {
            synchronized (PlayerAnimationSet.class) {
                s = player;
                if (s == null) {
                    PlayerCharacter c = PlayerCharacter.get();
                    String graph = c.definition().animGraph();
                    if (graph == null || graph.isBlank()) {
                        throw new IllegalStateException(PlayerCharacter.ASSET_PATH
                                + ": its character names no animation graph (.omchar animGraph)");
                    }
                    s = player = new PlayerAnimationSet(c.asset(), c.definition(), graph);
                }
            }
        }
        return s;
    }

    /** The first-person arm's set. Any thread. */
    public static PlayerAnimationSet firstPersonArm() {
        PlayerAnimationSet s = firstPersonArm;
        if (s == null) {
            synchronized (PlayerAnimationSet.class) {
                s = firstPersonArm;
                if (s == null) {
                    s = firstPersonArm = new PlayerAnimationSet(GameSbeAssets.load(FP_ARM_ASSET), null, FP_ARM_GRAPH);
                }
            }
        }
        return s;
    }

    /**
     * A set over {@code asset} driven by the game resource graph {@code graphPath} (relative to the resource root).
     * Public for tests that build their own.
     */
    public PlayerAnimationSet(EntityAsset asset, CharacterDefinition character, String graphPath) {
        this.asset = asset;
        this.character = character;
        this.geometry = asset.geometryFor(EntityAsset.DEFAULT_VARIANT);
        if (geometry == null || geometry.rig() == null) {
            throw new IllegalStateException(asset.objectId() + ": no rigged default geometry");
        }
        this.rig = geometry.rig().rig();
        this.graph = readGraph(graphPath);
        List<String> slots = graph.slots();
        this.slotClips = new AnimClipDocument[slots.size()];
        boolean footsteps = false;
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            byte[] bytes = asset.clipBytesFor(slots.get(i));
            if (bytes == null) {
                missing.add(slots.get(i));
                continue;
            }
            try {
                slotClips[i] = AnimClipDocument.read(bytes);
            } catch (IOException e) {
                throw new IllegalStateException(asset.objectId() + ": clip '" + slots.get(i) + "' does not read", e);
            }
            for (AnimClipDocument.Notify n : slotClips[i].notifies()) {
                footsteps |= FOOTSTEP.equals(n.name());
            }
        }
        if (!missing.isEmpty()) {
            LOG.warn("{}: graph {} slots without a clip (rest pose where used): {}", asset.objectId(), graphPath, missing);
        }
        this.footstepNotifies = footsteps;
        this.speed = graph.param(SPEED);
        this.grounded = graph.param(GROUNDED);
        this.inWater = graph.param(IN_WATER);
        this.seated = graph.param(SEATED);
        this.crouched = graph.param(CROUCHED);
        this.attacking = graph.param(ATTACKING);
        this.gaitNode = graph.node(GAIT_NODE);
        this.headJoint = rig.headJoint();
    }

    private static AnimGraph readGraph(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = PlayerAnimationSet.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("animation graph not found: " + resource);
            return AnimGraph.read(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("animation graph " + resource + " does not read", e);
        }
    }

    /** A new graph instance with every available slot bound. Owned by the caller (close it). */
    public AnimGraphInstance newInstance() {
        AnimGraphInstance instance = AnimGraphInstance.create(graph, rig, character);
        for (int i = 0; i < slotClips.length; i++) {
            if (slotClips[i] != null) instance.bindSlot(i, slotClips[i]);
        }
        return instance;
    }

    public EntityAsset asset() { return asset; }
    public EntityGeometry geometry() { return geometry; }
    public Rig rig() { return rig; }
    public AnimGraph graph() { return graph; }

    /** Whether any bound clip carries {@link #FOOTSTEP} notifies (else the gait phase plants the feet). */
    public boolean footstepNotifies() { return footstepNotifies; }
}
