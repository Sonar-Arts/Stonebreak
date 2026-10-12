package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.ui.assets.AssetOrigin;
import com.openmason.engine.ui.assets.HostCompatibility;
import com.openmason.engine.ui.assets.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A deployed game resolves SBUI shared dependencies with no Open Mason workspace (#285). */
class GameUiAssetsTest {

    private static final String PACKAGED_TEX = "stonebreak:ui-test/textures/panel";
    private static final String PACK_SCRIPT = "stonebreak:ui-test/scripts/menu";
    private static final String PACK = "stonebreak:ui-test-pack";
    private static final UiBytes SCRIPT = UiBytes.utf8("return {}\n");

    @TempDir
    Path tmp;

    @Test
    void sharedRowsResolveFromThePackagedRootAndDeclaredPacks() throws Exception {
        SbuiArchive sbui = reread(export());
        Path pack = tmp.resolve("pack");
        Files.createDirectories(pack.resolve("stonebreak/ui-test/scripts"));
        Files.write(pack.resolve("stonebreak/ui-test/scripts/menu.lua"), SCRIPT.toArray());

        Resolution r = GameUiAssets.resolver(sbui, Map.of(PACK, pack)).resolveAll();

        assertTrue(r.complete(), r.diagnostics()::toString);
        assertEquals(AssetOrigin.PACKAGED, r.get(PACKAGED_TEX).origin());
        assertEquals(packagedBytes(), r.get(PACKAGED_TEX).bytes());
        assertEquals(AssetOrigin.PACK, r.get(PACK_SCRIPT).origin());
    }

    @Test
    void missingPackOrHostContractMakesTheScreenIncompatible() throws Exception {
        SbuiArchive sbui = export();
        UiHostProfile game = new UiHostProfile(1, Set.of("flex-1"), Map.of("stonebreak:screen.pause", 1), Map.of());
        UiHostProfile older = new UiHostProfile(1, Set.of("flex-1"), Map.of(), Map.of());

        HostCompatibility noPack = GameUiAssets.check(sbui, game, Map.of());
        assertFalse(noPack.runnable());
        assertTrue(noPack.assets().stream().anyMatch(d -> d.isError() && d.message().contains("not mounted")));

        Path pack = tmp.resolve("pack");
        Files.createDirectories(pack.resolve("stonebreak/ui-test/scripts"));
        Files.write(pack.resolve("stonebreak/ui-test/scripts/menu.lua"), SCRIPT.toArray());
        assertTrue(GameUiAssets.check(sbui, game, Map.of(PACK, pack)).runnable());
        assertFalse(GameUiAssets.check(sbui, older, Map.of(PACK, pack)).runnable(), "host lacks screen.pause");
    }

    private SbuiArchive export() throws Exception {
        UiBytes tex = packagedBytes();
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(),
                Map.of("background-image", UiValue.of(PACKAGED_TEX)), null, List.of(), null, List.of(), Map.of());
        UiManifest manifest = new UiManifest(com.openmason.engine.format.omui.OmuiFormat.SCHEMA_VERSION,
                "stonebreak:ui-test/menu", UiManifest.DocumentKind.SCREEN, "", 1, "flex-1", List.of(),
                List.of(new HostRequirement("stonebreak:screen.pause", 1)), List.of(), Map.of());
        OmuiArchive doc = OmuiArchive.of(manifest, new UiDocument(root, List.of(), PACK_SCRIPT, null, Map.of()))
                .withDependencies(new UiDependencies(List.of(
                        UiDependency.shared(PACKAGED_TEX, UiDependency.Kind.TEXTURE, tex.sha256(), tex.size(), "t.sbt"),
                        UiDependency.shared(PACK_SCRIPT, UiDependency.Kind.SCRIPT, SCRIPT.sha256(), SCRIPT.size(),
                                "menu.lua")), Map.of()));
        return SbuiExporter.export(doc, new SbuiExporter.Options(null, Map.of(), false, Map.of(PACK_SCRIPT, PACK),
                List.of())).archive();
    }

    private static SbuiArchive reread(SbuiArchive sbui) throws Exception {
        return SbuiReader.read(SbuiWriter.write(sbui), SbuiReader.Options.RUNTIME).archive();
    }

    private static UiBytes packagedBytes() throws Exception {
        try (InputStream in = GameUiAssets.class.getResourceAsStream(
                "/" + GameUiAssets.RESOURCE_ROOT + "stonebreak/ui-test/textures/panel.sbt")) {
            assertNotNull(in, "test resource on the game classpath");
            return UiBytes.copyOf(in.readAllBytes());
        }
    }
}
