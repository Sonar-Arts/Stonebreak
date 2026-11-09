package com.stonebreak.fx.content;

import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Writes the first shipped FX ({@link FirstFxContent}) into {@code src/main/resources/sbfx/}, plus their editable
 * {@code .omfx} sources into {@code Dev Working/fx-sources/} (local, for the Open Mason Particles tab). Opt-in, a
 * one-time bootstrap: once shipped, the files are edited in Open Mason and re-exported as Stonebreak FX.
 *
 * <pre>mvn -q test -pl stonebreak-game -Dtest=FirstFxContentGeneratorTest -Dstonebreak.fx.regenerate=true</pre>
 */
class FirstFxContentGeneratorTest {

    @Test
    @EnabledIfSystemProperty(named = "stonebreak.fx.regenerate", matches = "true")
    void regenerateTheShippedFx() throws Exception {
        Path shipped = Path.of("src/main/resources/sbfx");
        Path sources = Path.of("../Dev Working/fx-sources");
        Files.createDirectories(shipped);
        Files.createDirectories(sources);
        for (Map.Entry<String, SbfxDocument> e : FirstFxContent.all().entrySet()) {
            SbfxDocument doc = e.getValue();
            SbfxCodec.write(doc, shipped.resolve(e.getKey()));
            OmfxCodec.write(doc.fx(), sources.resolve(doc.fxId() + OmfxCodec.FILE_EXTENSION));
            // What was written reads back as the same document.
            assertEquals(doc, SbfxCodec.read(shipped.resolve(e.getKey())));
        }
    }
}
