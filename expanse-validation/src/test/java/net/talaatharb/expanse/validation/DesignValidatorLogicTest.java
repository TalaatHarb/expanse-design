package net.talaatharb.expanse.validation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.schematic.SchematicEditor;
import net.talaatharb.expanse.validation.DesignValidator.Finding;
import org.junit.jupiter.api.Test;

class DesignValidatorLogicTest {
    private final ComponentLibrary library = new ComponentLibrary();
    private final SchematicEditor editor = new SchematicEditor();
    private Project project = Project.create("erc");

    @Test
    void wellFormedLogicCircuitHasNoLogicFindings() {
        place("U1", "builtin.74hc00");
        place("PWR", "builtin.power-rail");
        place("G", "builtin.ground");
        place("A", "builtin.logic-input");
        place("B", "builtin.logic-clock");
        place("U2", "builtin.logic-not");
        connect("VCC", "PWR.1", "U1.VCC");
        connect("GND", "G.1", "U1.GND");
        connect("IN_A", "A.1", "U1.1A");
        connect("IN_B", "B.1", "U1.1B");
        connect("Y", "U1.1Y", "U2.A");
        connect("NY", "U2.Y", "U1.2A", "U1.2B");
        assertThat(codes()).noneMatch(code -> code.startsWith("LOGIC_"));
    }

    @Test
    void reportsConflictsFloatingAndUndrivenInputsAndMissingSupply() {
        place("U1", "builtin.74hc08");
        place("A", "builtin.logic-input");
        place("B", "builtin.logic-input");
        place("U2", "builtin.logic-or");
        place("U3", "builtin.logic-not");
        place("PWR", "builtin.power-rail");
        connect("BUS", "A.1", "B.1", "U1.1A");
        connect("Y", "U1.1Y", "U2.A");
        connect("FLOAT", "U2.B", "U3.A");
        connect("TIED", "U3.Y", "PWR.1");
        var findings = new DesignValidator().validate(project);
        assertThat(findings).filteredOn(f -> f.code().equals("LOGIC_OUTPUT_CONFLICT")).extracting(Finding::message)
                .containsExactlyInAnyOrder("Net BUS is driven by more than one logic output or supply.",
                        "Net TIED is driven by more than one logic output or supply.");
        assertThat(findings).filteredOn(f -> f.code().equals("LOGIC_INPUT_FLOATING")).extracting(Finding::message)
                .containsExactly("U1 pin 2 (1B) drives a used gate but is unconnected; tie it high or low.");
        assertThat(findings).filteredOn(f -> f.code().equals("LOGIC_INPUT_UNDRIVEN")).extracting(Finding::message)
                .containsExactlyInAnyOrder("U2 pin 2 (B) is on net FLOAT which has no driver.",
                        "U3 pin 1 (A) is on net FLOAT which has no driver.");
        assertThat(codes()).contains("LOGIC_IC_UNPOWERED");
    }

    @Test
    void unusedGatesInsideAnIcAreNotReported() {
        place("U1", "builtin.74hc04");
        place("PWR", "builtin.power-rail");
        place("G", "builtin.ground");
        connect("VCC", "PWR.1", "U1.VCC");
        connect("GND", "G.1", "U1.GND");
        assertThat(codes()).noneMatch(code -> code.startsWith("LOGIC_"));
    }

    @Test
    void feedbackInsideOneIcIsNotASingleComponentNetButAResistorLoopIs() {
        place("U1", "builtin.74hc74");
        place("R1", "builtin.resistor");
        connect("FB", "U1.1QN", "U1.1D");
        connect("LOOP", "R1.1", "R1.2");
        assertThat(new DesignValidator().validate(project))
                .filteredOn(f -> f.code().equals("ERC_SINGLE_COMPONENT_NET")).extracting(Finding::message)
                .containsExactly("Net LOOP only connects pins of one component.");
    }

    private List<String> codes() {
        return new DesignValidator().validate(project).stream().map(Finding::code).toList();
    }

    private void place(String reference, String definition) {
        project = editor.place(project, project.sheets().getFirst().id(), library.get(definition), reference,
                project.components().size() * 20.0, 0, 0, Map.of());
    }

    private void connect(String name, String... pins) {
        project = editor.connect(project, name, java.util.Arrays.stream(pins).map(pin -> {
            int dot = pin.lastIndexOf('.');
            return project.component(pin.substring(0, dot)).pin(pin.substring(dot + 1)).id();
        }).toList());
    }
}
