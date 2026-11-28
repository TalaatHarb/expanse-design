package net.talaatharb.expanse.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Quantity;

/**
 * Writes the analog part of a project as a SPICE3/ngspice netlist using the same device equations as
 * {@link CircuitSimulator}: nets with a ground symbol become node 0, power rails become DC sources to ground, switches
 * become resistors in their current state, LEDs become diodes with an equivalent saturation current and op-amps
 * become a voltage-controlled source behind their output resistance (supply rails are not modelled). Logic and custom
 * parts have no SPICE model and are left out with a warning.
 */
public final class SpiceExporter {
    public static final String OPEN_SWITCH_OHMS = "1T";

    public record Export(String netlist, List<String> warnings) {
    }

    public Export export(Project project) {
        List<String> warnings = new ArrayList<>();
        Map<UUID, String> nodeOfPin = nodes(project);
        List<String> elements = new ArrayList<>();
        List<String> models = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> railNodes = new HashSet<>();
        for (Component c : project.components()) {
            Map<String, String> n = new LinkedHashMap<>();
            for (var pin : c.pins()) {
                n.put(pin.number(), nodeOfPin.getOrDefault(pin.id(), "NC_" + identifier(c.reference()) + "_"
                        + identifier(pin.number())));
            }
            String kind = c.definition().kind();
            switch (kind) {
                case "resistor" -> elements.add(line(name('R', c, names), n.get("1"), n.get("2"),
                        number(c.value("resistance"))));
                case "capacitor" -> elements.add(line(name('C', c, names), n.get("1"), n.get("2"),
                        number(c.value("capacitance"))));
                case "inductor" -> elements.add(line(name('L', c, names), n.get("1"), n.get("2"),
                        number(c.value("inductance"))));
                case "voltage-source" -> elements.add(line(name('V', c, names), n.get("1"), n.get("2"), "DC",
                        number(c.value("voltage"))));
                case "power-rail" -> {
                    String node = n.get("1");
                    if (!node.equals("0") && railNodes.add(node)) {
                        elements.add(line(name('V', c, names), node, "0", "DC", number(c.value("voltage"))));
                    }
                }
                case "ground" -> {
                }
                case "switch" -> {
                    boolean closed = "closed".equals(c.properties().get("state"));
                    elements.add("* " + c.reference() + " switch (" + (closed ? "closed" : "open") + ")");
                    elements.add(line(name('R', c, names), n.get("1"), n.get("2"),
                            closed ? number(c.value("onResistance")) : OPEN_SWITCH_OHMS));
                }
                case "diode", "led" -> {
                    String model = "D_" + identifier(c.reference());
                    double emission = c.value("emissionCoefficient");
                    double saturation = kind.equals("led")
                            ? CircuitSimulator.LED_REFERENCE_CURRENT
                                    * Math.exp(-c.value("forwardVoltage") / (emission * CircuitSimulator.VT))
                            : c.value("saturationCurrent");
                    if (kind.equals("led")) {
                        elements.add("* " + c.reference() + " LED (" + c.properties().get("color") + ", Vf "
                                + number(c.value("forwardVoltage")) + " at 20mA)");
                    }
                    elements.add(line(name('D', c, names), n.get("1"), n.get("2"), model));
                    models.add(".model " + model + " D(IS=" + number(saturation) + " N=" + number(emission) + ")");
                }
                case "npn", "pnp" -> {
                    String model = "Q_" + identifier(c.reference());
                    elements.add(line(name('Q', c, names), n.get("3"), n.get("2"), n.get("1"), model));
                    models.add(".model " + model + " " + kind.toUpperCase(Locale.ROOT) + "(BF="
                            + number(c.value("beta")) + " IS=" + number(c.value("saturationCurrent")) + ")");
                }
                case "nmos", "pmos" -> {
                    String model = "M_" + identifier(c.reference());
                    elements.add(line(name('M', c, names), n.get("3"), n.get("2"), n.get("1"), n.get("1"), model));
                    models.add(".model " + model + " " + kind.toUpperCase(Locale.ROOT) + "(LEVEL=1 VTO="
                            + number(c.value("thresholdVoltage")) + " KP=" + number(c.value("transconductance")) + ")");
                }
                case "opamp" -> {
                    String internal = identifier(c.reference()) + "_int";
                    elements.add("* " + c.reference() + " op-amp: ideal gain stage, supply rails are not modelled");
                    elements.add(line(name('E', c, names), internal, "0", n.get("3"), n.get("2"),
                            number(c.value("openLoopGain"))));
                    elements.add(line("R" + identifier(c.reference()) + "_out", internal, n.get("6"),
                            number(c.value("outputResistance"))));
                    warnings.add(c.reference() + " is exported as an ideal gain stage; its output is not limited "
                            + "by the supply rails.");
                }
                default -> {
                    elements.add("* " + c.reference() + " (" + c.definition().name() + ") has no SPICE model");
                    warnings.add(c.reference() + " (" + c.definition().name() + ") has no SPICE model and was "
                            + "left out.");
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(project.name().replaceAll("[\\r\\n]+", " ")).append('\n');
        out.append("* Exported by Expanse Design (revision ").append(project.revision()).append(")\n");
        elements.forEach(e -> out.append(e).append('\n'));
        models.forEach(m -> out.append(m).append('\n'));
        out.append(".op\n.end\n");
        return new Export(out.toString(), List.copyOf(warnings));
    }

    /** SPICE numbers: engineering suffixes with {@code Meg} for mega because {@code M} means milli in SPICE. */
    public static String number(double value) {
        if (value != 0 && (Math.abs(value) < 1e-15 || Math.abs(value) >= 1e15)) {
            return String.format(Locale.ROOT, "%.4e", value);
        }
        String text = Quantity.format(value, "");
        return text.endsWith("M") ? text.substring(0, text.length() - 1) + "Meg" : text;
    }

    private static Map<UUID, String> nodes(Project project) {
        Set<UUID> groundPins = new HashSet<>();
        project.components().stream().filter(c -> c.definition().kind().equals("ground"))
                .forEach(c -> c.pins().forEach(p -> groundPins.add(p.id())));
        Map<UUID, String> nodeOfPin = new HashMap<>();
        Set<String> used = new HashSet<>(Set.of("0"));
        for (Net net : project.nets()) {
            String node;
            if (net.pinIds().stream().anyMatch(groundPins::contains)) {
                node = "0";
            } else {
                String base = identifier(net.name());
                node = base;
                for (int i = 2; !used.add(node.toLowerCase(Locale.ROOT)); i++) {
                    node = base + "_" + i;
                }
            }
            String finalNode = node;
            net.pinIds().forEach(p -> nodeOfPin.put(p, finalNode));
        }
        return nodeOfPin;
    }

    private static String name(char letter, Component c, Set<String> names) {
        String ref = identifier(c.reference());
        String base = Character.toUpperCase(ref.charAt(0)) == letter ? ref : letter + ref;
        String name = base;
        for (int i = 2; !names.add(name.toUpperCase(Locale.ROOT)); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    private static String identifier(String text) {
        String id = text.replaceAll("[^A-Za-z0-9_]", "_");
        return id.isEmpty() ? "_" : id;
    }

    private static String line(String... tokens) {
        return String.join(" ", tokens);
    }
}
