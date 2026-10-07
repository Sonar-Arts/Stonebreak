package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The editor's deterministic stand-in host (#289). */
class FixtureHostTest {

    private static UiManifest declaring(String... contracts) {
        UiManifest m = UiManifest.create("t:ui/pause", UiManifest.DocumentKind.SCREEN, "Pause");
        return new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), m.displayName(), m.uiApi(),
            m.layoutSemantics(), m.requires(), java.util.Arrays.stream(contracts)
            .map(c -> new UiManifest.HostRequirement(c, 1, false, Map.of())).toList(), m.providers(), m.unknown());
    }

    private static FixtureHost parse(String json, UiManifest m) {
        return FixtureHost.parse(json.getBytes(StandardCharsets.UTF_8), "fixture.json", m);
    }

    @Test
    void shorthandFixturesAreDataRoots() {
        FixtureHost f = parse("{\"session\": {\"online\": true}}", declaring("stonebreak:session"));
        UiScope s = f.host().openScope("t:ui/pause", null, p -> { });
        assertEquals(DataState.ready(UiValue.TRUE), s.read(DataPath.parse("session.online")));
        assertTrue(f.host().contracts().containsKey("stonebreak:session"), "declared contracts are offered");
        assertTrue(f.host().check(declaring("stonebreak:session")).isEmpty());
    }

    @Test
    void actionsRespondDeterministically() {
        FixtureHost f = parse("""
            {
              "contracts": {"session": "stonebreak:session"},
              "data": {"session": {"online": false}},
              "actions": {
                "stonebreak:network.resync": {"params": {"full": "bool"}, "result": {"chunks": 12}},
                "stonebreak:network.load": {"pending": true},
                "stonebreak:network.delete": {"error": "permission denied"}
              }
            }""", declaring("stonebreak:network", "stonebreak:session"));
        UiScope s = f.host().openScope("t:ui/pause", Set.of("stonebreak:network"), p -> { });
        CallSite site = s.site("b", CallSite.Origin.SCRIPT);
        ActionCall ok = s.invoke("stonebreak:network.resync", new UiValue.Obj(Map.of("full", UiValue.TRUE)), site);
        assertEquals(ActionCall.State.SUCCEEDED, ok.state());
        assertEquals(UiValue.of(12), ((UiValue.Obj) ok.result()).get("chunks"));
        assertThrows(UiActionException.class,
            () -> s.invoke("stonebreak:network.resync", new UiValue.Obj(Map.of("full", UiValue.of(1))), site));
        assertEquals(ActionCall.State.FAILED, s.invoke("stonebreak:network.delete", null, site).state());
        ActionCall load = s.invoke("stonebreak:network.load", null, site);
        assertEquals(1, f.heldCount("stonebreak:network.load"));
        assertTrue(f.release("stonebreak:network.load", UiValue.of("done")));
        assertTrue(load.isPending(), "released work lands at the next drain");
        f.host().drain();
        assertEquals(UiValue.of("done"), load.result());
        assertEquals(List.of("stonebreak:network.resync", "stonebreak:network.delete", "stonebreak:network.load"),
            f.calls().stream().map(FixtureHost.Call::actionId).toList());
        assertEquals("stonebreak:network", f.host().actions().spec("stonebreak:network.load").contract().id());
    }

    @Test
    void editableFixtureRootsCommitToTheCell() {
        FixtureHost f = parse("""
            {"data": {"settings": {"fov": 70}}, "editable": {"settings": {"commit": "fixture:settings.apply"}}}
            """, declaring());
        UiScope s = f.host().openScope("t:ui/settings", null, p -> { });
        CallSite site = s.site("fov", CallSite.Origin.BINDING);
        assertTrue(s.edits().stage(DataPath.parse("settings.fov"), UiValue.of(90), site).accepted());
        assertEquals(ActionCall.State.SUCCEEDED, s.edits().apply("settings", site).state());
        assertEquals(new UiValue.Obj(Map.of("fov", UiValue.of(90))), f.cell("settings").value());
    }

    @Test
    void collections() {
        FixtureHost f = parse("{\"collections\": {\"slots\": {\"identity\": \"id\", \"items\": [{\"id\": 1}, {\"id\": 2}]}}}",
            declaring());
        assertEquals(2, f.collection("slots").items().size());
        f.collection("slots").remove("1");
        assertEquals(1, f.collection("slots").items().size());
    }

    // ── #327: fixture roots stand in for the contracts the document declares ──

    @Test
    void shorthandRootsTakeTheDeclaredContractTheyName() {
        FixtureHost f = parse("{\"session\": {\"online\": true}, \"vitals\": {\"health\": 3}, \"weather\": {\"rain\": 1}}",
            declaring("stonebreak:session", "stonebreak:player.vitals", "stonebreak:screen.pause"));
        assertEquals("stonebreak:session", f.host().data().root("session").contract().id());
        assertEquals("stonebreak:player.vitals", f.host().data().root("vitals").contract().id(), "suffix match");
        assertEquals("fixture:weather", f.host().data().root("weather").contract().id(), "no declared contract names it");

        UiScope s = f.host().openScope("t:ui/pause", Set.of("stonebreak:session", "stonebreak:player.vitals",
            "stonebreak:screen.pause"), p -> { });
        assertEquals(DataState.ready(UiValue.TRUE), s.read(DataPath.parse("session.online")), "the preview keeps working");
        assertEquals(DataState.ready(UiValue.of(3)), s.read(DataPath.parse("vitals.health")));
        assertTrue(s.read(DataPath.parse("weather.rain")) instanceof DataState.Failed, "a fixture cannot widen access");
        assertEquals(UiActionException.Code.CAPABILITY_MISSING, assertThrows(UiActionException.class,
            () -> s.requireDeclared(DataPath.parse("weather.rain"), s.site("x", CallSite.Origin.SCRIPT))).code());
        s.close();
    }

    @Test
    void explicitContractsWinAndUndeclaredOnesStayRefused() {
        FixtureHost f = parse("""
            {"contracts": {"carried": "stonebreak:inventory", "session": "stonebreak:other"},
             "data": {"carried": {"count": 1}, "session": {"online": true}}}
            """, declaring("stonebreak:inventory", "stonebreak:session"));
        assertEquals("stonebreak:inventory", f.host().data().root("carried").contract().id());
        assertEquals("stonebreak:other", f.host().data().root("session").contract().id(), "the fixture's word is final");
        UiScope s = f.host().openScope("t:ui/inv", Set.of("stonebreak:inventory", "stonebreak:session"), p -> { });
        assertEquals(DataState.ready(UiValue.of(1)), s.read(DataPath.parse("carried.count")));
        assertTrue(s.read(DataPath.parse("session.online")) instanceof DataState.Failed);
        s.close();
    }

    @Test
    void contractNamingPrefersAnExactLocalIdThenTheShortest() {
        assertEquals("a:session", FixtureHost.declaredContractOf("session", List.of("b:net.session", "a:session")));
        assertEquals("b:x.vitals", FixtureHost.declaredContractOf("vitals", List.of("b:player.vitals", "b:x.vitals")));
        assertEquals(null, FixtureHost.declaredContractOf("vitals", List.of("b:vitalsx", "b:player.vitalsx")));
        assertEquals("plain", FixtureHost.declaredContractOf("plain", List.of("plain")), "no namespace");
    }

    @Test
    void theSamplePauseFixturePreviewsUnderItsDeclaredContracts() throws Exception {
        com.openmason.engine.format.omui.OmuiArchive pause = com.openmason.engine.format.omui.UiSamples.pauseMenu();
        FixtureHost f = FixtureHost.forArchive(pause);
        Map<String, Integer> declared = new java.util.TreeMap<>();
        pause.manifest().hostApis().forEach(h -> declared.put(h.id(), h.version()));
        UiScope s = f.host().openScopeAt(pause.manifest().documentId(), declared, p -> { });
        assertEquals(DataState.ready(UiValue.TRUE), s.read(DataPath.parse("session.online")));
        s.close();
    }
}
