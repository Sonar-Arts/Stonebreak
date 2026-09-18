package com.stonebreak.mobs.sbe;

import org.junit.jupiter.api.Test;
import java.util.stream.Collectors;

import static com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.*;
import static com.stonebreak.mobs.sbe.PlayerStateMapping.locomotion;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStateMappingTest {
    @Test
    void sprintRequiresGroundMovementAndStaysSeparateFromSwimming() {
        assertEquals(SPRINTING, locomotion(true, true, false, true));
        assertEquals(WALKING, locomotion(true, true, false, false));
        assertEquals(IDLE, locomotion(false, true, false, true));
        assertEquals(JUMPING, locomotion(true, false, false, true));
        assertEquals(WALKING, locomotion(true, true, true, true));
        assertEquals(WALKING, locomotion(true, false, true, true));
    }

    @Test
    void publishedPlayerHasCompatibleClipsForEveryState() {
        var asset = SbeEntityLoader.load("/sbe/Mobs/SB_Player.sbe");
        var partIds = asset.geometryFor("Default").parts().stream()
                .map(SbePart::id).collect(Collectors.toSet());
        for (var state : PlayerStateMapping.PlayerMovementState.values()) {
            var clip = asset.clipFor(PlayerStateMapping.sbeState(state));
            assertNotNull(clip, state.name());
            assertFalse(clip.tracks().isEmpty(), state.name());
            clip.tracks().forEach(track -> assertTrue(partIds.contains(track.partId()),
                    state + " references absent part " + track.partName()));
        }
        var sprint = asset.clipFor("sprinting");
        assertTrue(sprint.loop());
        assertEquals(0.68f, sprint.duration(), 1e-6f);
        assertEquals(0.96f, asset.clipFor("walking").duration(), 1e-6f);
        assertNotEquals(asset.clipFor("walking").tracks(), sprint.tracks());
        assertEquals("BASE", sprint.layer().type().toString());
        assertEquals("OVERLAY", asset.clipFor("attacking").layer().type().toString());
    }
}
