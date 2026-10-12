package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.graph.NodePorts;
import com.openmason.engine.ui.graph.PortSpec;
import com.openmason.engine.ui.graph.PortType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinConnectPickerTest {

    private static NodePorts printLike() {
        return NodePorts.builder()
            .in(PortSpec.exec(PortSpec.EXEC_IN))
            .in(PortSpec.data("text", PortType.STRING))
            .in(PortSpec.data("count", PortType.NUMBER))
            .out(PortSpec.exec(PortSpec.THEN))
            .build();
    }

    @Test
    void anExecOutputMeetsTheFirstExecInput() {
        assertEquals("exec", PinConnectPicker.pick(PortSpec.exec("then"), true, printLike()));
    }

    @Test
    void execNeverMeetsData() {
        NodePorts pure = NodePorts.builder().in(PortSpec.data("a", PortType.NUMBER))
            .out(PortSpec.data("sum", PortType.NUMBER)).build();
        assertNull(PinConnectPicker.pick(PortSpec.exec("then"), true, pure));
        assertFalse(PinConnectPicker.compatible(PortSpec.exec("then"), true, pure));
    }

    @Test
    void aDataOutputPrefersAnExactTypeOverAConversion() {
        // number converts to string, but the exact NUMBER input wins even though it comes later
        assertEquals("count", PinConnectPicker.pick(PortSpec.data("v", PortType.NUMBER), true, printLike()));
        // a bool has no exact input: it falls back to the converting string one
        assertEquals("text", PinConnectPicker.pick(PortSpec.data("flag", PortType.BOOL), true, printLike()));
    }

    @Test
    void aDraggedInputIsFedByTheFirstCompatibleOutput() {
        NodePorts source = NodePorts.builder()
            .out(PortSpec.data("label", PortType.STRING))
            .out(PortSpec.data("n", PortType.INT))
            .build();
        assertEquals("n", PinConnectPicker.pick(PortSpec.data("count", PortType.NUMBER), false, source));
        assertEquals("label", PinConnectPicker.pick(PortSpec.data("text", PortType.STRING), false, source));
        assertNull(PinConnectPicker.pick(PortSpec.data("flags", PortType.LIST), false, source));
    }

    @Test
    void anyMeetsEverythingButExec() {
        assertTrue(PinConnectPicker.compatible(PortSpec.data("v", PortType.ANY), true, printLike()));
        assertNull(PinConnectPicker.pick(PortSpec.data("v", PortType.ANY), false,
            NodePorts.builder().in(PortSpec.exec("exec")).build()));
    }
}
