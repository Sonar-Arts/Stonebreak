package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.HostCompatibility;
import com.openmason.engine.ui.assets.MountedAssetSource;
import com.openmason.engine.ui.assets.ResourceOpener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where a deployed Stonebreak finds the shared resources SBUI exports depend on. No Open Mason
 * workspace is involved: an SBUI's own resolution table says whether each dependency is in the
 * export, in its embedded source, in the game's packaged resource root, or in a named pack.
 *
 * <ul>
 *   <li><b>Packaged root</b>: {@value #RESOURCE_ROOT} on the game classpath, laid out
 *       {@code <namespace>/<path><ext>} ({@code stonebreak:ui/textures/panel} →
 *       {@code ui/shared/stonebreak/ui/textures/panel.sbt}). Serves shared rows without a pack.</li>
 *   <li><b>Packs</b>: declared by id, each a directory or ZIP with the same layout. Serves only
 *       rows naming that pack.</li>
 * </ul>
 */
public final class GameUiAssets {

    /** Classpath folder of shared UI resources shipped with the game. */
    public static final String RESOURCE_ROOT = "ui/shared/";

    private GameUiAssets() {
    }

    /** The packaged resource root, read through this module (JPMS resource encapsulation). */
    public static MountedAssetSource packaged() {
        return MountedAssetSource.packaged(RESOURCE_ROOT,
                ResourceOpener.streams(path -> GameUiAssets.class.getResourceAsStream("/" + path)));
    }

    /**
     * Packaged root plus the declared packs, in deterministic (pack id) order.
     *
     * @param packs pack id → directory or {@code .zip}
     */
    public static List<AssetSource> sources(Map<String, Path> packs) throws IOException {
        List<AssetSource> out = new ArrayList<>();
        out.add(packaged());
        for (Map.Entry<String, Path> pack : new TreeMap<>(packs).entrySet()) {
            Path p = pack.getValue();
            ResourceOpener opener = Files.isDirectory(p) ? ResourceOpener.directory(p) : ResourceOpener.zip(p);
            out.add(MountedAssetSource.pack(pack.getKey(), opener));
        }
        return out;
    }

    public static AssetResolver resolver(SbuiArchive sbui, Map<String, Path> packs) throws IOException {
        return AssetResolver.forExport(sbui, sources(packs));
    }

    /**
     * Whether this game can run {@code sbui}: every required host contract/provider is offered
     * by {@code host} and every required dependency resolves. Callers refuse the screen and
     * show the diagnostics otherwise.
     */
    public static HostCompatibility check(SbuiArchive sbui, UiHostProfile host, Map<String, Path> packs)
            throws IOException {
        return HostCompatibility.check(sbui, host, sources(packs));
    }
}
