package com.stonebreak.network.packet.entity;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.sbe.MobStateMapping;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #346: mob animation states cross the wire as a one-byte code. A clip name added to
 * {@link MobStateMapping} without a code would silently fall back to the string form.
 */
class EntityAnimStateCodesTest {

    @Test
    void everyMobClipNameHasACode() {
        for (EntityType type : EntityType.values()) {
            for (MobBehaviorState state : MobBehaviorState.values()) {
                String clip = MobStateMapping.sbeState(type, state);
                assertTrue(EntityAnimS2C.knownIndex(clip) >= 0,
                    type + "/" + state + " → \"" + clip + "\" has no code in EntityAnimS2C.KNOWN_STATES");
            }
        }
    }

    @Test
    void codesAreUniqueAndBelowTheStringTag() {
        Set<String> seen = new HashSet<>();
        for (String s : EntityAnimS2C.KNOWN_STATES) {
            assertTrue(seen.add(s), "duplicate code for " + s);
        }
        assertTrue(EntityAnimS2C.KNOWN_STATES.length < EntityAnimS2C.STRING_TAG);
    }

    @Test
    void aKnownStateIsOneByteOnTheWire() {
        ByteBuf buf = Unpooled.buffer();
        EntityAnimS2C.CODEC.encode(buf, new EntityAnimS2C(42, "Wingflap"));
        assertEquals(Integer.BYTES + 1, buf.readableBytes());
        assertEquals(new EntityAnimS2C(42, "Wingflap"), EntityAnimS2C.CODEC.decode(buf));
        buf.release();
    }
}
