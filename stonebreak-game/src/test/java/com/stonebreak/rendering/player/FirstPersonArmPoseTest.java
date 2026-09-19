package com.stonebreak.rendering.player;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.sbe.*;
import com.stonebreak.rendering.models.entities.SbePoseSolver;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FirstPersonArmPoseTest {
    @Test
    void onlyEmptyHandsUseTheNewArm() {
        assertTrue(FirstPersonArmPose.usesAuthoredArm(null));
        assertTrue(FirstPersonArmPose.usesAuthoredArm(new ItemStack(BlockType.AIR, 0)));
        assertTrue(FirstPersonArmPose.usesAuthoredArm(new ItemStack(ItemType.BOW, 0)));
        assertFalse(FirstPersonArmPose.usesAuthoredArm(new ItemStack(BlockType.STONE, 1)));
        for (ItemType item : ItemType.values()) {
            assertFalse(FirstPersonArmPose.usesAuthoredArm(new ItemStack(item, 1)), item.name());
        }
    }

    @Test
    void cameraMotionDoesNotMoveTheArmOnScreen() {
        var view = new Matrix4f().lookAt(new Vector3f(127, 60, -37), new Vector3f(126, 61, -39), new Vector3f(0, 1, 0));
        var original = new Matrix4f(view);
        var base = FirstPersonArmPose.worldTransform(view, 0f, false, new Matrix4f());
        assertTrue(new Matrix4f(view).mul(base).equals(new Matrix4f().translation(.32f, -.34f, -.12f), 1e-5f));
        assertEquals(original, view, "must not mutate the player's camera view");
    }

    @Test
    void idleAndImpactKeepTheFistVisibleAndOnTheRight() {
        var asset = SbeEntityLoader.load(FirstPersonArmPose.RESOURCE);
        var base = FirstPersonArmPose.worldTransform(new Matrix4f(), 0, false, new Matrix4f());
        var idle = point(asset, AnimState.single("idle", 0), base);
        var impact = point(asset, new AnimState("idle", 0, List.of(new AnimState.Overlay("attacking", .38f, 1))), base);
        assertTrue(idle.x > 0 && idle.y < 0 && idle.z < -.3f, "idle fist: " + idle);
        assertTrue(impact.z < idle.z - .1f, "punch reaches forward: " + impact);
        assertTrue(Math.abs(impact.x) < Math.abs(idle.x), "punch moves toward the crosshair");
        var projection = FirstPersonArmPose.projection(new Matrix4f().perspective(1.2f, 16f / 9, .1f, 100), new Matrix4f());
        for (var p : List.of(idle, impact)) {
            projection.transformProject(p);
            assertTrue(Math.abs(p.x) < .95f && Math.abs(p.y) < .95f, "fist is outside frame: " + p);
        }
    }

    private static Vector3f point(SbeEntityAsset asset, AnimState anim, Matrix4f base) {
        var result = new Vector3f();
        SbePoseSolver.forEachPartMatrix(asset.geometryFor("Default"), asset, anim, base, 0, 0,
                (m, p) -> { if (p.name().equals("fist")) m.transformPosition(new Vector3f(.036f, -.566f, -.008f), result); });
        return result;
    }

    @Test
    void exportedArmHasClosedFingerGeometryAndCompatibleClips() {
        var asset = SbeEntityLoader.load(FirstPersonArmPose.RESOURCE);
        var geometry = asset.geometryFor("Default");
        var names = geometry.parts().stream().map(SbePart::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(List.of("upper_arm", "forearm", "wrist_cuff", "wrist_guard", "fist")));
        assertEquals(5, names.size());
        var ids = geometry.parts().stream().map(SbePart::id).collect(Collectors.toSet());
        for (var clip : asset.clips().values()) {
            clip.tracks().forEach(track -> assertTrue(ids.contains(track.partId())));
        }
        assertTrue(asset.clipFor("idle").loop());
        assertFalse(asset.clipFor("attacking").loop());
        assertEquals(SbeEntityLoader.load("/sbe/Mobs/SB_Player.sbe").clipFor("attacking").duration(),
                asset.clipFor("attacking").duration(), 1e-6f, "first/body punches share a clock");
        assertTrue(geometry.indices().length / 3 < 20000, "arm should not include the whole player mesh");
        assertEquals(5, geometry.materials().size());

    }

    @Test
    void idleLoopsAndPunchReturnsToItsIdlePoseWithoutScalingTheFist() {
        var asset = SbeEntityLoader.load(FirstPersonArmPose.RESOURCE);
        Map<String, Matrix4f> start = matrices(asset, "idle", 0);
        for (var end : List.of(matrices(asset, "idle", 3.2f), matrices(asset, "attacking", .78f))) {
            start.forEach((name, matrix) -> assertTrue(matrix.equals(end.get(name), 1e-5f), name));
        }
        for (var clip : asset.clips().values()) for (var track : clip.tracks()) for (var key : track.keyframes()) {
            assertEquals(new Vector3f(1), key.scale());
        }
    }

    private static Map<String, Matrix4f> matrices(SbeEntityAsset asset, String clip, float time) {
        Map<String, Matrix4f> result = new HashMap<>();
        SbePoseSolver.forEachPartMatrix(asset.geometryFor("Default"), asset, AnimState.single(clip, time),
                new Matrix4f(), 0, 0, (m, p) -> result.put(p.name(), new Matrix4f(m)));
        return result;
    }
}
