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
 *
 * <p>The same event triggers the subject's FX bindings (Stonebreak#437): another player's break throws
 * the block's debris here, a mob's death plays its death FX — no packet of their own.
 */
public final class ClientSoundHandler {

    private ClientSoundHandler() {
    }

    public static void apply(SoundEventS2C s) {
        if (com.stonebreak.network.MultiplayerSession.isMenuBackdrop()) {
            return; // the main menu's live backdrop is silent: the menu music is its only sound
        }
        switch (s.subject()) {
            case SoundEventS2C.BLOCK -> {
                BlockType block = BlockType.getById(s.subjectId());
                if (block != null) {
                    BlockSounds.playEventAt(block, s.event(), s.x(), s.y(), s.z(), 1f);
                    com.stonebreak.fx.bindings.BlockFx.play(block, s.event(), s.x(), s.y(), s.z(),
                            com.stonebreak.fx.bindings.AssetFx.NORMAL);
                }
            }
            case SoundEventS2C.ENTITY -> {
                EntityType[] types = EntityType.values();
                if (s.subjectId() >= 0 && s.subjectId() < types.length) {
                    EntitySounds.playAt(types[s.subjectId()], s.event(), s.x(), s.y(), s.z());
                    com.stonebreak.fx.bindings.EntityFx.playReplicated(types[s.subjectId()], s.event(),
                            s.x(), s.y(), s.z());
                }
            }
            default -> { }
        }
    }
}
