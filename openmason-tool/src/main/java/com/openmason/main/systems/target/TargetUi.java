package com.openmason.main.systems.target;

import com.openmason.api.target.GameTarget;
import com.openmason.api.target.UiHostSource;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the editor's UI previews take from the active game target (#381): its font, packaged UI
 * assets and Skia-only draw providers, through its {@link UiHostSource}. Without a target (or one
 * with no UI host) previews still run, on the tool's own font with no game assets.
 *
 * <p>The editor owns one {@link #textures() texture cache} for every preview: no game runs in the
 * editor, so there is no game cache to share.
 */
public final class TargetUi {

    private static final Logger logger = LoggerFactory.getLogger(TargetUi.class);

    /** The font previews fall back to without a game target's. */
    static final String FALLBACK_TYPEFACE = "/masonFonts/JetBrainsMono-Regular.ttf";

    private static final MTextureCache TEXTURES = new MTextureCache();

    private TargetUi() {
    }

    private static Optional<UiHostSource> host() {
        return GameTargets.shared().active().map(GameTarget::uiHost);
    }

    /** A new typeface owned by the caller (close it when done): the target's UI font, else the tool's. */
    public static Typeface loadTypeface() throws IOException {
        Optional<UiHostSource> host = host();
        if (host.isPresent()) {
            return host.get().loadTypeface();
        }
        try (InputStream in = TargetUi.class.getResourceAsStream(FALLBACK_TYPEFACE)) {
            if (in == null) {
                throw new IOException("Missing tool font " + FALLBACK_TYPEFACE);
            }
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        }
    }

    /** The target's packaged UI asset sources; empty without a UI host or when they are unreadable. */
    public static List<AssetSource> packagedAssets() {
        Optional<UiHostSource> host = host();
        if (host.isEmpty()) {
            return List.of();
        }
        try {
            return host.get().packagedAssets();
        } catch (IOException e) {
            logger.warn("Packaged UI assets unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /** The editor's texture cache, which owns every preview texture. */
    public static MTextureCache textures() {
        return TEXTURES;
    }

    /** The target's draw providers that work in a preview; none without a UI host. */
    public static Map<String, UiPaintHost.UiDrawProvider> previewProviders() {
        return host().map(UiHostSource::previewProviders).orElse(Map.of());
    }

    /**
     * An SBT texture from the active target's resources, owned by {@link #textures()}: never close
     * the result.
     *
     * @return null without a target, or when the resource is missing or cannot be decoded
     */
    public static MTexture targetTexture(String resource) {
        Optional<GameTarget> target = GameTargets.shared().active();
        if (target.isEmpty() || resource == null) {
            return null;
        }
        GameTarget t = target.get();
        return TEXTURES.get("target:" + t.id() + ":" + resource,
                key -> MTexture.loadFromResource(resource, path -> t.resources().open(path)));
    }
}
