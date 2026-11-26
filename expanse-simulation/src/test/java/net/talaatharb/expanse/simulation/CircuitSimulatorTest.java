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

class CircuitSimulatorTest {
    private final ComponentLibrary library = new ComponentLibrary();
    private final SchematicEditor editor = new SchematicEditor();
    private final CircuitSimulator simulator = new CircuitSimulator();
    private Project project = Project.create("sim");

    @Test
    void voltageDividerOperatingPointReportsVoltagesCurrentsAndPower() {
        place("V1", "builtin.dc-source", Map.of("voltage", "10"));
        place("R1", "builtin.resistor", Map.of("resistance", "1k"));
        place("R2", "builtin.resistor", Map.of("resistance", "1kΩ"));
        place("G1", "builtin.ground", Map.of());
        connect("VIN", "V1.+", "R1.1");
        connect("MID", "R1.2", "R2.1");
        connect("0", "R2.2", "V1.-", "G1.GND");
        var result = simulator.simulate(project, Options.operatingPoint());
        assertThat(result.nodeVoltages().get("MID")).isCloseTo(5.0, within(1e-6));
        assertThat(result.nodeVoltages().get("VIN")).isCloseTo(10.0, within(1e-6));
        assertThat(result.pinCurrents().get("R1").get("1")).isCloseTo(5e-3, within(1e-8));
        assertThat(result.pinCurrents().get("V1").get("1")).isCloseTo(-5e-3, within(1e-8));
        assertThat(result.componentPower().get("R1")).isCloseTo(25e-3, within(1e-7));
        assertThat(result.componentPower().get("V1")).isCloseTo(-50e-3, within(1e-7));
    }

    @Test
    void rcChargeMatchesAnalyticResponseAtOneTimeConstant() {
        place("V1", "builtin.dc-source", Map.of("voltage", "5"));
        place("R1", "builtin.resistor", Map.of("resistance", "1k"));
        place("C1", "builtin.capacitor", Map.of("capacitance", "1u"));
        place("G1", "builtin.ground", Map.of());
        connect("VIN", "V1.+", "R1.1");
        connect("OUT", "R1.2", "C1.1");
        connect("GND", "C1.2", "V1.-", "G1.1");
        var result = simulator.simulate(project, new Options("transient", 5e-3, 1e-6, true, List.of()));
        assertThat(valueAt(result, "OUT", 1e-3)).isCloseTo(5 * (1 - Math.exp(-1)), withinPercentage(2));
        assertThat(result.nodeVoltages().get("OUT")).isCloseTo(5 * (1 - Math.exp(-5)), withinPercentage(2));
    }

    @Test
    void operatingPointTreatsCapacitorsAsOpenAndInductorsAsShort() {
        place("V1", "builtin.dc-source", Map.of("voltage", "5"));
        place("R1", "builtin.resistor", Map.of("resistance", "100"));
        place("L1", "builtin.inductor", Map.of("inductance", "10m"));
        place("C1", "builtin.capacitor", Map.of());
        place("G1", "builtin.ground", Map.of());
        connect("VIN", "V1.+", "R1.1");
        connect("X", "R1.2", "L1.1", "C1.1");
        connect("GND", "L1.2", "C1.2", "V1.-", "G1.1");
        var op = simulator.simulate(project, Options.operatingPoint());
        assertThat(op.nodeVoltages().get("X")).isCloseTo(0, within(1e-6));
        assertThat(op.pinCurrents().get("L1").get("1")).isCloseTo(0.05, within(1e-6));
        assertThat(op.pinCurrents().get("C1").get("1")).isCloseTo(0, within(1e-9));
    }

    @Test
    void rlCurrentRisesWithTheInductiveTimeConstant() {
        place("V1", "builtin.dc-source", Map.of("voltage", "5"));
        place("R1", "builtin.resistor", Map.of("resistance", "100"));
        place("L1", "builtin.inductor", Map.of("inductance", "10m"));
        place("G1", "builtin.ground", Map.of());
        connect("VIN", "V1.+", "R1.1");
        connect("X", "R1.2", "L1.1");
        connect("GND", "L1.2", "V1.-", "G1.1");
        var result = simulator.simulate(project, new Options("transient", 1e-3, 1e-7, true, List.of()));
        assertThat(seriesAt(result, result.currentSeries().get("L1.1"), 1e-4))
                .isCloseTo(0.05 * (1 - Math.exp(-1)), withinPercentage(2));
    }

    @Test
    void switchEventsChangeStateDuringTransient() {
        place("V1", "builtin.dc-source", Map.of("voltage", "5"));
        place("S1", "builtin.switch-spst", Map.of());
        place("R1", "builtin.resistor", Map.of("resistance", "1k"));
        place("G1", "builtin.ground", Map.of());
        connect("VIN", "V1.+", "S1.1");
        connect("LOAD", "S1.2", "R1.1");
        connect("GND", "R1.2", "V1.-", "G1.1");
        assertThat(simulator.simulate(project, Options.operatingPoint()).nodeVoltages().get("LOAD"))
                .isCloseTo(0, within(1e-6));
        var result = simulator.simulate(project, new Options("transient", 2e-3, 1e-5, false,
                List.of(new SwitchEvent("S1", 1e-3, "closed"))));
        assertThat(valueAt(result, "LOAD", 0.5e-3)).isCloseTo(0, within(1e-6));
        assertThat(valueAt(result, "LOAD", 1.5e-3)).isCloseTo(5, within(1e-3));
        project = editor.setProperties(project, project.component("S1").id(), Map.of("state", "closed"));
        assertThat(simulator.simulate(project, Options.operatingPoint()).nodeVoltages().get("LOAD"))
                .isCloseTo(5, within(1e-3));
        assertThatThrownBy(() -> simulator.simulate(project, new Options("transient", 1e-3, 1e-5, false,
                List.of(new SwitchEvent("R1", 0, "open"))))).isInstanceOf(DesignException.class);
    }

    @Test
    void npnCommonEmitterBiasesIntoActiveRegion() {
        place("VCC", "builtin.power-rail", Map.of("voltage", "5"));
        place("RB", "builtin.resistor", Map.of("resistance", "220k"));
        place("RC", "builtin.resistor", Map.of("resistance", "1k"));
        place("Q1", "builtin.npn", Map.of());
        place("G1", "builtin.ground", Map.of());
        connect("VCC", "VCC.VCC", "RB.1", "RC.1");
        connect("BASE", "RB.2", "Q1.B");
        connect("COLLECTOR", "RC.2", "Q1.C");
        connect("GND", "Q1.E", "G1.GND");
        var result = simulator.simulate(project, Options.operatingPoint());
        assertThat(result.nodeVoltages().get("BASE")).isBetween(0.6, 0.75);
        assertThat(result.nodeVoltages().get("COLLECTOR")).isBetween(2.85, 3.15);
        var q = result.pinCurrents().get("Q1");
        assertThat(q.get("3") / q.get("2")).isCloseTo(100, withinPercentage(2));
        assertThat(q.get("1") + q.get("2") + q.get("3")).isCloseTo(0, within(1e-9));
    }

    @Test
    void pnpHighSideBiasMirrorsTheNpnCase() {
        place("VCC", "builtin.power-rail", Map.of("voltage", "5"));
        place("RB", "builtin.resistor", Map.of("resistance", "220k"));
        place("RC", "builtin.resistor", Map.of("resistance", "1k"));
        place("Q1", "builtin.pnp", Map.of());
        place("G1", "builtin.ground", Map.of());
        connect("VCC", "VCC.1", "Q1.E");
        connect("BASE", "RB.1", "Q1.B");
        connect("COLLECTOR", "RC.1", "Q1.C");
        connect("GND", "RB.2", "RC.2", "G1.1");
        var result = simulator.simulate(project, Options.operatingPoint());
        assertThat(result.nodeVoltages().get("COLLECTOR")).isBetween(1.85, 2.15);
    }

    @Test
    void mosfetsSwitchLoadsByGateVoltage() {
        place("VCC", "builtin.power-rail", Map.of("voltage", "5"));
        place("RD", "builtin.resistor", Map.of("resistance", "1k"));
        place("M1", "builtin.nmos", Map.of());
        place("G1", "builtin.ground", Map.of());
        connect("VCC", "VCC.1", "RD.1", "M1.G");
        connect("DRAIN", "RD.2", "M1.D");
        connect("GND", "M1.S", "G1.1");
        assertThat(simulator.simulate(project, Options.operatingPoint()).nodeVoltages().get("DRAIN")).isLessThan(0.1);
        project = editor.disconnect(project, project.component("M1").pin("G").id());
        project = editor.connect(project, "GND", List.of(project.component("M1").pin("G").id(),
                project.component("M1").pin("S").id()));
        assertThat(simulator.simulate(project, Options.operatingPoint()).nodeVoltages().get("DRAIN"))
                .isCloseTo(5, within(1e-3));

        project = Project.create("pmos");
        place("VCC", "builtin.power-rail", Map.of("voltage", "5"));
        place("RL", "builtin.resistor", Map.of("resistance", "1k"));
        place("M2", "builtin.pmos", Map.of());
        place("G1", "builtin.ground", Map.of());
        connect("VCC", "VCC.1", "M2.S");
        connect("OUT", "M2.D", "RL.1");
        connect("GND", "RL.2", "M2.G", "G1.1");
        assertThat(simulator.simulate(project, Options.operatingPoint()).nodeVoltages().get("OUT")).isGreaterThan(4.9);
    }

    @Test
    void invalidCircuitsAndRequestsAreRejectedWithCodes() {
        place("R1", "builtin.resistor", Map.of());
        place("R2", "builtin.resistor", Map.of());
        connect("A", "R1.1", "R2.1");
        assertThatThrownBy(() -> simulator.simulate(project, Options.operatingPoint()))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("SIM_NO_GROUND"));
        place("G1", "builtin.ground", Map.of());
        connect("A", "R1.1", "G1.1");
        assertThatThrownBy(() -> simulator.simulate(project, new Options("transient", 1, 2, false, List.of())))
                .isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> simulator.simulate(project, new Options("transient", 1, 1e-6, false, List.of())))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("SIM_TOO_LARGE"));
        assertThat(simulator.simulate(project, Options.operatingPoint()).warnings()).isNotEmpty();
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

    private static double valueAt(CircuitSimulator.Result result, String node, double t) {
        return seriesAt(result, result.voltageSeries().get(node), t);
    }

    private static double seriesAt(CircuitSimulator.Result result, List<Double> series, double t) {
        int best = 0;
        for (int i = 0; i < result.time().size(); i++) {
            if (Math.abs(result.time().get(i) - t) < Math.abs(result.time().get(best) - t)) {
                best = i;
            }
        }
        return series.get(best);
    }
}
