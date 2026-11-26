package net.talaatharb.expanse.assembly;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.pcb.BoardEditor;
import net.talaatharb.expanse.schematic.SchematicEditor;
import org.junit.jupiter.api.Test;

class AssemblyBuilderTest {
    private final ComponentLibrary library = new ComponentLibrary();
    private final SchematicEditor editor = new SchematicEditor();
    private final BoardEditor board = new BoardEditor();
    private final AssemblyBuilder builder = new AssemblyBuilder();

    @Test
    void sceneContainsBoardBodiesPadsAndTracesAndDetectsCollisions() {
        Project project = Project.create("3d");
        for (String[] part : new String[][]{{"R1", "builtin.resistor"}, {"C1", "builtin.capacitor"},
                {"Q1", "builtin.npn"}, {"G1", "builtin.ground"}}) {
            project = editor.place(project, project.sheets().getFirst().id(), library.get(part[1]), part[0], 0, 0, 0,
                    Map.of());
        }
        var r1 = project.component("R1");
        var c1 = project.component("C1");
        project = editor.connect(project, "A", java.util.List.of(r1.pin("1").id(), c1.pin("1").id()));
        project = board.syncFromSchematic(project);
        project = board.route(project, UUID.randomUUID(), r1.pin("1").id(), c1.pin("1").id(), "top", 0.4);

        var scene = builder.build(project);
        assertThat(scene.solids()).filteredOn(s -> s.role().equals("body")).hasSize(3);
        assertThat(scene.solids()).filteredOn(s -> s.role().equals("board")).singleElement()
                .satisfies(b -> assertThat(b.zMm()).isEqualTo(-0.8));
        assertThat(scene.solids()).filteredOn(s -> s.role().equals("pad")).hasSize(14);
        assertThat(scene.solids()).anyMatch(s -> s.role().equals("trace"));
        assertThat(scene.maxHeightMm()).isEqualTo(11);
        assertThat(scene.findings()).isEmpty();

        project = board.place(project, c1.id(), project.board().placements().stream()
                .filter(p -> p.componentId().equals(r1.id())).findFirst().orElseThrow().xMm(),
                project.board().placements().stream().filter(p -> p.componentId().equals(r1.id())).findFirst()
                        .orElseThrow().yMm(), 0);
        assertThat(builder.build(project).findings()).extracting(AssemblyBuilder.Finding::code)
                .contains("ASM_BODY_COLLISION");
        project = board.place(project, c1.id(), 79, 30, 0);
        assertThat(builder.build(project).findings()).extracting(AssemblyBuilder.Finding::code)
                .contains("ASM_BODY_OVERHANG");
    }
}
