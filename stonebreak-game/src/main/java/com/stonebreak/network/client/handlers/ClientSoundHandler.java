package com.stonebreak.network.client.handlers;

import com.stonebreak.audio.BlockSounds;
import com.stonebreak.audio.EntitySounds;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.packet.world.SoundEventS2C;

/**
 * Client-side: plays a replicated sound event (issue #344) from this client's own copy of the
 * subject's asset. The packet carries the subject's id rather than a live entity, so a mob's death
 * voice still plays after the despawn that follows it. Unknown subjects and ids are ignored.
 */
public final class ClientSoundHandler {

    private ClientSoundHandler() {
    }

    public static void apply(SoundEventS2C s) {
        switch (s.subject()) {
            case SoundEventS2C.BLOCK -> {
                BlockType block = BlockType.getById(s.subjectId());
                if (block != null) {
                    BlockSounds.playEventAt(block, s.event(), s.x(), s.y(), s.z(), 1f);
                }
            }
            case SoundEventS2C.ENTITY -> {
                EntityType[] types = EntityType.values();
                if (s.subjectId() >= 0 && s.subjectId() < types.length) {
                    EntitySounds.playAt(types[s.subjectId()], s.event(), s.x(), s.y(), s.z());
                }
            }
            default -> { }
        }
    }
}
