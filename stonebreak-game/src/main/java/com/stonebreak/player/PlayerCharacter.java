package com.stonebreak.player;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.entity.model.EntityAsset;
import com.stonebreak.mobs.sbe.GameSbeAssets;

import java.util.List;
import java.util.Optional;

/**
 * The player character (Stonebreak #456): the data asset every player figure is built from - the local body, the
 * first-person view, other players, decoys, previews - in the way Unreal pairs a Character with its data asset. It is
 * {@code SB_Player.sbe}: a re-rigged humanoid whose bones are its skeleton, carrying its {@code .omchar} character
 * definition (SBE 1.7: role map, reference pose, limits, chains, sockets, metrics; made in Open Mason's Character
 * workspace).
 *
 * <p>Proportions come from the asset: the eye height, height, hip height and width are the character's metrics, so
 * the camera, reach and sight origins, name tags, battle framing and fallback figures follow the model. The COLLISION
 * box is not a proportion but a gameplay constant ({@link PlayerConstants#PLAYER_WIDTH} x
 * {@link PlayerConstants#PLAYER_HEIGHT}; {@code HitboxMatchesModelTest} keeps it consistent with the model).
 *
 * <p>Loaded once, on first use, on any thread (the integrated server reads the eye height too). There is no literal
 * fallback: an asset without a character definition that fits its skeleton fails loudly.
 */
public final class PlayerCharacter {

    /** The player asset's object id (SBE {@code objectId}). */
    public static final String ASSET_ID = "stonebreak:Player";
    /** Its game resource path. */
    public static final String ASSET_PATH = "/sbe/Mobs/SB_Player.sbe";

    private static volatile PlayerCharacter instance;

    private final EntityAsset asset;
    private final CharacterDefinition definition;
    private final CharacterDefinition.Metrics metrics;

    private PlayerCharacter(EntityAsset asset, CharacterDefinition definition) {
        this.asset = asset;
        this.definition = definition;
        this.metrics = definition.metrics();
    }

    /** The player character (loaded on first use). */
    public static PlayerCharacter get() {
        PlayerCharacter c = instance;
        if (c == null) {
            synchronized (PlayerCharacter.class) {
                c = instance;
                if (c == null) {
                    c = load(GameSbeAssets.load(ASSET_PATH));
                    instance = c;
                }
            }
        }
        return c;
    }

    /** A player character over {@code asset} (its embedded definition checked against its skeleton). */
    static PlayerCharacter load(EntityAsset asset) {
        if (asset == null) {
            throw new IllegalStateException("player asset " + ASSET_PATH + " did not load");
        }
        if (!asset.hasCharacter()) {
            throw new IllegalStateException(ASSET_PATH + " embeds no character definition (SBE 1.7 character.omchar)");
        }
        CharacterDefinition def;
        try {
            def = CharacterDefinition.read(asset.character());
        } catch (Exception e) {
            throw new IllegalStateException(ASSET_PATH + ": unreadable character definition: " + e.getMessage(), e);
        }
        var rig = asset.geometryFor(EntityAsset.DEFAULT_VARIANT).rig().rig();
        List<CharacterDefinition.Issue> errors = def.check(rig).stream()
                .filter(i -> i.severity() == CharacterDefinition.Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            def.close();
            throw new IllegalStateException(ASSET_PATH + ": the character does not fit its skeleton: " + errors);
        }
        return new PlayerCharacter(asset, def);
    }

    public EntityAsset asset() {
        return asset;
    }

    /** The character definition (roles, reference pose, limits, chains, sockets, metrics). */
    public CharacterDefinition definition() {
        return definition;
    }

    /** Eyes above the feet (the camera, reach and sight origin). */
    public float eyeHeight() {
        return metrics.eyeHeight();
    }

    /** Top of the head above the feet. */
    public float height() {
        return metrics.height();
    }

    /** Hips above the feet. */
    public float hipHeight() {
        return metrics.hipHeight();
    }

    /** Shoulder-to-shoulder width. */
    public float width() {
        return metrics.width();
    }

    /** A named socket ({@code grip_r}, {@code grip_l}, {@code head}, {@code eyes}, {@code back}, or custom). */
    public Optional<CharacterDefinition.Socket> socket(String name) {
        return definition.sockets().stream().filter(s -> s.name().equals(name)).findFirst();
    }
}
