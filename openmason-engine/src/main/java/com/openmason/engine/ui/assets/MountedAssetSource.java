package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency.Kind;

import java.io.IOException;
import java.util.Objects;

/**
 * Deployed shared assets laid out by convention ({@code <prefix><namespace>/<path><ext>}):
 * the game's packaged resource root or a declared resource pack. Runtime sources never read
 * {@code sourceHint} — a deployed game has no Open Mason workspace.
 */
public final class MountedAssetSource implements AssetSource {

    private final String name;
    private final AssetOrigin origin;
    private final String packId;
    private final String prefix;
    private final ResourceOpener opener;

    private MountedAssetSource(String name, AssetOrigin origin, String packId, String prefix, ResourceOpener opener) {
        this.name = name;
        this.origin = origin;
        this.packId = packId;
        this.prefix = prefix == null ? "" : prefix;
        this.opener = Objects.requireNonNull(opener, "opener");
        if (!this.prefix.isEmpty() && !this.prefix.endsWith("/")) {
            throw new IllegalArgumentException("prefix must end with '/': " + prefix);
        }
    }

    /** The host's packaged resource root, e.g. {@code ui/shared/} on the game classpath. */
    public static MountedAssetSource packaged(String prefix, ResourceOpener opener) {
        return new MountedAssetSource("packaged", AssetOrigin.PACKAGED, null, prefix, opener);
    }

    /** A declared resource pack; serves only dependency rows that name {@code packId}. */
    public static MountedAssetSource pack(String packId, ResourceOpener opener) {
        if (!OmuiFormat.LOGICAL_ID.matcher(packId).matches()) {
            throw new IllegalArgumentException("Pack ids are logical ids: " + packId);
        }
        return new MountedAssetSource("pack:" + packId, AssetOrigin.PACK, packId, "", opener);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AssetOrigin origin() {
        return origin;
    }

    @Override
    public String packId() {
        return packId;
    }

    @Override
    public ResolvedAsset find(String id, Kind kind, String sourceHint) throws IOException {
        for (String candidate : AssetKinds.candidates(id, kind)) {
            String path = prefix + candidate;
            UiBytes bytes = opener.read(path);
            if (bytes != null) {
                return new ResolvedAsset(id, kind, bytes, origin, name, path);
            }
        }
        return null;
    }
}
