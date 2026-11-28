package net.talaatharb.expanse.pcb;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Via;
import net.talaatharb.expanse.domain.Design.Zone;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.schematic.SchematicEditor;
import org.junit.jupiter.api.Test;

class CopperTest {
    private final ComponentLibrary library = new ComponentLibrary();
    private final SchematicEditor editor = new SchematicEditor();
    private final BoardEditor board = new BoardEditor();
    private final BoardAnalyzer analyzer = new BoardAnalyzer();
    private Project project = Project.create("Copper Demo");

    @Test
    void zoneFillConnectsSameNetPadsAndKeepsClearanceFromOtherNets() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        connect("GND", "R1.1", "R2.1");
        connect("SIG", "R1.2", "R2.2");
        project = board.place(project, project.component("R1").id(), 20, 20, 0);
        project = board.place(project, project.component("R2").id(), 20, 40, 0);
        assertThat(analyzer.analyze(project).airwires()).hasSize(2);

        UUID gnd = net("GND");
        project = board.addZone(project, new Zone(UUID.randomUUID(), gnd, "bottom",
                List.of(new Point(2, 2), new Point(78, 2), new Point(78, 58), new Point(2, 58)), 0.3));
        var report = analyzer.analyze(project);
        assertThat(report.airwires()).extracting(BoardAnalyzer.Airwire::netName).containsExactly("SIG");
        assertThat(report.findings()).extracting(BoardAnalyzer.Finding::code)
                .doesNotContain("PCB_SHORT", "PCB_CLEARANCE", "PCB_ZONE_EMPTY");

        var fill = report.fills().getFirst();
        assertThat(fill.islands()).hasSize(1);
        double clearance = 0.3;
        for (var pad : report.pads()) {
            if (pad.netId().equals(gnd)) {
                continue;
            }
            var padRect = new PcbGeometry.Rect(pad.xMm(), pad.yMm(), pad.widthMm(), pad.heightMm());
            for (var rect : fill.rects()) {
                assertThat(PcbGeometry.rectToRect(rect.rect(), padRect)).isGreaterThanOrEqualTo(clearance - 1e-9);
            }
        }
    }

    @Test
    void zoneWithoutSameNetCopperIsReportedEmpty() {
        place("R1", "builtin.resistor");
        connect("GND", "R1.1", "R1.2");
        project = board.place(project, project.component("R1").id(), 60, 50, 0);
        project = board.addZone(project, new Zone(UUID.randomUUID(), net("GND"), "top",
                List.of(new Point(2, 2), new Point(20, 2), new Point(20, 20)), 0.3));
        assertThat(codes()).contains("PCB_ZONE_EMPTY");
    }

    @Test
    void viasJoinTracesOnBothLayersAndAreCheckedForClearance() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        connect("A", "R1.1", "R2.1");
        connect("B", "R1.2", "R2.2");
        project = board.place(project, project.component("R1").id(), 20, 20, 0);
        project = board.place(project, project.component("R2").id(), 20, 40, 0);
        var r1 = pad("R1", "1");
        var r2 = pad("R2", "1");
        UUID a = net("A");
        project = board.addTrace(project, UUID.randomUUID(), a, "top", 0.4,
                List.of(new Point(r1.xMm(), r1.yMm()), new Point(r1.xMm() - 5, r1.yMm()),
                        new Point(r1.xMm() - 5, 30)));
        project = board.addTrace(project, UUID.randomUUID(), a, "bottom", 0.4,
                List.of(new Point(r2.xMm() - 5, 30), new Point(r2.xMm() - 5, r2.yMm()),
                        new Point(r2.xMm(), r2.yMm())));
        assertThat(analyzer.analyze(project).airwires()).extracting(BoardAnalyzer.Airwire::netName).contains("A");
        project = board.addVia(project, new Via(UUID.randomUUID(), a, r1.xMm() - 5, 30, 0.8, 0.4));
        assertThat(analyzer.analyze(project).airwires()).extracting(BoardAnalyzer.Airwire::netName)
                .containsExactly("B");

        project = board.addVia(project, new Via(UUID.randomUUID(), net("B"), r1.xMm() - 5.5, 30, 0.8, 0.4));
        assertThat(codes()).contains("PCB_SHORT");
        project = board.addVia(project, new Via(UUID.randomUUID(), a, 200, 30, 0.8, 0.4));
        assertThat(codes()).contains("PCB_VIA_OUTSIDE_BOARD");
        assertThatThrownBy(() -> board.deleteVia(project, UUID.randomUUID()))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("VIA_NOT_FOUND"));
    }

    @Test
    void autorouterRoutesEveryConnectionWithoutDesignRuleErrors() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        place("R3", "builtin.resistor");
        place("Q1", "builtin.npn");
        connect("A", "R1.2", "R2.1", "Q1.B");
        connect("B", "R2.2", "R3.1");
        connect("C", "R1.1", "R3.2", "Q1.C");
        project = board.syncFromSchematic(project);
        var routed = board.autoroute(project, new AutoRouter.Options(Set.of(), 0.4, 0.8, 0.4));
        assertThat(routed.result().unrouted()).isEmpty();
        assertThat(routed.result().routed()).isEqualTo(routed.result().attempted()).isEqualTo(5);
        project = routed.project();
        var report = analyzer.analyze(project);
        assertThat(report.airwires()).isEmpty();
        assertThat(report.findings()).filteredOn(f -> f.severity().equals("ERROR")).isEmpty();
        assertThat(report.findings()).extracting(BoardAnalyzer.Finding::code).doesNotContain("PCB_CLEARANCE");
        assertThat(board.autoroute(project, new AutoRouter.Options(Set.of(), 0.4, 0.8, 0.4)).result().attempted())
                .isZero();
    }

    @Test
    void autorouterUsesTheBottomLayerAndViasAroundBlockingCopper() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        connect("A", "R1.1", "R2.1");
        connect("WALL", "R1.2", "R2.2");
        project = board.setBoard(project, 60, 40, 1.6, 0.2);
        project = board.place(project, project.component("R1").id(), 15, 20, 0);
        project = board.place(project, project.component("R2").id(), 45, 20, 0);
        // A top-layer wall across the board forces net A onto the bottom layer.
        project = board.addTrace(project, UUID.randomUUID(), net("WALL"), "top", 1.0,
                List.of(new Point(30, 0.8), new Point(30, 39.2)));
        project = board.addTrace(project, UUID.randomUUID(), net("WALL"), "bottom", 1.0,
                List.of(new Point(30, 0.8), new Point(30, 19)));
        var routed = board.autoroute(project, new AutoRouter.Options(Set.of(net("A")), 0.3, 0.8, 0.4));
        assertThat(routed.result().unrouted()).isEmpty();
        assertThat(routed.result().traces()).extracting(t -> t.layer()).contains("bottom");
        var report = analyzer.analyze(routed.project());
        assertThat(report.airwires()).extracting(BoardAnalyzer.Airwire::netName).containsOnly("WALL");
        assertThat(report.findings()).extracting(BoardAnalyzer.Finding::code)
                .doesNotContain("PCB_SHORT", "PCB_CLEARANCE");
    }

    @Test
    void fabricationExportContainsGerbersDrillBomAndPlacement() {
        place("R1", "builtin.resistor");
        place("R2", "builtin.resistor");
        place("Q1", "builtin.npn");
        connect("A", "R1.1", "R2.1", "Q1.B");
        project = editor.setProperties(project, project.component("R2").id(), Map.of("resistance", "10k"));
        project = board.syncFromSchematic(project);
        project = board.autoroute(project, new AutoRouter.Options(Set.of(), 0.4, 0.8, 0.4)).project();
        UUID a = net("A");
        project = board.addVia(project, new Via(UUID.randomUUID(), a, 70, 50, 0.8, 0.4));
        project = board.addZone(project, new Zone(UUID.randomUUID(), a, "bottom",
                List.of(new Point(1, 1), new Point(79, 1), new Point(79, 59), new Point(1, 59)), 0.3));

        var files = new FabricationExporter().export(project);
        assertThat(files).extracting(FabricationExporter.FabricationFile::name).containsExactly(
                "Copper_Demo-F_Cu.gbr", "Copper_Demo-B_Cu.gbr", "Copper_Demo-F_Mask.gbr", "Copper_Demo-B_Mask.gbr",
                "Copper_Demo-F_SilkS.gbr", "Copper_Demo-Edge_Cuts.gbr", "Copper_Demo.drl", "Copper_Demo-BOM.csv",
                "Copper_Demo-positions.csv");
        Map<String, String> byName = new java.util.HashMap<>();
        files.forEach(f -> byName.put(f.name(), f.content()));

        String top = byName.get("Copper_Demo-F_Cu.gbr");
        assertThat(top).startsWith("%TF.GenerationSoftware").contains("%FSLAX46Y46*%", "%MOMM*%", "%ADD10")
                .endsWith("M02*\n");
        String bottom = byName.get("Copper_Demo-B_Cu.gbr");
        assertThat(bottom).contains("G36*", "G37*");
        assertThat(byName.get("Copper_Demo-F_Mask.gbr")).contains("%LPD*%");
        assertThat(byName.get("Copper_Demo-Edge_Cuts.gbr")).contains("X80000000Y60000000D01*");

        String drill = byName.get("Copper_Demo.drl");
        assertThat(drill).startsWith("M48").contains("METRIC", "T1C", "X70.000Y50.000").endsWith("M30\n");
        long holes = drill.lines().filter(l -> l.startsWith("X")).count();
        assertThat(holes).isEqualTo(PcbGeometry.pads(project).size() + project.board().vias().size());

        assertThat(byName.get("Copper_Demo-BOM.csv").lines()).containsExactly(
                "Item,Quantity,References,Value,Part,Footprint",
                "1,1,Q1,NPN transistor (TO-92),NPN transistor (TO-92),TO-92_Inline",
                "2,1,R1,1k,Resistor,R_Axial_P10.16mm",
                "3,1,R2,10k,Resistor,R_Axial_P10.16mm");
        assertThat(byName.get("Copper_Demo-positions.csv").lines()).hasSize(4)
                .first().isEqualTo("Designator,Value,Package,PosX,PosY,Rotation,Side");
    }

    private List<String> codes() {
        return analyzer.analyze(project).findings().stream().map(BoardAnalyzer.Finding::code).toList();
    }

    private UUID net(String name) {
        return project.nets().stream().filter(n -> n.name().equals(name)).findFirst().orElseThrow().id();
    }

    private PcbGeometry.PlacedPad pad(String reference, String number) {
        return PcbGeometry.pads(project).stream()
                .filter(p -> p.reference().equals(reference) && p.pinNumber().equals(number)).findFirst()
                .orElseThrow();
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
        project = editor.connect(project, name, Arrays.stream(pins).map(this::pin).toList());
    }
}
