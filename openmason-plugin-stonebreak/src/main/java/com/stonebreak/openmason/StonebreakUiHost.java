package com.stonebreak.openmason;

import com.openmason.api.target.UiHostSource;
import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.ui.runtime.GameUiAssets;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiProviders;
import com.stonebreak.ui.runtime.GameUiResources;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The Stonebreak game's UI host, headless (#380; moved out of the tool's {@code UiGameDeploy} in
 * #382): the contracts {@link GameUiHost} declares, its preview fixture, the packaged
 * {@code ui/shared/} assets, the game's UI font and its Skia-only draw providers.
 */
enum StonebreakUiHost implements UiHostSource {
    INSTANCE;

    private volatile UiHostProfile profile;

    @Override
    public UiHostProfile profile() {
        UiHostProfile p = profile;
        if (p == null) {
            p = GameUiHost.declaredProfile();
            profile = p;
        }
        return p;
    }

    @Override
    public UiHost declaration() {
        return GameUiHost.declaration().host();
    }

    @Override
    public byte[] fixtureJson() {
        return GameUiHost.fixtureJson();
    }

    @Override
    public String fixtureName() {
        return GameUiHost.FIXTURE_RESOURCE;
    }

    @Override
    public List<AssetSource> packagedAssets() throws IOException {
        return GameUiAssets.sources(Map.of());
    }

    @Override
    public Typeface loadTypeface() throws IOException {
        return GameUiResources.loadTypeface();
    }

    @Override
    public Map<String, UiPaintHost.UiDrawProvider> previewProviders() {
        return GameUiProviders.skiaOnly();
    }
}
