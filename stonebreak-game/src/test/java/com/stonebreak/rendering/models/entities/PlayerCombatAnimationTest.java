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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Shipped Monk assets exercised through the actual runtime hierarchy/sampler. */
class PlayerCombatAnimationTest {
    private static final SbeEntityAsset ASSET = SbeEntityLoader.load("/sbe/Mobs/SB_Player.sbe");

    private static JsonNode contract() throws IOException {
        try (var in = PlayerCombatAnimationTest.class.getResourceAsStream("/sbe/Mobs/player-combat/clips.json")) {
            assertNotNull(in);
            return new ObjectMapper().readTree(in);
        }
    }

    private static Map<String, Matrix4f> pose(String state, float time) {
        Map<String, Matrix4f> matrices = new HashMap<>();
        SbePoseSolver.forEachPartMatrix(ASSET.geometryFor("Default"), ASSET,
                state == null ? null : AnimState.single(state, time), new Matrix4f(), 0f, 0f,
                (matrix, part) -> matrices.put(part.name(), new Matrix4f(matrix)));
        return matrices;
    }

    @Test
    void timingContractMatchesEmbeddedClipsAndEveryTrackBinds() throws IOException {
        assertEquals("stonebreak:Player", ASSET.objectId());
        var ids = ASSET.geometryFor("Default").parts().stream().map(SbePart::id).collect(Collectors.toSet());
        for (var spec : contract().get("clips")) {
            String state = spec.get("state").asText();
            var clip = ASSET.clipFor(state);
            assertNotNull(clip, state);
            assertEquals(spec.get("durationSeconds").floatValue(), clip.duration(), 1e-5f, state);
            assertEquals(spec.get("loop").asBoolean(), clip.loop(), state);
            assertEquals("BASE", clip.layer().type().name(), state);
            assertEquals(33, clip.tracks().size(), state);
            for (var track : clip.tracks()) {
                assertTrue(ids.contains(track.partId()), state + ": " + track.partName());
                assertFalse(track.keyframes().isEmpty());
                assertEquals(0f, track.keyframes().getFirst().time(), 1e-6f);
                assertEquals(clip.duration(), track.keyframes().getLast().time(), 1e-5f);
                for (var key : track.keyframes()) {
                    assertTrue(key.position().isFinite() && key.rotation().isFinite() && key.scale().isFinite());
                    assertEquals(new Vector3f(1), key.scale(), "combat poses must not shrink the hands");
                }
                for (var cue : spec.get("cues")) {
                    float time = cue.get("timeSeconds").floatValue();
                    assertTrue(track.keyframes().stream().anyMatch(k -> Math.abs(k.time() - time) < 1e-5f),
                            state + " missing exact contact key " + time);
                }
            }
        }
    }

    @Test
    void actionsAndTerminalLoopsJoinTheirDocumentedPoses() throws IOException {
        for (var spec : contract().get("clips")) {
            String state = spec.get("state").asText();
            for (boolean start : List.of(true, false)) {
                String target = spec.get(start ? "startPose" : "endPose").asText();
                var actual = pose(state, start ? 0f : spec.get("durationSeconds").floatValue());
                var expected = pose(target.equals("rest") ? null : target, 0f);
                for (String part : actual.keySet()) {
                    assertTrue(actual.get(part).equals(expected.get(part), 2e-4f),
                            state + (start ? " start " : " end ") + part + " != " + target);
                }
            }
        }
    }

    @Test
    void supportingFeetRemainPlantedDuringAttacksAndIdle() {
        var feet = Map.of("foot_left", new Vector3f(.083f, .135f, .008f),
                "foot_right", new Vector3f(-.083f, .135f, .008f));
        for (String state : List.of("combat_idle", "strike", "flurry", "stunning_strike", "guard", "parry", "hurt", "kick")) {
            var initial = pose(state, 0f);
            for (int i = 0; i <= 36; i++) {
                var current = pose(state, ASSET.clipFor(state).duration() * i / 36f);
                for (var foot : feet.entrySet()) {
                    if (state.equals("kick") && foot.getKey().equals("foot_right")) continue;
                    var before = initial.get(foot.getKey()).transformPosition(new Vector3f(foot.getValue()));
                    var after = current.get(foot.getKey()).transformPosition(new Vector3f(foot.getValue()));
                    assertTrue(before.distance(after) < .001f, state + " slid support foot: " + after);
                }
            }
        }
    }

    @Test
    void strikesReachForwardAndFingerSegmentsFollowTheirHand() {
        var hit = pose("strike", .38f);
        var fist = hit.get("hand_left").transformPosition(new Vector3f(.221f, .845f, -.012f));
        assertTrue(fist.z < -.45f && fist.y > 1.2f && fist.y < 1.65f, "strike contact: " + fist);
        var parts = ASSET.geometryFor("Default").parts().stream().collect(Collectors.toMap(SbePart::name, p -> p));
        for (String side : List.of("left", "right")) {
            for (String finger : List.of("index", "middle", "ring", "little")) {
                var proximal = parts.get("finger_" + finger + "_" + side);
                var distal = parts.get("finger_" + finger + "_tip_" + side);
                assertEquals(parts.get("hand_" + side).id(), proximal.parentId());
                assertEquals(proximal.id(), distal.parentId());
            }
        }
    }
}
