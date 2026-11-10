package com.stonebreak.fx.content;

import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Writes {@code sbfx/SB_Precipitation.sbfx} from {@link PrecipitationFxContent} (and its editable {@code .omfx}
 * source into {@code Dev Working/fx-sources/}). Opt-in and separate from {@link FirstFxContentGeneratorTest} so it
 * never rewrites the other shipped FX, some of which were edited in Open Mason since.
 *
 * <pre>mvn -q test -pl stonebreak-game -Dtest=PrecipitationFxContentGeneratorTest -Dstonebreak.fx.regenerate=true</pre>
 */
class PrecipitationFxContentGeneratorTest {

    @Test
    @EnabledIfSystemProperty(named = "stonebreak.fx.regenerate", matches = "true")
    void regenerateThePrecipitationFx() throws Exception {
        Path shipped = Path.of("src/main/resources/sbfx");
        Path sources = Path.of("../Dev Working/fx-sources");
        Files.createDirectories(shipped);
        Files.createDirectories(sources);
        SbfxDocument doc = PrecipitationFxContent.document();
        SbfxCodec.write(doc, shipped.resolve(PrecipitationFxContent.FILE));
        OmfxCodec.write(doc.fx(), sources.resolve(doc.fxId() + OmfxCodec.FILE_EXTENSION));
        assertEquals(doc, SbfxCodec.read(shipped.resolve(PrecipitationFxContent.FILE)));
    }
}
