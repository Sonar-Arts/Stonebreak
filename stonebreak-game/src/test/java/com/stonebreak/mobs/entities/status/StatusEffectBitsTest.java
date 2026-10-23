package com.stonebreak.mobs.entities.status;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status-effect wire mapping (#339/#371): ids are stable and unique, and every effect fits
 * the replicated bitset's field. Adding a 17th effect type fails here first — the field (and
 * the protocol version) has to grow with it.
 */
class StatusEffectBitsTest {

    @Test
    void everyEffectFitsTheReplicatedBitset() {
        assertTrue(StatusEffectType.values().length <= StatusEffectBits.WIRE_BITS,
                "StatusEffectType has " + StatusEffectType.values().length + " values; the wire bitset holds "
                        + StatusEffectBits.WIRE_BITS + " — widen EntityStatusS2C.statusBits and bump the protocol");
        int all = 0;
        for (StatusEffectType type : StatusEffectType.values()) {
            all |= StatusEffectBits.bit(type);
        }
        assertEquals(all, all & 0xFFFF, "all bits survive the unsigned 16-bit field");
    }

    @Test
    void wireIdsRoundTripAndAreUnique() {
        int seen = 0;
        for (StatusEffectType type : StatusEffectType.values()) {
            int id = StatusEffectBits.wireId(type);
            assertSame(type, StatusEffectBits.fromWireId(id));
            assertEquals(0, seen & StatusEffectBits.bit(type), "two effects share a bit");
            seen |= StatusEffectBits.bit(type);
        }
        assertNull(StatusEffectBits.fromWireId(-1));
        assertNull(StatusEffectBits.fromWireId(StatusEffectType.values().length));
    }

    @Test
    void unknownBitsAreDropped() {
        int bits = StatusEffectBits.sanitize(0xFFFF);
        for (StatusEffectType type : StatusEffectType.values()) {
            assertTrue(StatusEffectBits.has(bits, type));
        }
        assertEquals(0, bits >>> StatusEffectType.values().length, "no bit past the last effect survives");
        assertFalse(StatusEffectBits.has(0, StatusEffectType.STUNNED));
    }
}
