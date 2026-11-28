package net.talaatharb.expanse.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.talaatharb.expanse.domain.Quantity;
import net.talaatharb.expanse.dto.ApiModels.ConnectOp;
import net.talaatharb.expanse.dto.ApiModels.Operation;
import net.talaatharb.expanse.dto.ApiModels.PlaceOp;
import net.talaatharb.expanse.simulation.SpiceParser;
import net.talaatharb.expanse.simulation.SpiceParser.Element;
import net.talaatharb.expanse.simulation.SpiceParser.Model;

/**
 * Turns a parsed SPICE netlist into place/connect operations on a new project: R/C/L, DC sources, diodes, BJTs and
 * MOSFETs map onto the built-in parts with their model parameters, node 0 gets a ground symbol and every other node
 * with two or more pins becomes a net of the same name. Parts are laid out on a grid for the user to tidy up.
 */
final class SpiceImporter {
    private static final int COLUMNS = 6;
    private static final double PITCH_MM = 30;

    record Plan(String title, List<Operation> operations, List<String> warnings) {
    }

    Plan plan(String text) {
        SpiceParser.Netlist netlist = new SpiceParser().parse(text);
        List<String> warnings = new ArrayList<>(netlist.warnings());
        List<Operation> places = new ArrayList<>();
        Map<String, List<String>> pinsOfNode = new LinkedHashMap<>();
        Set<String> references = new HashSet<>();
        int index = 0;
        for (Element e : netlist.elements()) {
            Model model = netlist.model(e.model());
            String definition;
            Map<String, String> properties = new LinkedHashMap<>();
            List<String> pinOrder;
            switch (e.type()) {
                case 'R' -> {
                    definition = "builtin.resistor";
                    properties.put("resistance", positive(e, e.value()));
                    pinOrder = List.of("1", "2");
                }
                case 'C' -> {
                    definition = "builtin.capacitor";
                    properties.put("capacitance", positive(e, e.value()));
                    pinOrder = List.of("1", "2");
                }
                case 'L' -> {
                    definition = "builtin.inductor";
                    properties.put("inductance", positive(e, e.value()));
                    pinOrder = List.of("1", "2");
                }
                case 'V' -> {
                    definition = "builtin.dc-source";
                    properties.put("voltage", format(e.value()));
                    pinOrder = List.of("1", "2");
                }
                case 'D' -> {
                    definition = "builtin.diode";
                    put(properties, "saturationCurrent", model, "IS");
                    put(properties, "emissionCoefficient", model, "N");
                    pinOrder = List.of("1", "2");
                }
                case 'Q' -> {
                    boolean pnp = model != null && model.type().equals("PNP");
                    definition = pnp ? "builtin.pnp" : "builtin.npn";
                    put(properties, "beta", model, "BF");
                    put(properties, "saturationCurrent", model, "IS");
                    pinOrder = List.of("3", "2", "1");
                }
                case 'M' -> {
                    boolean pmos = model != null && model.type().equals("PMOS");
                    definition = pmos ? "builtin.pmos" : "builtin.nmos";
                    put(properties, "thresholdVoltage", model, "VTO");
                    put(properties, "transconductance", model, "KP");
                    pinOrder = List.of("3", "2", "1");
                }
                default -> throw new IllegalStateException("Unexpected element " + e.name());
            }
            String reference = unique(e.name().toUpperCase(Locale.ROOT), references);
            places.add(new PlaceOp(definition, reference, 20 + PITCH_MM * (index % COLUMNS),
                    20 + PITCH_MM * (index / COLUMNS), 0, properties, null));
            index++;
            for (int i = 0; i < pinOrder.size(); i++) {
                pinsOfNode.computeIfAbsent(node(e.nodes().get(i)), k -> new ArrayList<>())
                        .add(reference + "." + pinOrder.get(i));
            }
        }
        List<Operation> operations = new ArrayList<>(places);
        List<String> ground = pinsOfNode.remove("0");
        if (ground != null) {
            String reference = unique("GND", references);
            operations.add(new PlaceOp("builtin.ground", reference, 20,
                    20 + PITCH_MM * ((index + COLUMNS - 1) / COLUMNS), 0, Map.of(), null));
            List<String> pins = new ArrayList<>(ground);
            pins.add(reference + ".1");
            operations.add(new ConnectOp("GND", pins));
        }
        pinsOfNode.forEach((node, pins) -> {
            if (pins.size() >= 2) {
                operations.add(new ConnectOp(node, pins));
            } else {
                warnings.add("Node " + node + " connects only " + pins.getFirst() + "; it is left unconnected");
            }
        });
        if (netlist.elements().isEmpty()) {
            warnings.add("The netlist has no supported elements");
        }
        return new Plan(netlist.title(), operations, List.copyOf(warnings));
    }

    private static String node(String name) {
        return name.equalsIgnoreCase("gnd") ? "0" : name;
    }

    private static void put(Map<String, String> properties, String parameter, Model model, String key) {
        Double value = model == null ? null : model.parameter(key);
        if (value != null) {
            properties.put(parameter, format(value));
        }
    }

    private static String positive(Element e, Double value) {
        if (value == null || !(value > 0)) {
            throw new net.talaatharb.expanse.domain.DesignException("INVALID_NETLIST",
                    e.name() + " needs a positive value");
        }
        return format(value);
    }

    private static String format(double value) {
        return Quantity.format(value, "");
    }

    private static String unique(String base, Set<String> used) {
        String name = base;
        for (int i = 2; !used.add(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }
}
