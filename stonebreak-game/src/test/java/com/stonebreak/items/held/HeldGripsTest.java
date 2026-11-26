package com.stonebreak.items.held;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.torch.TorchBlock;
import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.PlayerCharacter;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #458: held grips are data - a default per held kind plus SBO {@code held} overrides - on the character's sockets. */
class HeldGripsTest {

    @Test
    void everyDrawableKindHasADefaultGripOnASocketThePlayerHas() {
        for (HeldKind kind : HeldKind.values()) {
            if (kind == HeldKind.NONE) continue;
            HeldGrip grip = HeldGrips.defaultFor(kind);
            assertTrue(PlayerCharacter.get().socket(grip.socket()).isPresent(),
                    kind + " grips socket " + grip.socket() + " the player character lacks");
        }
    }

    @Test
    void everyHoldableObjectResolvesAGripOnAPlayerSocket() {
        List<Item> holdable = new ArrayList<>();
        for (ItemType t : ItemType.values()) holdable.add(t);
        for (BlockType b : BlockType.values()) holdable.add(b);
        int drawn = 0;
        for (Item item : holdable) {
            HeldKind kind = HeldKind.of(item);
            HeldGrip grip = HeldGrips.of(item);
            if (kind == HeldKind.NONE) {
                assertEquals(null, grip, item + " is not drawable in a hand");
                continue;
            }
            drawn++;
            assertNotNull(grip, item + " (" + kind + ") has no grip");
            assertTrue(PlayerCharacter.get().socket(grip.socket()).isPresent(),
                    item + " is held on socket " + grip.socket() + " the player character lacks");
        }
        assertTrue(drawn > 50, "blocks and items are drawable in a hand: " + drawn);
    }

    /**
     * The items whose sprites do not follow the default (handle bottom-left, tip top-right, symmetric about the blade)
     * carry their own grips; the sides are part of the data: a bow in the left hand (a right-handed archer), every
     * other one in the right.
     */
    @Test
    void theShippedOverridesAndTheirSides() {
        Map<Item, String> sides = Map.of(ItemType.BOW, "grip_l", ItemType.FISHING_ROD, "grip_r",
                ItemType.STAFF, "grip_r", TorchBlock.item(), "grip_r");
        sides.forEach((item, socket) -> {
            SboGameplay.HeldData held = HeldGrips.heldData(item).orElse(null);
            assertNotNull(held, item + " ships a held override");
            assertEquals(socket, held.socket(), item + " is held on " + socket);
            assertEquals(socket, HeldGrips.of(item).socket());
        });
        assertTrue(HeldGrips.heldData(ItemType.SWORD).isEmpty(), "the sword keeps the tool default");
        assertEquals(HeldKind.TOOL, HeldKind.of(ItemType.SWORD), "weapons are held like a handle");
        assertEquals(HeldKind.TOOL, HeldKind.of(ItemType.WOODEN_PICKAXE), "mining tools are held like a handle");
        assertEquals(HeldKind.ITEM, HeldKind.of(ItemType.WOODEN_BUCKET), "a bucket is held upright");
        assertEquals("grip_r", HeldGrips.of(ItemType.SWORD).socket(), "tools are held in the right hand");
    }

    /**
     * Which way each grip faces, in the socket frame (at rest = the model axes: forward -Z, the right hand's outside
     * +X, the left hand's -X). Canonical sprite frame: +Y handle to tip, +Z the image's front, -X the image's
     * upper-left side (an axe's blade, a bow's limbs, the rod's line), +X its lower-right side (a bow's string).
     */
    @Test
    void gripsFaceTheRightWay() {
        // Tools: tip forward and up, the blade side down and forward (an axe chops with it).
        Vector3f tip = dir(HeldGrips.defaultFor(HeldKind.TOOL), 0, 1, 0);
        assertTrue(tip.z < -0.3f && tip.y > 0.3f, "a tool points forward and up: " + tip);
        Vector3f blade = dir(HeldGrips.defaultFor(HeldKind.TOOL), -1, 0, 0);
        assertTrue(blade.y < -0.5f && blade.z < 0f, "a tool's blade side faces down and forward: " + blade);
        // Items: upright image (its own up stays up), front facing the right hand's outside.
        Vector3f up = dir(HeldGrips.defaultFor(HeldKind.ITEM), -1, 1, 0);
        assertTrue(up.y > 0.9f, "an item's image stands upright: " + up);
        Vector3f front = dir(HeldGrips.defaultFor(HeldKind.ITEM), 0, 0, 1);
        assertTrue(front.x > 0.9f, "an item shows its front to the outside of the right hand: " + front);
        // Bow (left hand): vertical, limbs forward, string toward the archer, front facing the left hand's outside.
        HeldGrip bow = HeldGrips.of(ItemType.BOW);
        assertTrue(dir(bow, 0, 1, 0).y > 0.9f, "a bow is held upright");
        assertTrue(dir(bow, 1, 0, 0).z > 0.9f, "the bow string faces the archer");
        assertTrue(dir(bow, 0, 0, 1).x < -0.9f, "the bow shows its front to the left hand's outside");
        // Rod: tip forward and up, the line (upper-left side of the image) hanging down.
        HeldGrip rod = HeldGrips.of(ItemType.FISHING_ROD);
        assertTrue(dir(rod, 0, 1, 0).z < -0.5f && dir(rod, 0, 1, 0).y > 0.5f, "the rod points forward and up");
        assertTrue(dir(rod, -1, 0, 0).y < -0.5f, "the rod's line hangs down");
        // Torch: the image's own up (a vertical stick) stands nearly upright, flame on top.
        assertTrue(dir(HeldGrips.of(TorchBlock.item()), -1, 1, 0).y > 0.9f, "a torch is held upright");
        // Staff: upright walking staff.
        assertTrue(dir(HeldGrips.of(ItemType.STAFF), 0, 1, 0).y > 0.95f, "a staff is held upright");
    }

    /**
     * A canonical-frame direction under the grip (normalised). The image's own up is the canonical {@code (-1, 1, 0)}
     * (the sprite frame turns the image 45 degrees so its diagonal stands on +Y).
     */
    private static Vector3f dir(HeldGrip grip, float x, float y, float z) {
        return grip.local().transformDirection(new Vector3f(x, y, z).normalize()).normalize();
    }

    @Test
    void aMalformedDefaultsFileFailsLoudly() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThrows(IllegalStateException.class, () -> HeldGrips.parseDefaults(json.readTree(
                "{\"format\":\"other\",\"version\":1,\"kinds\":{}}")));
        assertThrows(IllegalStateException.class, () -> HeldGrips.parseDefaults(json.readTree(
                "{\"format\":\"stonebreak.heldGrips\",\"version\":1,\"kinds\":{\"block\":{\"socket\":\"grip_r\"}}}")));
        assertThrows(IllegalStateException.class, () -> HeldGrips.parseDefaults(json.readTree(
                "{\"format\":\"stonebreak.heldGrips\",\"version\":1,\"kinds\":{"
                        + "\"block\":{\"socket\":\"grip_r\",\"scale\":0},"
                        + "\"tool\":{\"socket\":\"grip_r\"},\"item\":{\"socket\":\"grip_r\"},"
                        + "\"model\":{\"socket\":\"grip_r\"}}}")));
        Map<HeldKind, HeldGrip> ok = HeldGrips.parseDefaults(json.readTree(
                "{\"format\":\"stonebreak.heldGrips\",\"version\":1,\"kinds\":{"
                        + "\"block\":{\"socket\":\"grip_r\"},\"tool\":{\"socket\":\"grip_l\",\"scale\":0.5},"
                        + "\"item\":{\"socket\":\"grip_r\"},"
                        + "\"model\":{\"socket\":\"back\",\"position\":[1,2,3]}}}"));
        assertEquals(Set.of(HeldKind.BLOCK, HeldKind.TOOL, HeldKind.ITEM, HeldKind.MODEL), ok.keySet());
        assertFalse(ok.containsKey(HeldKind.NONE));
        assertEquals(3f, ok.get(HeldKind.MODEL).local().m32(), 1e-6f, "position is the grip's translation");
    }
}
