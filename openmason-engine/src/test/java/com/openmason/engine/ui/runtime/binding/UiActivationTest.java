package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Missing host capabilities show before a screen activates (#289). */
class UiActivationTest {

    private static final UiDocumentSource COMPONENTS =
        UiDocumentSource.of(Map.of(UiSamples.BUTTON_ID, UiSamples.stoneButton()), Map.of());

    private static UiHost host(boolean pauseScreen, boolean resync) {
        UiHost h = new UiHost();
        h.data().register("session", new DataCell(DataType.object("online", DataType.bool()),
            new UiValue.Obj(Map.of("online", UiValue.FALSE))), HostContract.of("stonebreak:session", 1));
        if (pauseScreen) {
            h.offer(HostContract.of("stonebreak:screen.pause", 1));
        }
        if (resync) {
            h.offer(HostContract.of("stonebreak:network.resync", 1));
        }
        return h;
    }

    private static List<UiDiagnostic.Code> codes(List<UiDiagnostic> d) {
        return d.stream().map(UiDiagnostic::code).toList();
    }

    @Test
    void aCompleteHostActivatesThePauseMenu() throws Exception {
        assertTrue(UiActivation.require(UiSamples.pauseMenu(), COMPONENTS, host(true, true)).isEmpty());
    }

    @Test
    void anOptionalCapabilityIsAWarningARequiredOneAnError() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        List<UiDiagnostic> d = UiActivation.check(pause, COMPONENTS, host(true, false));
        assertEquals(List.of(UiDiagnostic.Code.UNSUPPORTED_HOST_API), codes(d));
        assertEquals(UiDiagnostic.Severity.WARNING, d.getFirst().severity());

        UiActivationException e = assertThrows(UiActivationException.class,
            () -> UiActivation.require(pause, COMPONENTS, host(false, true)));
        assertTrue(e.getMessage().contains("stonebreak:screen.pause"), e.getMessage());
    }

    @Test
    void exportedScreensAreCheckedByTheirManifestUnion() throws Exception {
        var sbui = SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared()).archive();
        UiActivationException e = assertThrows(UiActivationException.class,
            () -> UiActivation.require(sbui, COMPONENTS, new UiHost()));
        assertTrue(codes(e.diagnostics()).contains(UiDiagnostic.Code.UNKNOWN_DATA_SOURCE), "no session root");
        assertTrue(UiActivation.require(sbui, COMPONENTS, host(true, true)).isEmpty());
    }

    @Test
    void unknownDataRootsAndUndeclaredContractsArePointedAt() {
        OmuiArchive doc = screen("t:ui/x", box("root").kids(box("panel").data("furnace").kids(
            label("a", "?").bind("prop:text", "session.online"))));
        List<UiDiagnostic> d = UiActivation.check(doc, UiDocumentSource.EMPTY, host(true, true));
        UiDiagnostic unknown = d.stream().filter(x -> x.code() == UiDiagnostic.Code.UNKNOWN_DATA_SOURCE).findFirst().orElseThrow();
        assertEquals("/root/children/0/dataSource", unknown.pointer());
        UiDiagnostic undeclared = d.stream().filter(x -> x.code() == UiDiagnostic.Code.UNDECLARED_HOST_API).findFirst().orElseThrow();
        assertEquals("/root/children/0/children/0/bindings/0/path", undeclared.pointer());
        assertEquals(UiDiagnostic.Severity.ERROR, undeclared.severity(), "the scope would refuse it (#327)");
    }

    @Test
    void thePreviewFixtureHostStandsInForEveryDeclaredContract() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        assertTrue(UiActivation.require(pause, COMPONENTS, FixtureHost.forArchive(pause).host()).stream()
            .noneMatch(UiDiagnostic::isError));
    }
}
