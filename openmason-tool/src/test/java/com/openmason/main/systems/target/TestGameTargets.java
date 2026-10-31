package com.openmason.main.systems.target;

import com.openmason.api.target.AssetFolders;
import com.openmason.api.target.GameTarget;
import com.openmason.api.target.ResourceRoot;
import com.openmason.api.target.UiDeployLayout;
import com.openmason.api.target.UiHostSource;
import com.openmason.api.target.WriteRules;
import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.main.systems.io.BuiltinFileTypes;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Game targets for tests (#380, #382). The tool no longer sees the Stonebreak plugin, so
 * {@link #stonebreak()} is a file-only stand-in with Stonebreak's layout: same id, namespace,
 * folders, prefix and deploy layout, its tree at this checkout's {@code stonebreak-game} resources,
 * and an empty synthetic UI host (no game contracts). The real target is tested in
 * {@code openmason-plugin-stonebreak}.
 */
public final class TestGameTargets {

    /** The game's resource tree, relative to the repository root. */
    public static final String SOURCE_TREE = "stonebreak-game/src/main/resources";

    private TestGameTargets() {
    }

    /** The Stonebreak layout over this checkout's game resources (files only). */
    public static GameTarget stonebreak() {
        String sbo = BuiltinFileTypes.SBO.id();
        String sbe = BuiltinFileTypes.SBE.id();
        return GameTarget.builder("stonebreak", "Stonebreak")
                .namespace("stonebreak")
                .resources(new ResourceRoot(List.of(SOURCE_TREE), null, null))
                .folders(AssetFolders.builder()
                        .base(sbo, "sbo")
                        .subtype(sbo, "block", "sbo/blocks")
                        .subtype(sbo, "item", "sbo/items")
                        .subtype(sbo, "model", "sbo/models")
                        .base(sbe, "sbe")
                        .subtype(sbe, "mob", "sbe/Mobs")
                        .subtype(sbe, "clothing", "sbe/Clothing")
                        .subtype(sbe, "playercustomize", "sbe/PlayerCustomize")
                        .subtype(sbe, "particle", "sbe/Particles")
                        .build())
                .assetFilePrefix("SB_")
                .writes(WriteRules.SHIPPED_ASSETS)
                .uiDeploy(new UiDeployLayout("ui/documents", "ui/shared"))
                .uiHost(new EmptyUiHost())
                .legacyDefault(true)
                .build();
    }

    /** {@link #stonebreak()} with its tree at {@code tree}. */
    public static GameTarget stonebreakAt(Path tree) {
        return stonebreak().withResources(ResourceRoot.at(tree));
    }

    /** This checkout's game resource tree (absolute), or null when it cannot be found. */
    public static Path checkoutTree() {
        return stonebreak().resources().locate();
    }

    /** Makes {@code target} the shared registry's only target, active with no project open. */
    public static void installShared(GameTarget target) {
        GameTargets.shared().setSource(() -> List.of(target));
        GameTargets.shared().projectClosed();
    }

    /** {@link #installShared} with {@link #stonebreak()}. */
    public static void installStonebreak() {
        installShared(stonebreak());
    }

    /** Empties the shared registry again (no target). */
    public static void clearShared() {
        GameTargets.shared().setSource(List::of);
        GameTargets.shared().projectClosed();
    }

    /** A UI host that declares nothing: every data root, action and contract is unknown to it. */
    static final class EmptyUiHost implements UiHostSource {

        private final UiHost host = new UiHost();

        @Override
        public UiHostProfile profile() {
            return host.profile();
        }

        @Override
        public UiHost declaration() {
            return host;
        }

        @Override
        public byte[] fixtureJson() {
            return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String fixtureName() {
            return "test-host.fixture.json";
        }

        @Override
        public List<AssetSource> packagedAssets() {
            return List.of();
        }

        @Override
        public Typeface loadTypeface() throws IOException {
            return GameUiFixtures.loadTypeface();
        }
    }
}
