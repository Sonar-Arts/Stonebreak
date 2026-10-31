package com.stonebreak.mobs.vestige;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.openmason.engine.format.sound.SoundDef;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.sbe.GameSbeAssets;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Vestige's voice lives in its asset: Peek-a-boo's laugh and the Woosh are embedded samples bound
 * to the events the behaviours emit. An unbound event is silently silent, and a stereo sample plays
 * flat in both ears instead of from the tree it is hiding behind — both are pinned here.
 */
class VestigeAssetContractTest {

    private final SbeEntityAsset vestige = GameSbeAssets.load("/sbe/Mobs/SB_Vestige.sbe");

    @Test
    void theAssetIsTheVestige() {
        assertEquals(EntityType.VESTIGE.getSbeObjectId(), vestige.objectId());
    }

    @Test
    void itLaughsAndWooshesFromWhereItIs() throws Exception {
        for (String event : new String[] {"laugh", "woosh", "death"}) {
            SoundDef def = vestige.sounds().sounds().stream()
                    .filter(d -> d.event().equals(event)).findFirst().orElse(null);
            assertNotNull(def, "SB_Vestige.sbe binds no '" + event + "' sound");
            byte[] bytes = vestige.soundBytesFor(def.filename());
            assertNotNull(bytes, event + " is not embedded");
            AudioFormat format = AudioSystem.getAudioFileFormat(new ByteArrayInputStream(bytes)).getFormat();
            assertEquals(1, format.getChannels(), event + " must be mono to play positionally");
            assertTrue(format.getSampleSizeInBits() == 16, event + " is 16-bit PCM");
        }
    }
}
