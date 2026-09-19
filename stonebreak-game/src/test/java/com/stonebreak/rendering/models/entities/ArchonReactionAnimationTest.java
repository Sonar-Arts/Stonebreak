package com.stonebreak.rendering.models.entities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.mobs.sbe.SbePart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Authored reaction assets exercised through Stonebreak's real pose sampler. */
class ArchonReactionAnimationTest {
    private static final SbeEntityAsset ASSET = SbeEntityLoader.load("/sbe/Mobs/SB_Ice_Archon.sbe");

    private static JsonNode contract() throws IOException {
        try (var in = ArchonReactionAnimationTest.class.getResourceAsStream("/sbe/Mobs/ice-archon-combat/clips.json")) {
            assertNotNull(in);
            return new ObjectMapper().readTree(in);
        }
    }

    private static Map<String, Matrix4f> pose(String state, float time) {
        Map<String, Matrix4f> result = new HashMap<>();
        SbePoseSolver.forEachPartMatrix(ASSET.geometryFor("Default"), ASSET, AnimState.single(state, time),
                new Matrix4f(), 0f, 0f, (matrix, part) -> result.put(part.name(), new Matrix4f(matrix)));
        return result;
    }

    @Test
    void everyReactionBindsAndMatchesItsTimingContract() throws IOException {
        assertEquals("stonebreak:ice_archon", ASSET.objectId());
        var ids = ASSET.geometryFor("Default").parts().stream().map(SbePart::id).collect(Collectors.toSet());
        for (var spec : contract().get("clips")) {
            String name = spec.get("state").asText();
            var clip = ASSET.clipFor(name);
            assertNotNull(clip, name);
            assertEquals(spec.get("durationSeconds").floatValue(), clip.duration(), 1e-6f);
            assertEquals(spec.get("loop").asBoolean(), clip.loop());
            assertEquals("BASE", clip.layer().type().name());
            assertEquals(16, clip.tracks().size());
            for (var track : clip.tracks()) {
                assertTrue(ids.contains(track.partId()), track.partName());
                assertEquals(0f, track.keyframes().getFirst().time(), 1e-6f);
                assertEquals(clip.duration(), track.keyframes().getLast().time(), 1e-6f);
                for (var key : track.keyframes()) {
                    assertTrue(key.position().isFinite() && key.rotation().isFinite() && key.scale().isFinite());
                    assertEquals(new Vector3f(1), key.scale());
                }
            }
        }
        assertEquals(1.65f, ASSET.clipFor("attack_slash").duration(), 1e-6f);
        assertEquals(2.05f, ASSET.clipFor("attack_overhead").duration(), 1e-6f);
        assertEquals(2.4f, ASSET.clipFor("attack_frost_cast").duration(), 1e-6f);
    }

    @Test
    void reactionsJoinTheirHoldPosesAndLegacyAttackStance() throws IOException {
        for (var spec : contract().get("clips")) {
            String name = spec.get("state").asText();
            for (boolean start : new boolean[]{true, false}) {
                var actual = pose(name, start ? 0f : spec.get("durationSeconds").floatValue());
                var expected = pose(spec.get(start ? "startPose" : "endPose").asText(), 0f);
                for (String part : actual.keySet()) {
                    assertTrue(actual.get(part).equals(expected.get(part), 2e-4f), name + ": " + part);
                }
            }
        }
        var ready = pose("combat_idle", 0f);
        for (String attack : new String[]{"attack_slash", "attack_overhead", "attack_frost_cast"}) {
            var end = pose(attack, ASSET.clipFor(attack).duration());
            for (String part : ready.keySet()) assertTrue(ready.get(part).equals(end.get(part), 2e-4f), attack + ": " + part);
        }
    }

    @Test
    void collapseStaysAboveFloorAndSwordStaysInHand() {
        var mesh = ASSET.geometryFor("Default");
        var point = new Vector3f();
        for (int frame = 0; frame <= 40; frame++) {
            var matrices = pose("death", ASSET.clipFor("death").duration() * frame / 40f);
            assertTrue(matrices.get("frostblade").equals(matrices.get("hand_right"), 1e-5f), "sword slipped");
            float lowest = Float.POSITIVE_INFINITY;
            for (var part : mesh.parts()) {
                var matrix = matrices.get(part.name());
                for (var face : part.faces()) {
                    for (int i = face.indexStart(); i < face.indexStart() + face.indexCount(); i++) {
                        int v = mesh.indices()[i] * 3;
                        matrix.transformPosition(point.set(mesh.vertices()[v], mesh.vertices()[v + 1], mesh.vertices()[v + 2]));
                        lowest = Math.min(lowest, point.y);
                    }
                }
            }
            assertTrue(lowest >= -0.001f, "collapse penetrated the floor: " + lowest);
        }
    }
}
