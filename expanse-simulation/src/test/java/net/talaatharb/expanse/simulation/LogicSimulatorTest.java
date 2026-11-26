package net.talaatharb.expanse.simulation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.schematic.SchematicEditor;
import net.talaatharb.expanse.simulation.CircuitSimulator.Options;
import net.talaatharb.expanse.simulation.CircuitSimulator.SwitchEvent;
import org.junit.jupiter.api.Test;

class LogicSimulatorTest {
    private final ComponentLibrary library = new ComponentLibrary();
    private final SchematicEditor editor = new SchematicEditor();
    private final LogicSimulator logic = new LogicSimulator();
    private Project project = Project.create("logic");

    @Test
    void quadGateIcsMatchTheirTruthTables() {
        Map<String, String> expected = Map.of("builtin.74hc00", "1110", "builtin.74hc08", "0001",
                "builtin.74hc32", "0111", "builtin.74hc86", "0110");
        expected.forEach((definition, table) -> {
            for (int row = 0; row < 4; row++) {
                project = Project.create("truth");
                powered("U1", definition);
                place("A", "builtin.logic-input", Map.of("level", (row & 2) != 0 ? "high" : "low"));
                place("B", "builtin.logic-input", Map.of("level", (row & 1) != 0 ? "high" : "low"));
                connect("IN_A", "A.1", "U1.12");
                connect("IN_B", "B.1", "U1.13");
                connect("Y", "U1.11", "U1.1");
                var result = logic.simulate(project, 1e-6, List.of());
                assertThat(result.levels().get("Y")).as(definition + " row " + row)
                        .isEqualTo(String.valueOf(table.charAt(row)));
            }
        });
    }

    @Test
    void singleGatesEvaluateWithoutSupplyPins() {
        Map<String, String> expected = Map.of("and", "0001", "or", "0111", "nand", "1110", "nor", "1000",
                "xor", "0110");
        expected.forEach((gate, table) -> {
            for (int row = 0; row < 4; row++) {
                project = Project.create("gate");
                place("U1", "builtin.logic-" + gate, Map.of());
                place("A", "builtin.logic-input", Map.of("level", (row & 2) != 0 ? "high" : "low"));
                place("B", "builtin.logic-input", Map.of("level", (row & 1) != 0 ? "high" : "low"));
                place("P", "builtin.logic-not", Map.of());
                connect("IN_A", "A.1", "U1.A");
                connect("IN_B", "B.1", "U1.B");
                connect("Y", "U1.Y", "P.A");
                assertThat(logic.simulate(project, 1e-6, List.of()).levels().get("Y")).as(gate + " row " + row)
                        .isEqualTo(String.valueOf(table.charAt(row)));
            }
        });
    }

    @Test
    void unpoweredIcDrivesUnknownAndFloatingInputsPropagateX() {
        place("U1", "builtin.74hc04", Map.of());
        place("A", "builtin.logic-input", Map.of("level", "high"));
        place("U2", "builtin.logic-nand", Map.of());
        place("Z", "builtin.logic-input", Map.of("level", "low"));
        place("U3", "builtin.logic-and", Map.of());
        connect("IN", "A.1", "U1.1A");
        connect("OUT", "U1.1Y", "U2.A");
        connect("ZERO", "Z.1", "U3.A");
        connect("ANDED", "U3.Y", "U2.B");
        var result = logic.simulate(project, 1e-6, List.of());
        assertThat(result.levels()).containsEntry("OUT", "X").containsEntry("ANDED", "0");
    }

    @Test
    void dFlipFlopWiredAsToggleDividesTheClockByTwo() {
        powered("U1", "builtin.74hc74");
        place("CK", "builtin.logic-clock", Map.of("frequency", "1k"));
        place("PWR2", "builtin.power-rail", Map.of());
        connect("CLK", "CK.1", "U1.1CLK");
        connect("FB", "U1.1QN", "U1.1D");
        place("P", "builtin.logic-not", Map.of());
        connect("Q", "U1.1Q", "P.A");
        connect("SET", "PWR2.1", "U1.1PRE", "U1.1CLR");
        var result = logic.simulate(project, 4.2e-3, List.of());
        var q = result.series().get("Q");
        assertThat(q.getFirst()).isEqualTo(new LogicSimulator.Transition(0, "0"));
        // Rising edges at 0.5, 1.5, 2.5, 3.5 ms; Q follows 10 ns later.
        assertThat(q.subList(1, q.size())).extracting(LogicSimulator.Transition::level)
                .containsExactly("1", "0", "1", "0");
        assertThat(q.get(1).timeS()).isCloseTo(0.5e-3 + 10e-9, within(1e-12));
        assertThat(q.get(2).timeS()).isCloseTo(1.5e-3 + 10e-9, within(1e-12));
        assertThat(result.series().get("CLK")).hasSize(9);
    }

    @Test
    void clearOverridesTheClockAndUnconnectedPresetIsUnknown() {
        powered("U1", "builtin.74hc74");
        place("CK", "builtin.logic-clock", Map.of("frequency", "1k"));
        place("LOW", "builtin.logic-input", Map.of("level", "low"));
        connect("CLK", "CK.1", "U1.2CLK");
        connect("CLR", "LOW.1", "U1.1CLR");
        connect("Q1", "U1.1Q", "U1.1D");
        connect("Q2", "U1.2Q", "U1.2D");
        var result = logic.simulate(project, 2e-3, List.of());
        // 1PRE floats: with CLR low the flip-flop is still cleared; 2PRE/2CLR float so unit 2 is unknown.
        assertThat(result.levels()).containsEntry("Q1", "0").containsEntry("Q2", "X");
    }

    @Test
    void pullUpReadsHighUntilTheSwitchClosesToGround() {
        place("PWR", "builtin.power-rail", Map.of());
        place("R1", "builtin.resistor", Map.of("resistance", "10k"));
        place("SW1", "builtin.switch-spst", Map.of());
        place("G", "builtin.ground", Map.of());
        place("U1", "builtin.logic-not", Map.of());
        place("U2", "builtin.logic-not", Map.of());
        connect("VCC", "PWR.1", "R1.1");
        connect("BTN", "R1.2", "SW1.1", "U1.A");
        connect("0", "SW1.2", "G.1");
        connect("NBTN", "U1.Y", "U2.A");
        var result = logic.simulate(project, 3e-3, List.of(new SwitchEvent("SW1", 1e-3, "closed"),
                new SwitchEvent("SW1", 2e-3, "open")));
        assertThat(result.series().get("BTN")).containsExactly(new LogicSimulator.Transition(0, "1"),
                new LogicSimulator.Transition(1e-3, "0"), new LogicSimulator.Transition(2e-3, "1"));
        var inverted = result.series().get("NBTN");
        assertThat(inverted).extracting(LogicSimulator.Transition::level).containsExactly("0", "1", "0");
        assertThat(inverted.get(1).timeS()).isCloseTo(1e-3 + 10e-9, within(1e-15));
        assertThat(inverted.get(2).timeS()).isCloseTo(2e-3 + 10e-9, within(1e-15));
    }

    @Test
    void contendingOutputsResolveToUnknown() {
        place("H", "builtin.logic-input", Map.of("level", "high"));
        place("L", "builtin.logic-input", Map.of("level", "low"));
        place("U1", "builtin.logic-not", Map.of());
        connect("BUS", "H.1", "L.1", "U1.A");
        var result = logic.simulate(project, 1e-6, List.of());
        assertThat(result.levels().get("BUS")).isEqualTo("X");
    }

    @Test
    void ringOscillatorTogglesEverySixGateDelays() {
        for (int i = 1; i <= 3; i++) {
            place("U" + i, "builtin.logic-not", Map.of("propagationDelay", "1n"));
        }
        connect("N1", "U1.Y", "U2.A");
        connect("N2", "U2.Y", "U3.A");
        connect("N3", "U3.Y", "U1.A");
        var result = logic.simulate(project, 100e-9, List.of());
        assertThat(result.warnings()).anyMatch(w -> w.contains("did not settle"));
        var n1 = result.series().get("N1");
        assertThat(n1.size()).isBetween(25, 40);
        double halfPeriod = n1.get(n1.size() - 1).timeS() - n1.get(n1.size() - 2).timeS();
        assertThat(halfPeriod).isCloseTo(3e-9, within(1e-12));
    }

    @Test
    void runawayEventCountsAreRejected() {
        place("U1", "builtin.logic-not", Map.of("propagationDelay", "1p"));
        connect("N", "U1.Y", "U1.A");
        assertThatThrownBy(() -> logic.simulate(project, 1e-3, List.of()))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("SIM_TOO_LARGE"));
        assertThatThrownBy(() -> logic.simulate(project, 0, List.of()))
                .isInstanceOfSatisfying(DesignException.class,
                        e -> assertThat(e.code()).isEqualTo("SIM_INVALID_REQUEST"));
    }

    @Test
    void transistorsAreIgnoredWithAWarning() {
        place("Q1", "builtin.npn", Map.of());
        place("A", "builtin.logic-input", Map.of("level", "high"));
        place("U1", "builtin.logic-not", Map.of());
        connect("IN", "A.1", "U1.A", "Q1.B");
        var result = logic.simulate(project, 1e-6, List.of());
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("Q1 (npn)"));
    }

    @Test
    void analogOperatingPointDrivesLoadsThroughTheBehaviouralOutput() {
        powered("U1", "builtin.74hc04");
        place("R1", "builtin.resistor", Map.of("resistance", "950"));
        place("G2", "builtin.ground", Map.of());
        connect("OUT", "U1.1Y", "R1.1");
        connect("IN", "U1.1A", "G2.1", "R1.2");
        var result = new CircuitSimulator().simulate(project, Options.operatingPoint());
        assertThat(result.nodeVoltages().get("OUT")).isCloseTo(5 * 950 / 1000.0, within(1e-6));
        assertThat(result.pinCurrents().get("U1").get("2")).isCloseTo(-5e-3, within(1e-8));
        assertThat(result.pinCurrents().get("U1").get("14")).isCloseTo(5e-3, within(1e-8));
        assertThat(result.warnings()).noneMatch(w -> w.contains("U1."));
    }

    @Test
    void analogTransientClocksAVirtualInverter() {
        place("CK", "builtin.logic-clock", Map.of("frequency", "1k"));
        place("U1", "builtin.logic-not", Map.of());
        place("R1", "builtin.resistor", Map.of("resistance", "1Meg"));
        place("G", "builtin.ground", Map.of());
        connect("CLK", "CK.1", "U1.A");
        connect("OUT", "U1.Y", "R1.1");
        connect("0", "R1.2", "G.1");
        var result = new CircuitSimulator().simulate(project, new Options("transient", 2e-3, 1e-5, false,
                List.of()));
        int quarter = result.time().indexOf(result.time().stream().filter(t -> t >= 0.25e-3).findFirst().orElseThrow());
        int threeQuarter = result.time().indexOf(result.time().stream().filter(t -> t >= 0.8e-3).findFirst()
                .orElseThrow());
        assertThat(result.voltageSeries().get("OUT").get(quarter)).isCloseTo(5, within(1e-3));
        assertThat(result.voltageSeries().get("OUT").get(threeQuarter)).isCloseTo(0, within(1e-3));
        assertThat(result.voltageSeries().get("CLK").get(threeQuarter)).isCloseTo(5, within(1e-3));
    }

    /** Places an IC with VCC on a 5 V rail and GND on ground. */
    private void powered(String reference, String definition) {
        place(reference, definition, Map.of());
        place("PWR_" + reference, "builtin.power-rail", Map.of());
        place("GND_" + reference, "builtin.ground", Map.of());
        connect("VCC", "PWR_" + reference + ".1", reference + ".VCC");
        connect("GND", "GND_" + reference + ".1", reference + ".GND");
    }

    private void place(String reference, String definition, Map<String, String> properties) {
        project = editor.place(project, project.sheets().getFirst().id(), library.get(definition), reference,
                project.components().size() * 20.0, 0, 0, properties);
    }

    private void connect(String name, String... pins) {
        project = editor.connect(project, name, java.util.Arrays.stream(pins).map(pin -> {
            int dot = pin.lastIndexOf('.');
            return project.component(pin.substring(0, dot)).pin(pin.substring(dot + 1)).id();
        }).toList());
    }
}
