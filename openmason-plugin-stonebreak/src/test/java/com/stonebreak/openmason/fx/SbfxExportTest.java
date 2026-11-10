package com.stonebreak.openmason.fx;

import com.openmason.api.fx.FxExportInput;
import com.openmason.api.fx.FxExportTarget;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.openmason.StonebreakGameTarget;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SBFX export target (Stonebreak#435): deploys into the checkout, writes what the game reads. */
class SbfxExportTest {

    private static final ParticleEffect SPARK = ParticleEffect.builder("spark").rate(0).texture("glint").build();

    @Test
    void theTargetDeploysIntoTheStonebreakCheckout() {
        FxExportTarget target = SbfxExport.target();
        assertEquals("stonebreak.sbfx", target.id());
        assertEquals(StonebreakGameTarget.ID, target.gameTargetId());
        assertEquals(".sbfx", target.fileType().extension());
        assertTrue(target.deploys(), "a target-root save rule deploys like the SBO/SBE editors");
        assertEquals("sbfx", StonebreakGameTarget.create().folders().folderFor(SbfxExport.FILE_TYPE_ID, null));
    }

    @Test
    void theExportIsTheGamesSbfxWithItsSpritesInside() throws IOException {
        OmfxDocument doc = OmfxDocument.of(FxSystem.of(SPARK, 8)).withAuthor("tester")
                .withSprite(new OmfxSprite("glint", png()));
        FxExportInput input = FxExportInput.prepare(doc);

        assertEquals(List.of(), SbfxExport.target().exporter().problems(input));
        SbfxDocument shipped = SbfxCodec.read(SbfxExport.target().exporter().export(input));

        assertEquals("stonebreak:spark", shipped.objectId());
        assertEquals(doc, shipped.fx());
    }

    @Test
    void aSystemUsingSpritesItDoesNotEmbedIsRefused() throws IOException {
        FxExportInput input = FxExportInput.prepare(OmfxDocument.of(FxSystem.of(SPARK, 8)));
        List<String> problems = SbfxExport.target().exporter().problems(input);
        assertFalse(problems.isEmpty());
        assertTrue(problems.get(0).contains("glint"), problems.toString());
        assertThrows(IOException.class, () -> SbfxExport.target().exporter().export(input));
    }

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, 0xFFFFFFFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
