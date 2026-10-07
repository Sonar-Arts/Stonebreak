package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiRequirements;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataRoot;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.data.UiHost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The canonical preview fixture ({@link GameUiHost#FIXTURE_RESOURCE}) says only what the game
 * host can really produce, and covers every data root and action it offers — so a document
 * previewed in the editor against it binds and invokes exactly what it will in the game (#289
 * review: fixtures and host types were kept in sync by hand, with one parity test for pause).
 */
class GameHostFixtureParityTest {

    private static FixtureHost fixture(UiHost game) throws IOException {
        byte[] json = GameUiHost.fixtureJson();
        List<UiManifest.HostRequirement> apis = game.profile().hostApis().entrySet().stream()
            .map(e -> new UiManifest.HostRequirement(e.getKey(), e.getValue(), false, Map.of())).toList();
        UiRequirements declared = new UiRequirements() {
            @Override public int uiApi() { return UiHost.UI_API; }
            @Override public String layoutSemantics() { return "flex-1"; }
            @Override public List<String> requires() { return List.of(); }
            @Override public List<UiManifest.HostRequirement> hostApis() { return apis; }
            @Override public List<UiManifest.HostRequirement> providers() { return List.of(); }
        };
        return FixtureHost.parse(json, GameUiHost.FIXTURE_RESOURCE, declared);
    }

    @Test
    void theFixtureIsAFaithfulStandInForTheGameHost() throws IOException {
        UiHost game = GameUiHost.declaration().host();
        assertEquals(List.of(), fixture(game).compatibility(game));
    }

    @Test
    void theFixtureCoversEveryRootAndAction() throws IOException {
        UiHost game = GameUiHost.declaration().host();
        UiHost preview = fixture(game).host();
        assertEquals(roots(game), roots(preview), "data roots");
        assertEquals(actions(game), actions(preview), "actions");
    }

    private static Set<String> roots(UiHost h) {
        return h.data().roots().stream().map(DataRoot::name).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> actions(UiHost h) {
        return h.actions().specs().stream().map(ActionSpec::id).collect(Collectors.toCollection(TreeSet::new));
    }
}
