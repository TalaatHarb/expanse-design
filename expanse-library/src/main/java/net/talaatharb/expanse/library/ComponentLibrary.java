package net.talaatharb.expanse.library;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.talaatharb.expanse.domain.Design.Body;
import net.talaatharb.expanse.domain.Design.Definition;
import net.talaatharb.expanse.domain.Design.Footprint;
import net.talaatharb.expanse.domain.Design.Pad;
import net.talaatharb.expanse.domain.Design.ParameterDefinition;
import net.talaatharb.expanse.domain.Design.PinDefinition;
import net.talaatharb.expanse.domain.DesignException;

/** Built-in, versioned component definitions. Projects keep a snapshot of the definition they were placed with. */
public final class ComponentLibrary {
    private static final Footprint VIRTUAL = new Footprint("virtual", List.of(), 0, 0);
    private static final Body NO_BODY = new Body("none", 0, 0, 0, "#000000");
    private final List<Definition> definitions = List.of(
            new Definition("builtin.resistor", "2.0", "Resistor", "resistor", twoPin("A", "B"),
                    List.of(numeric("resistance", "ohm", "1k")),
                    axial("R_Axial_P10.16mm", 10.16), new Body("axial", 6.3, 2.5, 2.5, "#d2b48c")),
            new Definition("builtin.capacitor", "2.0", "Capacitor", "capacitor", twoPin("A", "B"),
                    List.of(numeric("capacitance", "F", "10u")),
                    new Footprint("C_Radial_P2.50mm", List.of(pad("1", -1.25, 0, 1.6, 0.8), pad("2", 1.25, 0, 1.6, 0.8)),
                            6, 6), new Body("cylinder", 5, 5, 11, "#1f3a93")),
            new Definition("builtin.inductor", "2.0", "Inductor", "inductor", twoPin("A", "B"),
                    List.of(numeric("inductance", "H", "1m")),
                    axial("L_Axial_P10.16mm", 10.16), new Body("axial", 9, 3.5, 3.5, "#3a7d44")),
            new Definition("builtin.dc-source", "2.0", "DC voltage source", "voltage-source", twoPin("+", "-"),
                    List.of(numeric("voltage", "V", "5")),
                    new Footprint("TerminalBlock_2P_P5.08mm",
                            List.of(pad("1", -2.54, 0, 2.2, 1.1), pad("2", 2.54, 0, 2.2, 1.1)), 11, 9),
                    new Body("box", 10.2, 8, 10, "#2e86de")),
            new Definition("builtin.power-rail", "2.0", "Power rail", "power-rail",
                    List.of(new PinDefinition("1", "VCC", true)), List.of(numeric("voltage", "V", "5")),
                    VIRTUAL, NO_BODY),
            new Definition("builtin.ground", "2.0", "Ground", "ground",
                    List.of(new PinDefinition("1", "GND", true)), List.of(), VIRTUAL, NO_BODY),
            new Definition("builtin.switch-spst", "2.0", "Switch (SPST)", "switch", twoPin("A", "B"),
                    List.of(new ParameterDefinition("state", "", "open", List.of("open", "closed")),
                            numeric("onResistance", "ohm", "10m")),
                    new Footprint("SW_SPST_P5.00mm", List.of(pad("1", -2.5, 0, 1.8, 1.0), pad("2", 2.5, 0, 1.8, 1.0)),
                            8, 7), new Body("box", 6, 6, 5, "#333333")),
            transistor("builtin.npn", "NPN transistor (TO-92)", "npn", bjtPins(), bjtParameters()),
            transistor("builtin.pnp", "PNP transistor (TO-92)", "pnp", bjtPins(), bjtParameters()),
            transistor("builtin.nmos", "N-channel MOSFET (TO-92)", "nmos", mosPins(),
                    List.of(numeric("thresholdVoltage", "V", "2"), numeric("transconductance", "A/V^2", "100m"))),
            transistor("builtin.pmos", "P-channel MOSFET (TO-92)", "pmos", mosPins(),
                    List.of(numeric("thresholdVoltage", "V", "-2"), numeric("transconductance", "A/V^2", "100m"))),
            new Definition("builtin.logic-input", "1.0", "Logic input (constant)", "logic-input",
                    List.of(new PinDefinition("1", "OUT", true)),
                    List.of(new ParameterDefinition("level", "", "low", List.of("low", "high")),
                            numeric("highVoltage", "V", "5")), VIRTUAL, NO_BODY),
            new Definition("builtin.logic-clock", "1.0", "Logic clock", "logic-clock",
                    List.of(new PinDefinition("1", "CLK", true)),
                    List.of(numeric("frequency", "Hz", "1k"), numeric("dutyCycle", "", "0.5"),
                            numeric("highVoltage", "V", "5")), VIRTUAL, NO_BODY),
            gate("and", "AND gate"),
            gate("or", "OR gate"),
            gate("nand", "NAND gate"),
            gate("nor", "NOR gate"),
            gate("xor", "XOR gate"),
            new Definition("builtin.logic-not", "1.0", "NOT gate (inverter)", "logic-not",
                    List.of(new PinDefinition("1", "A", true), new PinDefinition("2", "Y", true)),
                    gateParameters(), VIRTUAL, NO_BODY),
            dip14("builtin.74hc00", "74HC00 quad 2-input NAND", "logic-74hc00", quadPins()),
            dip14("builtin.74hc04", "74HC04 hex inverter", "logic-74hc04",
                    List.of("1A", "1Y", "2A", "2Y", "3A", "3Y", "GND", "4Y", "4A", "5Y", "5A", "6Y", "6A", "VCC")),
            dip14("builtin.74hc08", "74HC08 quad 2-input AND", "logic-74hc08", quadPins()),
            dip14("builtin.74hc32", "74HC32 quad 2-input OR", "logic-74hc32", quadPins()),
            dip14("builtin.74hc86", "74HC86 quad 2-input XOR", "logic-74hc86", quadPins()),
            dip14("builtin.74hc74", "74HC74 dual D flip-flop", "logic-74hc74",
                    List.of("1CLR", "1D", "1CLK", "1PRE", "1Q", "1QN", "GND", "2QN", "2Q", "2PRE", "2CLK", "2D",
                            "2CLR", "VCC")));

    public List<Definition> all() {
        return definitions;
    }

    public List<Definition> search(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return definitions.stream().filter(d -> d.name().toLowerCase(Locale.ROOT).contains(normalized)
                || d.id().contains(normalized) || d.kind().contains(normalized)).toList();
    }

    public Definition get(String id) {
        return find(id).orElseThrow(() -> new DesignException("DEFINITION_NOT_FOUND",
                "Unknown component definition: " + id));
    }

    public Optional<Definition> find(String id) {
        return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    private static List<PinDefinition> twoPin(String first, String second) {
        return List.of(new PinDefinition("1", first, true), new PinDefinition("2", second, true));
    }

    private static List<PinDefinition> bjtPins() {
        return List.of(new PinDefinition("1", "E", true), new PinDefinition("2", "B", true),
                new PinDefinition("3", "C", true));
    }

    private static List<PinDefinition> mosPins() {
        return List.of(new PinDefinition("1", "S", true), new PinDefinition("2", "G", true),
                new PinDefinition("3", "D", true));
    }

    private static List<ParameterDefinition> bjtParameters() {
        return List.of(numeric("beta", "", "100"), numeric("saturationCurrent", "A", "10f"));
    }

    private static ParameterDefinition numeric(String name, String unit, String value) {
        return new ParameterDefinition(name, unit, value, List.of());
    }

    private static Pad pad(String number, double x, double y, double size, double drill) {
        return new Pad(number, x, y, size, size, drill);
    }

    private static Footprint axial(String name, double pitch) {
        return new Footprint(name, List.of(pad("1", -pitch / 2, 0, 1.6, 0.8), pad("2", pitch / 2, 0, 1.6, 0.8)),
                pitch + 2.4, 3.5);
    }

    private static List<ParameterDefinition> gateParameters() {
        return List.of(numeric("propagationDelay", "s", "10n"), numeric("highVoltage", "V", "5"));
    }

    /** Single schematic-only gate, implicitly powered at highVoltage. */
    private static Definition gate(String function, String name) {
        return new Definition("builtin.logic-" + function, "1.0", name, "logic-" + function,
                List.of(new PinDefinition("1", "A", true), new PinDefinition("2", "B", true),
                        new PinDefinition("3", "Y", true)), gateParameters(), VIRTUAL, NO_BODY);
    }

    private static List<String> quadPins() {
        return List.of("1A", "1B", "1Y", "2A", "2B", "2Y", "GND", "3Y", "3A", "3B", "4Y", "4A", "4B", "VCC");
    }

    /**
     * 74-series DIP-14: pins 1-7 run down the left column and 8-14 up the right column (top view, Y up),
     * 2.54 mm pitch and 7.62 mm row spacing. Only the supply pins are required; unused gates may stay open.
     */
    private static Definition dip14(String id, String name, String kind, List<String> pinNames) {
        List<PinDefinition> pins = new java.util.ArrayList<>();
        List<Pad> pads = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            String number = String.valueOf(i + 1);
            String pinName = pinNames.get(i);
            pins.add(new PinDefinition(number, pinName, pinName.equals("VCC") || pinName.equals("GND")));
            double x = i < 7 ? -3.81 : 3.81;
            double y = i < 7 ? 7.62 - i * 2.54 : -7.62 + (i - 7) * 2.54;
            pads.add(new Pad(number, x, y, 1.6, 1.6, 0.8));
        }
        return new Definition(id, "1.0", name, kind, pins, List.of(numeric("propagationDelay", "s", "10n")),
                new Footprint("DIP-14_W7.62mm", pads, 10.2, 19.6), new Body("box", 6.35, 19, 3.3, "#1c1c1c"));
    }

    private static Definition transistor(String id, String name, String kind, List<PinDefinition> pins,
                                         List<ParameterDefinition> parameters) {
        return new Definition(id, "2.0", name, kind, pins, parameters,
                new Footprint("TO-92_Inline", List.of(new Pad("1", -1.27, 0, 1.05, 1.5, 0.6),
                        new Pad("2", 0, 0, 1.05, 1.5, 0.6), new Pad("3", 1.27, 0, 1.05, 1.5, 0.6)), 5.5, 4.5),
                new Body("box", 4.8, 3.8, 4.8, "#222222"));
    }
}
