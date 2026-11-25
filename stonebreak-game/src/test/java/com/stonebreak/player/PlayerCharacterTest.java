package com.stonebreak.player;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.entity.model.EntityAsset;
import com.stonebreak.mobs.entities.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stonebreak #456: the player character is SB_Player.sbe with its embedded .omchar (SBE 1.7). It passes
 * characterization validation against its own skeleton, maps every humanoid role it has bones for, carries the
 * standard sockets, and its metrics are what the game reads for proportions (the collision box stays a constant).
 */
class PlayerCharacterTest {

    @Test
    void thePlayerIsACharacterizedHumanoidThatFitsItsSkeleton() {
        PlayerCharacter pc = PlayerCharacter.get();
        CharacterDefinition def = pc.definition();
        assertEquals("humanoid", def.profile());
        assertTrue(def.locked(), "the role map is locked");
        assertEquals(33, def.roles().size());
        var rig = pc.asset().geometryFor(EntityAsset.DEFAULT_VARIANT).rig().rig();
        List<CharacterDefinition.Issue> issues = def.check(rig);
        assertTrue(issues.stream().noneMatch(i -> i.severity() == CharacterDefinition.Severity.ERROR), issues.toString());
        // Roles name the skeleton's BONES (the re-rig made them drive the model).
        for (String jointId : def.roles().values()) {
            int j = rig.find(jointId);
            assertTrue(j >= 0, jointId);
            assertEquals(com.openmason.engine.rig.RigJoint.Kind.BONE, rig.joints().get(j).kind(), jointId);
        }
    }

    @Test
    void proportionsComeFromTheAsset() {
        PlayerCharacter pc = PlayerCharacter.get();
        assertEquals(1.628f, pc.eyeHeight(), 1e-3f);
        assertEquals(1.787f, pc.height(), 1e-3f);
        assertEquals(0.958f, pc.hipHeight(), 1e-3f);
        // The model fits its gameplay collision box (0.6 x 1.8) and the eyes are inside it.
        assertTrue(pc.height() <= PlayerConstants.PLAYER_HEIGHT);
        assertTrue(pc.eyeHeight() < pc.height());
        assertTrue(pc.width() <= PlayerConstants.PLAYER_WIDTH);
        assertEquals(PlayerConstants.PLAYER_HEIGHT, EntityType.REMOTE_PLAYER.getHeight());
        assertEquals(PlayerCharacter.ASSET_ID, EntityType.REMOTE_PLAYER.getSbeObjectId());
        assertEquals(PlayerCharacter.ASSET_ID, pc.asset().objectId());
    }

    @Test
    void theStandardSocketsAreThere() {
        PlayerCharacter pc = PlayerCharacter.get();
        for (String socket : List.of("grip_r", "grip_l", "head", "eyes", "back")) {
            assertTrue(pc.socket(socket).isPresent(), socket);
        }
        // The eyes socket sits where the eye height says.
        var eyes = pc.socket("eyes").orElseThrow();
        assertEquals(pc.eyeHeight(), eyes.pos().y(), 0.02f);
    }

    @Test
    void anAssetWithoutACharacterIsRefused() {
        EntityAsset bare = new EntityAsset("test:bare", java.util.Map.of(), java.util.Map.of());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PlayerCharacter.load(bare));
        assertTrue(e.getMessage().contains("character"), e.getMessage());
    }
}
