package com.stonebreak.mobs.goblin;

import com.openmason.engine.format.oma.ParsedAnimClip;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.entities.combat.WeaponProfile;
import com.stonebreak.mobs.sbe.Clothing;
import com.stonebreak.mobs.sbe.MobStateMapping;
import com.stonebreak.mobs.sbe.SbeAttachmentPoint;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goblin's code and its authored asset must agree: every state the AI can enter has a clip,
 * every attack's timing matches its clip's length, and every socket a loadout hangs something on
 * exists on the model. Each of these fails silently in game — a rest pose, a blow that lands after
 * the swing has finished, a weapon that never appears — so they are pinned here instead.
 */
class GoblinAssetContractTest {

    private static final float CLIP_TOLERANCE_SECONDS = 0.02f;

    private final SbeEntityAsset goblin = SbeEntityLoader.load("/sbe/Mobs/SB_Goblin.sbe");

    private ParsedAnimClip clipFor(MobBehaviorState state) {
        String name = MobStateMapping.sbeState(EntityType.GOBLIN, state);
        ParsedAnimClip clip = goblin.clipFor(name);
        assertNotNull(clip, "SB_Goblin.sbe has no '" + name + "' clip for " + state);
        return clip;
    }

    @Test
    void theAssetIsTheGoblin() {
        assertEquals(EntityType.GOBLIN.getSbeObjectId(), goblin.objectId());
    }

    @Test
    void everyStateTheGoblinEntersHasAClip() {
        for (MobBehaviorState state : new MobBehaviorState[] {MobBehaviorState.IDLE, MobBehaviorState.WANDERING,
                MobBehaviorState.STAB, MobBehaviorState.SMASH, MobBehaviorState.DRAW_BOW, MobBehaviorState.RELEASE_BOW}) {
            clipFor(state);
        }
    }

    @Test
    void attackTimingsMatchTheirClips() {
        for (MobWeapon weapon : MobWeapon.values()) {
            switch (weapon.profile()) {
                case WeaponProfile.Melee melee -> {
                    float length = clipFor(melee.animation()).duration();
                    assertEquals(length, melee.clipSeconds(), CLIP_TOLERANCE_SECONDS, weapon + " swing length");
                    assertTrue(melee.impactSeconds() < melee.clipSeconds(), weapon + " lands inside its swing");
                }
                case WeaponProfile.Ranged ranged -> {
                    assertEquals(clipFor(MobBehaviorState.DRAW_BOW).duration(), ranged.drawSeconds(),
                            CLIP_TOLERANCE_SECONDS, weapon + " draw length");
                    assertEquals(clipFor(MobBehaviorState.RELEASE_BOW).duration(), ranged.releaseSeconds(),
                            CLIP_TOLERANCE_SECONDS, weapon + " release length");
                }
            }
        }
    }

    @Test
    void everySocketALoadoutUsesExists() {
        Set<String> sockets = new HashSet<>();
        for (SbeAttachmentPoint point : goblin.geometryFor(SbeEntityAsset.DEFAULT_VARIANT).attachmentPoints()) {
            sockets.add(point.name().toLowerCase(Locale.ROOT));
        }
        Set<String> needed = new HashSet<>(Set.of(Clothing.HAT_SOCKET, Clothing.EARRING_SOCKET));
        for (MobWeapon weapon : MobWeapon.values()) {
            needed.add(weapon.socket());
        }
        for (String socket : needed) {
            assertTrue(sockets.contains(socket.toLowerCase(Locale.ROOT)),
                    "SB_Goblin.sbe lacks socket '" + socket + "'; has " + sockets);
        }
    }

    @Test
    void everyCosmeticDecodesWithItsVariants() {
        for (String path : new String[] {Clothing.PIRATE_HAT, Clothing.GOLD_EARRING, Clothing.BANDANA}) {
            assertNotNull(SbeEntityLoader.loadAttachableResource(path).geometryFor(SbeEntityAsset.DEFAULT_VARIANT), path);
        }
        Set<String> colors = SbeEntityLoader.loadAttachableResource(Clothing.BANDANA).variants().keySet();
        assertTrue(colors.containsAll(Clothing.BANDANA_COLORS), "bandana colours " + colors);
    }
}
