package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.runtime.paint.UiPaintHost;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The game's draw providers for UI documents (item icons, 3D previews, the crucible): one
 * registry every document screen paints with, so a provider registered once is available to
 * every screen the game opens. Providers that a document may require are also declared on
 * {@code GameUiHost} ({@code UiHost.offerProvider}) so activation and the editor's export check
 * agree with what actually draws. Main thread only.
 */
public final class GameUiProviders {

    private static final Map<String, UiPaintHost.UiDrawProvider> PROVIDERS = new LinkedHashMap<>();

    private GameUiProviders() {
    }

    /** Registers (or replaces) the provider for {@code id}. */
    public static void register(String id, UiPaintHost.UiDrawProvider provider) {
        if (provider == null) {
            PROVIDERS.remove(id);
        } else {
            PROVIDERS.put(id, provider);
        }
    }

    /**
     * The game providers that paint with Skia alone (the furnace crucible), for hosts without the
     * game's GL (the editor preview, raster captures). Fresh instances per call.
     */
    public static Map<String, UiPaintHost.UiDrawProvider> skiaOnly() {
        return com.stonebreak.ui.runtime.providers.GameDrawProviders.skiaOnly();
    }

    /** A snapshot of every registered provider, for a document being opened. */
    public static Map<String, UiPaintHost.UiDrawProvider> all() {
        com.stonebreak.ui.runtime.providers.GameDrawProviders.install(); // item icons, 3D previews (once)
        return Map.copyOf(PROVIDERS);
    }
}
