package net.talaatharb.expanse.simulation;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.schematic.SchematicEditor;
import org.junit.jupiter.api.Test;

class SpiceTest {
    private final ComponentLibrary library = new ComponentLibrary();
    private final SchematicEditor editor = new SchematicEditor();
    private Project project = Project.create("Mixed bag");

    @Test
    void exportUsesSimulatorEquivalentsForEveryAnalogPart() {
        place("V1", "builtin.dc-source", Map.of("voltage", "9"));
        place("P1", "builtin.power-rail", Map.of("voltage", "12"));
        place("G1", "builtin.ground", Map.of());
        place("R1", "builtin.resistor", Map.of("resistance", "470"));
        place("R2", "builtin.resistor", Map.of("resistance", "1.5M"));
        place("D1", "builtin.led", Map.of());
        place("Q1", "builtin.npn", Map.of());
        place("S1", "builtin.switch-spst", Map.of("state", "closed"));
        place("M1", "builtin.nmos", Map.of());
        place("U1", "builtin.opamp", Map.of());
        place("U2", "builtin.logic-not", Map.of());
        connect("VCC", "V1.+", "R1.1", "M1.D");
        connect("GND", "V1.-", "G1.GND", "Q1.E", "M1.S", "R2.2");
        connect("RAIL", "P1.1", "S1.1");
        connect("BASE", "S1.2", "Q1.B", "M1.G", "U1.IN+", "R2.1");
        connect("LED A", "R1.2", "D1.A");
        connect("COLL", "D1.K", "Q1.C", "U1.IN-");

        var export = new SpiceExporter().export(project);
        assertThat(export.netlist().lines()).startsWith("Mixed bag").contains(
                "V1 VCC 0 DC 9",
                "VP1 RAIL 0 DC 12",
                "R1 VCC LED_A 470",
                "R2 BASE 0 1.5Meg",
                "D1 LED_A COLL D_D1",
                "Q1 COLL BASE 0 Q_Q1",
                "RS1 RAIL BASE 10m",
                "M1 VCC BASE 0 0 M_M1",
                "EU1 U1_int 0 BASE COLL 100k",
                "RU1_out U1_int NC_U1_6 75",
                ".model Q_Q1 NPN(BF=100 IS=10f)",
                ".model M_M1 NMOS(LEVEL=1 VTO=2 KP=100m)",
                ".op").endsWith(".end");
        assertThat(export.warnings()).hasSize(2).anyMatch(w -> w.startsWith("U2")).anyMatch(w -> w.startsWith("U1"));

        var parsed = new SpiceParser().parse(export.netlist());
        assertThat(parsed.title()).isEqualTo("Mixed bag");
        assertThat(parsed.elements()).extracting(SpiceParser.Element::name)
                .containsExactly("V1", "VP1", "R1", "R2", "D1", "Q1", "RS1", "M1", "RU1_out");
        assertThat(parsed.warnings()).singleElement().asString().startsWith("Skipped EU1");
        double ledIs = parsed.model("d_d1").parameter("IS");
        double vt = CircuitSimulator.VT;
        assertThat(ledIs * Math.exp(2 / (2 * vt))).isCloseTo(CircuitSimulator.LED_REFERENCE_CURRENT,
                withinPercentage(0.1));
        assertThat(parsed.elements().get(3).value()).isEqualTo(1.5e6);
    }

    @Test
    void parserHandlesContinuationsCommentsSuffixesAndSourceForms() {
        var netlist = new SpiceParser().parse("""
                * RC test bench
                V1 in 0 PULSE(0 5 0 1n 1n 1u 2u) ; transient only
                V2 vcc 0 dc 3.3
                R1 in out 4.7kohm
                + $ continued comment
                C1 out 0 10uF
                L1 out x 2.2MH
                R2 x 0 1MEG
                Q1 out x 0 sub QMOD
                X1 a b sub1
                .model QMOD pnp (bf=50
                + is=1e-14)
                .tran 1u 1m
                .include parts.lib
                .end
                R9 never parsed 1
                """);
        assertThat(netlist.title()).isEqualTo("RC test bench");
        assertThat(netlist.elements()).extracting(SpiceParser.Element::name)
                .containsExactly("V1", "V2", "R1", "C1", "L1", "R2", "Q1");
        assertThat(netlist.elements()).extracting(SpiceParser.Element::value)
                .containsExactly(0.0, 3.3, 4700.0, 1e-5, 2.2e-3, 1e6, null);
        assertThat(netlist.elements().get(6).model()).isEqualTo("QMOD");
        assertThat(netlist.elements().get(6).nodes()).containsExactly("out", "x", "0");
        assertThat(netlist.model("qmod").type()).isEqualTo("PNP");
        assertThat(netlist.model("qmod").parameters()).containsEntry("BF", 50.0).containsEntry("IS", 1e-14);
        assertThat(netlist.warnings()).hasSize(3)
                .anyMatch(w -> w.startsWith("V1 has no DC value"))
                .anyMatch(w -> w.startsWith("Skipped X1"))
                .anyMatch(w -> w.contains(".include"));
        assertThat(SpiceParser.number("10mil")).isCloseTo(254e-6, within(1e-12));
        assertThat(SpiceParser.number("1.5Meg")).isEqualTo(1.5e6);
        assertThat(SpiceParser.number("3m")).isEqualTo(3e-3);
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
