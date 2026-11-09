package com.stonebreak.openmason.fx;

import com.openmason.api.fx.FxCatalogSource;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.openmason.StonebreakGameTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stonebreak's shipped FX reach the SBO/SBE editors' FX bindings check by their object ids. */
class SbfxCatalogTest {

    @TempDir
    Path checkout;

    @Test
    void aCheckoutsSbfxFolderIsListedByObjectIdWithParameters() throws IOException {
        Path folder = Files.createDirectories(checkout.resolve(SbfxExport.FOLDER));
        FxSystem glow = FxSystem.of(ParticleLibrary.Presets.FIRE, 1).toBuilder().name("lantern_glow")
                .parameter(FxParameter.scalar("pulse", 0.5f))
                .parameter(FxParameter.color("tint", 0xFFFFFFFF))
                .build();
        SbfxCodec.write(SbfxDocument.of(OmfxDocument.of(glow)), folder.resolve("SB_Lantern_Glow.sbfx"));
        Files.writeString(folder.resolve("SB_Broken.sbfx"), "not an archive");
        Files.writeString(folder.resolve("notes.txt"), "ignored");

        List<FxCatalogSource.Entry> entries = SbfxCatalog.list(checkout);
        assertEquals(1, entries.size(), "the unreadable file is skipped, other files ignored: " + entries);
        FxCatalogSource.Entry entry = entries.get(0);
        assertEquals("stonebreak:lantern_glow", entry.id());
        assertEquals("sbfx/SB_Lantern_Glow.sbfx", entry.origin());
        assertEquals(List.of("pulse", "tint"), entry.parameters().stream().map(FxParameter::name).toList());
    }

    @Test
    void withoutACheckoutTheBundledFxAreListed() throws IOException {
        List<FxCatalogSource.Entry> entries = SbfxCatalog.list(null);
        FxCatalogSource.Entry fire = entries.stream().filter(e -> e.id().equals("stonebreak:torch_fire"))
                .findFirst().orElseThrow(() -> new AssertionError("torch_fire not bundled: " + entries));
        assertTrue(fire.origin().startsWith("bundled sbfx/"), fire.origin());
        assertTrue(fire.parameters().stream().anyMatch(p -> p.name().equals("pulse")));
    }

    @Test
    void anEmptyCheckoutListsNothingAndTheSourceBelongsToTheStonebreakTarget() throws IOException {
        assertEquals(List.of(), SbfxCatalog.list(checkout));
        FxCatalogSource source = SbfxCatalog.source(() -> checkout);
        assertEquals(SbfxCatalog.ID, source.id());
        assertEquals(StonebreakGameTarget.ID, source.gameTargetId());
        assertTrue(source.appliesTo(StonebreakGameTarget.ID));
        assertTrue(!source.appliesTo("another-game"));
        assertEquals(List.of(), source.lister().list());
    }
}
