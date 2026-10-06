package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.graph.NodePorts;
import com.openmason.engine.ui.graph.PortSpec;
import com.openmason.engine.ui.graph.PortType;

import java.util.List;

/**
 * "Drag a pin into empty space" (pure): which port of a freshly added node should connect to
 * the dragged pin. A dragged output feeds the new node's first input it can reach, a dragged
 * input is fed by the new node's first output; exec only meets exec, and an exact type match
 * beats a converting one.
 */
public final class PinConnectPicker {

    private PinConnectPicker() {
    }

    /**
     * @param dragged       the pin the author dragged from
     * @param draggedOutput true when it is an output pin
     * @param candidate     the ports of the node under consideration
     * @return the name of the candidate's port to link, or null when none is compatible
     */
    public static String pick(PortSpec dragged, boolean draggedOutput, NodePorts candidate) {
        List<PortSpec> ports = draggedOutput ? candidate.inputs() : candidate.outputs();
        String converting = null;
        for (PortSpec p : ports) {
            PortType.Assign a = draggedOutput ? p.type().accepts(dragged.type()) : dragged.type().accepts(p.type());
            if (a == PortType.Assign.NONE) {
                continue;
            }
            if (p.type() == dragged.type() || a == PortType.Assign.DIRECT && p.isExec() == dragged.isExec()
                && (p.type() != PortType.ANY && dragged.type() != PortType.ANY)) {
                return p.name();
            }
            if (converting == null) {
                converting = p.name();
            }
        }
        return converting;
    }

    /** Whether {@code candidate} has any port {@link #pick} would use. */
    public static boolean compatible(PortSpec dragged, boolean draggedOutput, NodePorts candidate) {
        return pick(dragged, draggedOutput, candidate) != null;
    }
}
