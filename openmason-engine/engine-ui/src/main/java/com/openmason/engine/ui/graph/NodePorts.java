package com.openmason.engine.ui.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * The ports of one node as resolved against its properties and environment: a function call's
 * signature, a format template's placeholders, a signal's arguments. Inputs and outputs each
 * hold exec ports first, then data ports, in declaration order.
 */
public record NodePorts(List<PortSpec> inputs, List<PortSpec> outputs) {

    public static final NodePorts NONE = new NodePorts(List.of(), List.of());

    public NodePorts {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    public PortSpec input(String name) {
        return find(inputs, name);
    }

    public PortSpec output(String name) {
        return find(outputs, name);
    }

    public boolean hasExecInput() {
        return inputs.stream().anyMatch(PortSpec::isExec);
    }

    public List<PortSpec> execOutputs() {
        return outputs.stream().filter(PortSpec::isExec).toList();
    }

    public List<PortSpec> dataInputs() {
        return inputs.stream().filter(p -> !p.isExec()).toList();
    }

    public List<PortSpec> dataOutputs() {
        return outputs.stream().filter(p -> !p.isExec()).toList();
    }

    private static PortSpec find(List<PortSpec> ports, String name) {
        for (PortSpec p : ports) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** Builds a port list: {@code NodePorts.builder().in(...).out(...).build()}. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<PortSpec> in = new ArrayList<>();
        private final List<PortSpec> out = new ArrayList<>();

        public Builder in(PortSpec p) {
            in.add(p);
            return this;
        }

        public Builder out(PortSpec p) {
            out.add(p);
            return this;
        }

        public Builder in(List<PortSpec> ports) {
            in.addAll(ports);
            return this;
        }

        public Builder out(List<PortSpec> ports) {
            out.addAll(ports);
            return this;
        }

        public NodePorts build() {
            List<PortSpec> i = new ArrayList<>(in.stream().filter(PortSpec::isExec).toList());
            i.addAll(in.stream().filter(p -> !p.isExec()).toList());
            List<PortSpec> o = new ArrayList<>(out.stream().filter(PortSpec::isExec).toList());
            o.addAll(out.stream().filter(p -> !p.isExec()).toList());
            return new NodePorts(i, o);
        }
    }
}
