package com.openmason.api.target;

import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A {@link GameTarget}'s UI host, headless (#380, #381): what the editor checks, previews and
 * deploys documents against without the game running.
 */
public interface UiHostSource {

    /** The contracts, data roots and actions the game's host declares. */
    UiHostProfile profile();

    /** The game's host declaration (every contract it registers, no game behind it). */
    UiHost declaration();

    /** The game's canonical preview fixture (JSON). */
    byte[] fixtureJson();

    /** The fixture's name, for diagnostics. */
    String fixtureName();

    /** The game's packaged UI asset sources (shared rows resolve through them). */
    List<AssetSource> packagedAssets() throws IOException;

    /** A new typeface owned by the caller: the font the game draws UI with. */
    Typeface loadTypeface() throws IOException;

    /** Draw providers that work in the editor's preview (Skia-only); GL ones stay placeholders. */
    default Map<String, UiPaintHost.UiDrawProvider> previewProviders() {
        return Map.of();
    }
}
