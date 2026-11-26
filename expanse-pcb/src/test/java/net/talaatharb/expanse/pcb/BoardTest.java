package net.talaatharb.expanse.pcb;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.schematic.SchematicEditor;
import org.junit.jupiter.api.Test;

class BoardTest {
    private final ComponentLibrary library = new ComponentLibrary();
    private final SchematicEditor editor = new SchematicEditor();
    private final BoardEditor board = new BoardEditor();
    private final BoardAnalyzer analyzer = new BoardAnalyzer();
    private Project project = Project.create("pcb");

    @Test
    void syncPlacesOnlyPhysicalPartsWithoutOverlapAndRoutingClearsTheRatsnest() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        place("Q1", "builtin.npn");
        place("G1", "builtin.ground");
        connect("A", "R1.2", "R2.1");
        connect("B", "R2.2", "Q1.B");
        connect("GND", "Q1.E", "G1.1");
        project = board.syncFromSchematic(project);
        assertThat(project.board().placements()).hasSize(3);
        var report = analyzer.analyze(project);
        assertThat(report.findings()).extracting(BoardAnalyzer.Finding::code)
                .containsOnly("PCB_UNROUTED").hasSize(2);
        assertThat(report.airwires()).hasSize(2);
        assertThat(report.pads()).hasSize(7);

        project = board.route(project, UUID.randomUUID(), pin("R1.2"), pin("R2.1"), "top", 0.4);
        project = board.route(project, UUID.randomUUID(), pin("R2.2"), pin("Q1.B"), "bottom", 0.3);
        report = analyzer.analyze(project);
        assertThat(report.airwires()).isEmpty();
        assertThat(report.findings()).extracting(BoardAnalyzer.Finding::code).doesNotContain("PCB_UNROUTED");
        assertThat(board.syncFromSchematic(project).board()).isEqualTo(project.board());
    }

    @Test
    void routingRejectsPadsOfDifferentNetsAndUnplacedParts() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        connect("A", "R1.1", "R2.1");
        assertThatThrownBy(() -> board.route(project, UUID.randomUUID(), pin("R1.1"), pin("R2.1"), "top", 0.4))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("PAD_NOT_PLACED"));
        project = board.syncFromSchematic(project);
        assertThatThrownBy(() -> board.route(project, UUID.randomUUID(), pin("R1.1"), pin("R2.2"), "top", 0.4))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("PCB_NOT_SAME_NET"));
        place("G1", "builtin.ground");
        assertThatThrownBy(() -> board.place(project, project.component("G1").id(), 10, 10, 0))
                .isInstanceOf(DesignException.class);
    }

    @Test
    void drcReportsOutlineOverlapClearanceAndShorts() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        connect("A", "R1.1", "R2.1");
        connect("B", "R1.2", "R2.2");
        project = board.place(project, project.component("R1").id(), 20, 20, 0);
        project = board.place(project, project.component("R2").id(), 20, 21, 180);
        assertThat(codes()).contains("PCB_COURTYARD_OVERLAP", "PCB_SHORT");

        project = board.place(project, project.component("R2").id(), 79, 40, 90);
        assertThat(codes()).contains("PCB_OUTSIDE_BOARD").doesNotContain("PCB_COURTYARD_OVERLAP");

        project = board.place(project, project.component("R2").id(), 40, 40, 0);
        UUID netA = project.nets().stream().filter(n -> n.name().equals("A")).findFirst().orElseThrow().id();
        project = board.addTrace(project, UUID.randomUUID(), netA, "top", 0.4,
                List.of(new Point(14.92, 20.5), new Point(14.92, 40), new Point(34.92, 40)));
        assertThat(codes()).doesNotContain("PCB_CLEARANCE", "PCB_SHORT");
        project = board.addTrace(project, UUID.randomUUID(), netA, "top", 0.4,
                List.of(new Point(25.08, 21.1), new Point(30, 21.1)));
        assertThat(codes()).contains("PCB_CLEARANCE");
        project = board.addTrace(project, UUID.randomUUID(), netA, "top", 0.4,
                List.of(new Point(90, 10), new Point(95, 10)));
        assertThat(codes()).contains("PCB_TRACE_OUTSIDE_BOARD");
    }

    @Test
    void deletingComponentsAndNetsRemovesTheirPlacementsAndTraces() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        connect("A", "R1.1", "R2.1");
        project = board.syncFromSchematic(project);
        UUID trace = UUID.randomUUID();
        project = board.route(project, trace, pin("R1.1"), pin("R2.1"), "top", 0.4);
        project = editor.delete(project, project.component("R2").id());
        assertThat(project.board().placements()).hasSize(1);
        assertThat(project.board().traces()).isEmpty();
        assertThatThrownBy(() -> board.deleteTrace(project, trace)).isInstanceOf(DesignException.class);
    }

    @Test
    void rotationTurnsPadsCounterClockwise() {
        assertThat(PcbGeometry.rotate(1, 0, 90)).containsExactly(0, 1);
        assertThat(PcbGeometry.rotate(1, 0, 180)).containsExactly(-1, 0);
        assertThat(PcbGeometry.rotate(1, 0, 270)).containsExactly(0, -1);
    }

    private List<String> codes() {
        return analyzer.analyze(project).findings().stream().map(BoardAnalyzer.Finding::code).toList();
    }

    private void place(String reference, String definition) {
        project = editor.place(project, project.sheets().getFirst().id(), library.get(definition), reference,
                project.components().size() * 20.0, 0, 0, Map.of());
    }

    private UUID pin(String selector) {
        int dot = selector.lastIndexOf('.');
        return project.component(selector.substring(0, dot)).pin(selector.substring(dot + 1)).id();
    }

    private void connect(String name, String... pins) {
        project = editor.connect(project, name, java.util.Arrays.stream(pins).map(this::pin).toList());
    }
}
