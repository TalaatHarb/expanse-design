package net.talaatharb.expanse.application;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.dto.ApiModels.*;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.schematic.SchematicEditor;
import net.talaatharb.expanse.validation.DesignValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectWorkflowTest {
    private final ProjectJson json = new ProjectJson();
    private final MemoryRepository repository = new MemoryRepository();
    private final ProjectService service = new ProjectService(repository, json, new ComponentLibrary(),
            new SchematicEditor(), new DesignValidator());
    private Snapshot project;

    @BeforeEach
    void create() {
        project = service.create(new CreateProject("Voltage divider"));
    }

    /** Builds a circuit using every supported part kind: a switched NPN stage, an NMOS load, R, L, C. */
    static List<Operation> allPartsCircuit() {
        return List.of(
                new PlaceOp("builtin.dc-source", "V1", 10, 10, 0, Map.of("voltage", "9"), null),
                new PlaceOp("builtin.power-rail", "VCC", 10, 0, 0, Map.of("voltage", "5"), null),
                new PlaceOp("builtin.ground", "GND1", 10, 40, 0, null, null),
                new PlaceOp("builtin.switch-spst", "S1", 30, 10, 0, Map.of("state", "closed"), null),
                new PlaceOp("builtin.resistor", "RB", 50, 10, 90, Map.of("resistance", "220k"), null),
                new PlaceOp("builtin.resistor", "RC", 70, 0, 0, Map.of("resistance", "1k"), null),
                new PlaceOp("builtin.npn", "Q1", 70, 20, 0, null, null),
                new PlaceOp("builtin.pnp", "Q2", 110, 20, 0, null, null),
                new PlaceOp("builtin.capacitor", "C1", 90, 20, 0, Map.of("capacitance", "100n"), null),
                new PlaceOp("builtin.inductor", "L1", 90, 40, 0, Map.of("inductance", "10m"), null),
                new PlaceOp("builtin.nmos", "M1", 130, 20, 0, null, null),
                new PlaceOp("builtin.pmos", "M2", 150, 20, 0, null, null),
                new PlaceOp("builtin.diode", "D1", 130, 40, 90, null, null),
                new PlaceOp("builtin.led", "D2", 150, 40, 90, Map.of("color", "green"), null),
                new PlaceOp("builtin.opamp", "U1", 170, 20, 0, null, null),
                new ConnectOp("VIN", List.of("V1.+", "S1.1")),
                new ConnectOp("SW", List.of("S1.2", "RB.1")),
                new ConnectOp("BASE", List.of("RB.2", "Q1.B")),
                new ConnectOp("VCC", List.of("VCC.VCC", "RC.1", "Q2.E", "M2.S", "U1.V+")),
                new ConnectOp("OUT", List.of("RC.2", "Q1.C", "C1.1", "M1.G", "Q2.B", "M2.G", "U1.IN+")),
                new ConnectOp("LOAD", List.of("M1.D", "L1.1", "Q2.C", "M2.D", "D1.A", "D2.A")),
                new ConnectOp("BUF", List.of("U1.IN-", "U1.OUT")),
                new ConnectOp("GND", List.of("GND1.GND", "V1.-", "Q1.E", "C1.2", "M1.S", "L1.2", "D1.K", "D2.K",
                        "U1.V-")),
                new SyncBoardOp(),
                new RouteOp("RC.2", "Q1.C", "top", 0.4),
                new RouteOp("RB.2", "Q1.B", "bottom", null));
    }

    @Test
    void undoAndRedoRestoreEarlierContentAsNewRevisionsAndNewEditsClearRedo() {
        UUID id = project.id();
        assertThat(service.history(id).canUndo()).isFalse();
        service.apply(id, new ChangeSet(0, List.of(new PlaceOp("builtin.resistor", "R1", 0, 0, 0, null, null))));
        service.apply(id, new ChangeSet(1, List.of(new PlaceOp("builtin.resistor", "R2", 10, 0, 0, null, null))));
        assertThat(service.history(id)).isEqualTo(new HistoryView(id, 2, true, false, 1L, null));

        var undone = service.undo(id, new HistoryStep(2));
        assertThat(undone.revision()).isEqualTo(3);
        assertThat(references(undone.snapshot())).containsExactly("R1");
        assertThat(references(service.undo(id, new HistoryStep(3)).snapshot())).isEmpty();
        assertThat(service.history(id)).isEqualTo(new HistoryView(id, 4, false, true, null, 3L));
        assertThatThrownBy(() -> service.undo(id, new HistoryStep(4))).isInstanceOfSatisfying(DesignException.class,
                e -> assertThat(e.code()).isEqualTo("NOTHING_TO_UNDO"));
        assertThatThrownBy(() -> service.redo(id, new HistoryStep(3))).isInstanceOfSatisfying(DesignException.class,
                e -> assertThat(e.code()).isEqualTo("REVISION_CONFLICT"));

        assertThat(references(service.redo(id, new HistoryStep(4)).snapshot())).containsExactly("R1");
        assertThat(references(service.redo(id, new HistoryStep(5)).snapshot())).containsExactly("R1", "R2");
        assertThat(service.history(id).canRedo()).isFalse();
        assertThat(references(service.undo(id, new HistoryStep(6)).snapshot())).containsExactly("R1");

        service.apply(id, new ChangeSet(7, List.of(new PlaceOp("builtin.capacitor", "C1", 0, 20, 0, null, null))));
        assertThat(service.history(id)).isEqualTo(new HistoryView(id, 8, true, false, 7L, null));
        assertThat(references(service.undo(id, new HistoryStep(8)).snapshot())).containsExactly("R1");
        assertThat(references(service.undo(id, new HistoryStep(9)).snapshot())).isEmpty();
        assertThatThrownBy(() -> service.redo(id, new HistoryStep(9))).isInstanceOfSatisfying(DesignException.class,
                e -> assertThat(e.code()).isEqualTo("REVISION_CONFLICT"));
        assertThat(service.history(id).redoRevision()).isEqualTo(9L);
    }

    private static List<String> references(Snapshot snapshot) {
        return snapshot.components().stream().map(ComponentView::reference).toList();
    }

    private static NetView netOf(Snapshot snapshot, String reference, String pin) {
        UUID pinId = snapshot.components().stream().filter(c -> c.reference().equals(reference))
                .flatMap(c -> c.pins().stream()).filter(p -> p.number().equals(pin) || p.name().equals(pin)).findFirst()
                .orElseThrow().id();
        return snapshot.nets().stream().filter(n -> n.pinIds().contains(pinId)).findFirst().orElse(null);
    }

    @Test
    void wiresConnectPinsCarryBendsAndTeesFollowNetMergesAndAreCleanedUp() {
        UUID id = project.id();
        service.apply(id, new ChangeSet(0, List.of(
                new PlaceOp("builtin.resistor", "R1", 0, 0, 0, null, null),
                new PlaceOp("builtin.resistor", "R2", 20, 0, 0, null, null),
                new PlaceOp("builtin.resistor", "R3", 20, 20, 0, null, null),
                new PlaceOp("builtin.resistor", "R4", 40, 20, 0, null, null),
                new PlaceOp("builtin.resistor", "R5", 60, 20, 0, null, null),
                new DrawWireOp("R1.2", "R2.1", null, List.of(new PointView(10, 0)), "MID"),
                new DrawWireOp(null, "R3.1", "MID", List.of(new PointView(10, 0), new PointView(10, 20)), null),
                new DrawWireOp("R4.2", "R5.1", null, List.of(), null))));
        var wired = service.get(id);
        NetView mid = netOf(wired, "R1", "2");
        assertThat(mid.name()).isEqualTo("MID");
        assertThat(mid.pinIds()).hasSize(3);
        assertThat(mid.wires()).hasSize(2);
        assertThat(mid.wires().get(1).fromPinId()).as("a tee starts at a free point").isNull();
        assertThat(mid.wires().get(1).points()).containsExactly(new PointView(10, 0), new PointView(10, 20));
        assertThat(netOf(wired, "R4", "2").wires()).singleElement()
                .satisfies(w -> assertThat(w.points()).isEmpty());

        assertThatThrownBy(() -> service.apply(id, new ChangeSet(1, List.of(
                new DrawWireOp("R1.1", null, null, List.of(new PointView(-10, 0)), null)))))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("PIN_NOT_CONNECTED"));
        assertThat(json.read(service.exportProject(id).json())).isEqualTo(repository.get(id));

        service.apply(id, new ChangeSet(1, List.of(new ConnectOp(null, List.of("R4.2", "R2.1")))));
        var merged = service.get(id);
        assertThat(netOf(merged, "R5", "1").wires()).as("wires move with merged nets").hasSize(3);

        service.apply(id, new ChangeSet(2, List.of(new DisconnectOp("R3.1"))));
        assertThat(netOf(service.get(id), "R1", "2").wires()).as("wires to a removed pin go with it").hasSize(2);
        UUID first = netOf(service.get(id), "R1", "2").wires().getFirst().id();
        var deleted = service.apply(id, new ChangeSet(3, List.of(new DeleteWireOp(first))));
        assertThat(netOf(deleted.snapshot(), "R1", "2").pinIds()).as("deleting graphics keeps connectivity")
                .hasSize(4);
        service.apply(id, new ChangeSet(4, List.of(new DeleteComponentOp("R5"))));
        assertThat(netOf(service.get(id), "R1", "2").wires()).isEmpty();
        assertThat(netOf(service.undo(id, new HistoryStep(5)).snapshot(), "R1", "2").wires()).hasSize(1);
    }

    @Test
    void customPartsArePlacedSimulatedRedefinedSavedAndDeleted() {
        UUID id = project.id();
        var pins = List.of(new PinSpec("1", "VCC", true), new PinSpec("2", "OUT", true), new PinSpec("3", "GND", true));
        var pads = List.of(new PadSpec("1", -2.54, 0, 1.6, 1.6, 0.8), new PadSpec("2", 0, 0, 1.6, 1.6, 0.8),
                new PadSpec("3", 2.54, 0, 1.6, 1.6, 0.8));
        var sensor = new PartSpec("sensor", null, "Sensor", null, pins, null, null, pads, 8, 4, null);
        var defined = service.apply(id, new ChangeSet(0, List.of(new DefinePartOp(sensor),
                new PlaceOp("project.sensor", "U1", 40, 0, 0, null, null),
                new PlaceOp("builtin.dc-source", "V1", 0, 0, 0, Map.of("voltage", "9"), null),
                new PlaceOp("builtin.ground", "GND1", 0, 20, 0, null, null),
                new PlaceOp("builtin.resistor", "R1", 20, 0, 0, null, null),
                new ConnectOp("VIN", List.of("V1.+", "R1.1")),
                new ConnectOp("OUT", List.of("R1.2", "U1.OUT")),
                new ConnectOp("GND", List.of("V1.-", "GND1.GND", "U1.GND")),
                new SyncBoardOp())));
        assertThat(defined.snapshot().parts()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("project.sensor");
            assertThat(p.kind()).isEqualTo(Design.GENERIC_KIND);
            assertThat(p.body().shape()).isEqualTo("box");
        });
        assertThat(defined.snapshot().board().placements()).extracting(PlacementView::reference).contains("U1");
        var simulation = service.simulate(id, new SimulationRequest("op", null, null, null, null));
        assertThat(simulation.nodeVoltages().get("OUT")).isCloseTo(9, within(1e-3));
        assertThat(simulation.warnings()).anyMatch(w -> w.contains("U1") && w.contains("custom part"));
        assertThat(json.read(service.exportProject(id).json())).isEqualTo(repository.get(id));

        UUID vccPin = defined.snapshot().components().stream().filter(c -> c.reference().equals("U1")).findFirst()
                .orElseThrow().pins().getFirst().id();
        var schematicOnly = new PartSpec("project.sensor", "2", "Sensor", null, List.of(pins.get(0), pins.get(1),
                new PinSpec("4", "EN", false)), null, null, null, 0, 0, null);
        var redefined = service.apply(id, new ChangeSet(1, List.of(new DefinePartOp(schematicOnly)))).snapshot();
        var u1 = redefined.components().stream().filter(c -> c.reference().equals("U1")).findFirst().orElseThrow();
        assertThat(u1.pins()).extracting(PinView::number).containsExactly("1", "2", "4");
        assertThat(u1.pins().getFirst().id()).as("pin identity is kept by number").isEqualTo(vccPin);
        assertThat(u1.definitionVersion()).isEqualTo("2");
        assertThat(netOf(redefined, "GND1", "GND").pinIds()).hasSize(2);
        assertThat(redefined.board().placements()).extracting(PlacementView::reference).doesNotContain("U1");

        assertThatThrownBy(() -> service.apply(id, new ChangeSet(2, List.of(new DeletePartOp("sensor")))))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("PART_IN_USE"));
        var removed = service.apply(id, new ChangeSet(2, List.of(new DeleteComponentOp("U1"),
                new DeletePartOp("sensor"))));
        assertThat(removed.snapshot().parts()).isEmpty();

        var badResistor = new PartSpec("my-r", null, "My R", "resistor", List.of(new PinSpec("1", "A", true),
                new PinSpec("2", "B", true)), List.of(), null, null, 0, 0, null);
        assertThatThrownBy(() -> service.apply(id, new ChangeSet(3, List.of(new DefinePartOp(badResistor)))))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.getMessage()).contains("resistance"));
        var resistance = new ComponentLibrary().get("builtin.resistor").parameters().getFirst();
        var goodResistor = new PartSpec("my-r", null, "My R", "resistor", badResistor.pins(),
                List.of(new ParameterView(resistance.name(), resistance.unit(), "47k", List.of())), null, null, 0, 0,
                null);
        var placed = service.apply(id, new ChangeSet(3, List.of(new DefinePartOp(goodResistor),
                new PlaceOp("project.my-r", "R9", 0, 40, 0, null, null))));
        assertThat(placed.snapshot().components()).anyMatch(c -> c.reference().equals("R9")
                && c.kind().equals("resistor") && c.properties().get("resistance").equals("47k"));
    }

    @Test
    void deletingAllTracesInOneChangeSetKeepsPlacementsAndRestoresAirwires() {
        service.apply(project.id(), new ChangeSet(0, allPartsCircuit()));
        var routed = service.get(project.id());
        int airwiresBefore = service.analyzeBoard(project.id()).airwires().size();
        List<Operation> deletes = routed.board().traces().stream()
                .map(t -> (Operation) new DeleteTraceOp(t.id())).toList();

        var applied = service.apply(project.id(), new ChangeSet(routed.revision(), deletes));

        assertThat(applied.revision()).isEqualTo(routed.revision() + 1);
        var cleared = service.get(project.id());
        assertThat(cleared.board().traces()).isEmpty();
        assertThat(cleared.board().placements()).hasSameSizeAs(routed.board().placements());
        assertThat(service.analyzeBoard(project.id()).airwires()).hasSize(airwiresBefore + 2);
    }

    @Test
    void netLabelsNameAndJoinNetsFollowPinsAndSurviveSaveAndLoad() {
        UUID id = project.id();
        service.apply(id, new ChangeSet(0, List.of(
                new PlaceOp("builtin.resistor", "R1", 0, 0, 0, null, null),
                new PlaceOp("builtin.resistor", "R2", 20, 0, 0, null, null),
                new PlaceOp("builtin.resistor", "R3", 40, 0, 0, null, null),
                new PlaceOp("builtin.ground", "G1", 60, 0, 0, null, null),
                new PlaceNetLabelOp("IN", "R1.1", null, null, null))));
        assertThat(net(id, "IN")).satisfies(n -> {
            assertThat(n.pinIds()).hasSize(1);
            assertThat(n.labels()).singleElement().satisfies(l -> assertThat(l.pinId()).isEqualTo(n.pinIds().getFirst()));
        });

        service.apply(id, new ChangeSet(1, List.of(
                new PlaceNetLabelOp("IN", "R2.1", null, null, null),
                new ConnectOp("X", List.of("R2.2", "R3.1")),
                new PlaceNetLabelOp("IN", null, "X", 30.0, 5.0))));
        var joined = net(id, "IN");
        assertThat(joined.pinIds()).hasSize(4);
        assertThat(joined.labels()).hasSize(3).filteredOn(l -> l.point() != null).singleElement()
                .satisfies(l -> assertThat(l.point()).isEqualTo(new PointView(30, 5)));
        assertThat(service.get(id).nets()).extracting(NetView::name).containsExactly("IN");

        String saved = service.exportProject(id).json();
        assertThat(json.write(json.read(saved))).isEqualTo(saved);

        var r1Label = joined.labels().stream().filter(l -> l.pinId() != null && l.pinId().equals(
                service.get(id).components().getFirst().pins().getFirst().id())).findFirst().orElseThrow();
        service.apply(id, new ChangeSet(2, List.of(new DeleteComponentOp("R1"))));
        assertThat(net(id, "IN").labels()).hasSize(2).noneMatch(l -> l.id().equals(r1Label.id()));

        service.apply(id, new ChangeSet(3, List.of(new PlaceNetLabelOp("LONE", "G1.1", null, null, null))));
        UUID lone = net(id, "LONE").labels().getFirst().id();
        service.apply(id, new ChangeSet(4, List.of(new DeleteNetLabelOp(lone))));
        assertThat(service.get(id).nets()).extracting(NetView::name).containsExactly("IN");
        assertThatThrownBy(() -> service.apply(id, new ChangeSet(5, List.of(new DeleteNetLabelOp(lone)))))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("LABEL_NOT_FOUND"));
        assertThatThrownBy(() -> service.apply(id, new ChangeSet(5, List.of(
                new PlaceNetLabelOp("Y", "R2.1", "IN", 0.0, 0.0))))).isInstanceOf(DesignException.class);
    }

    private NetView net(UUID projectId, String name) {
        return service.get(projectId).nets().stream().filter(n -> n.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void changeSetsPreviewWithoutPersistingAndApplyAsOneRevision() {
        var preview = service.preview(project.id(), new ChangeSet(0, allPartsCircuit()));
        assertThat(preview.applied()).isFalse();
        assertThat(preview.revision()).isEqualTo(1);
        assertThat(preview.snapshot().components()).hasSize(15);
        assertThat(service.get(project.id()).revision()).isZero();
        assertThat(service.get(project.id()).components()).isEmpty();

        var applied = service.apply(project.id(), new ChangeSet(0, allPartsCircuit()));
        assertThat(applied.applied()).isTrue();
        assertThat(applied.revision()).isEqualTo(1);
        assertThat(applied.created()).filteredOn(c -> c.kind().equals("trace")).hasSize(2);
        var snapshot = service.get(project.id());
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.components()).extracting(ComponentView::kind)
                .containsExactlyInAnyOrderElementsOf(Design.KINDS.stream().filter(k -> !k.startsWith("logic-") && !k.equals(Design.GENERIC_KIND))
                        .flatMap(k -> k.equals("resistor") ? java.util.stream.Stream.of(k, k) : java.util.stream.Stream.of(k))
                        .toList());
        assertThat(snapshot.board().placements()).hasSize(13);
        assertThat(snapshot.board().traces()).hasSize(2);
        assertThat(snapshot.board().pads()).hasSize(36);
        assertThat(snapshot.components()).filteredOn(c -> c.reference().equals("RB")).singleElement()
                .satisfies(c -> {
                    assertThat(c.rotation()).isEqualTo(90);
                    assertThat(c.properties()).containsEntry("resistance", "220k");
                });

        var simulation = service.simulate(project.id(), new SimulationRequest("op", null, null, null, null));
        assertThat(simulation.nodeVoltages()).containsKeys("VIN", "OUT", "LOAD", "GND", "BUF");
        assertThat(simulation.nodeVoltages().get("BUF")).isCloseTo(simulation.nodeVoltages().get("OUT"), within(1e-3));
        assertThat(simulation.nodeVoltages().get("VIN")).isCloseTo(9, within(1e-6));
        assertThat(service.analyzeBoard(project.id()).airwires()).isNotEmpty();
        assertThat(service.assembly(project.id()).solids()).anyMatch(s -> s.role().equals("body"));
    }

    @Test
    void failingOperationRollsBackTheWholeBatchAndReportsItsPosition() {
        service.apply(project.id(), new ChangeSet(0, List.of(new PlaceOp("builtin.resistor", "R1", 0, 0, null,
                null, null))));
        String before = service.exportProject(project.id()).json();
        assertThatThrownBy(() -> service.apply(project.id(), new ChangeSet(1, List.of(
                new PlaceOp("builtin.resistor", "R2", 0, 0, null, null, null),
                new SetPropertiesOp("R2", Map.of("resistance", "lots"))))))
                .isInstanceOfSatisfying(DesignException.class,
                        e -> assertThat(e.getMessage()).startsWith("Operation 2 (set_properties)"));
        assertThatThrownBy(() -> service.apply(project.id(), new ChangeSet(0, List.of(new RenameProjectOp("x")))))
                .isInstanceOfSatisfying(DesignException.class, e -> {
                    assertThat(e.code()).isEqualTo("REVISION_CONFLICT");
                    assertThat(e.currentRevision()).isEqualTo(1);
                });
        assertThatThrownBy(() -> service.apply(project.id(), new ChangeSet(1, List.of(
                new ConnectOp(null, List.of("R1.1", "R9.1")))))).isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> service.apply(project.id(), new ChangeSet(1, List.of(
                new PlaceOp("builtin.resistor", "R1", 0, 0, null, null, null)))))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("REFERENCE_EXISTS"));
        assertThat(service.exportProject(project.id()).json()).isEqualTo(before);
    }

    @Test
    void editingCommandsMoveRotateRenameDisconnectAndDeleteWithCascade() {
        service.apply(project.id(), new ChangeSet(0, allPartsCircuit()));
        var result = service.apply(project.id(), new ChangeSet(1, List.of(
                new MoveOp("Q1", 75, 25, 180),
                new SetReferenceOp("RC", "RLOAD"),
                new RenameNetOp("OUT", "COLLECTOR"),
                new DisconnectOp("M2.G"),
                new DeleteComponentOp("RB"),
                new SetBoardOp(100.0, null, null, 0.25))));
        var s = result.snapshot();
        assertThat(result.revision()).isEqualTo(2);
        assertThat(s.components()).extracting(ComponentView::reference).contains("RLOAD").doesNotContain("RB", "RC");
        assertThat(s.components()).filteredOn(c -> c.reference().equals("Q1")).singleElement()
                .satisfies(c -> assertThat(c.rotation()).isEqualTo(180));
        assertThat(s.nets()).extracting(NetView::name).contains("COLLECTOR").doesNotContain("OUT", "SW", "BASE");
        assertThat(s.board().traces()).hasSize(1);
        assertThat(s.board().widthMm()).isEqualTo(100);
        assertThat(s.board().clearanceMm()).isEqualTo(0.25);
        assertThat(s.board().placements()).extracting(PlacementView::reference).doesNotContain("RB");
    }

    @Test
    void saveAndLoadRoundTripsEveryPartKindTheBoardAndExtensions() {
        service.apply(project.id(), new ChangeSet(0, allPartsCircuit()));
        String saved = service.exportProject(project.id()).json();
        Project reopened = json.read(saved);
        assertThat(reopened).isEqualTo(repository.get(project.id()));
        assertThat(json.write(reopened)).isEqualTo(saved);
        assertThat(reopened.schemaVersion()).isEqualTo("0.5");

        assertThatThrownBy(() -> service.importProject(new Document(saved)))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("PROJECT_EXISTS"));
        var copy = service.importCopy(new Document(saved));
        assertThat(copy.id()).isNotEqualTo(project.id());
        assertThat(copy.revision()).isZero();
        assertThat(copy.components()).hasSize(15);
        assertThat(copy.components()).extracting(ComponentView::id)
                .doesNotContainAnyElementsOf(service.get(project.id()).components().stream().map(ComponentView::id).toList());
        assertThat(copy.board().traces()).hasSize(2);

        service.apply(project.id(), new ChangeSet(1, List.of(new DeleteComponentOp("Q1"))));
        var restored = service.replaceDocument(project.id(), new ReplaceDocument(2, saved));
        assertThat(restored.revision()).isEqualTo(3);
        assertThat(restored.components()).extracting(ComponentView::reference).contains("Q1");
        assertThatThrownBy(() -> service.replaceDocument(copy.id(), new ReplaceDocument(0, saved)))
                .isInstanceOf(DesignException.class);

        Project original = repository.get(project.id());
        Project extended = new Project(original.schemaVersion(), original.id(), original.name(), 0,
                original.createdAt(), original.modifiedAt(), original.sheets(), original.components(),
                original.nets(), original.board(), original.parts(), Map.of("vendor.note", "{\"opaque\":\"value\"}"));
        assertThat(json.read(json.write(extended))).isEqualTo(extended);

        service.delete(copy.id(), 0);
        assertThatThrownBy(() -> service.get(copy.id())).isInstanceOf(DesignException.class);
    }

    @Test
    void legacyPlaceAndConnectEndpointsStillWork() {
        project = service.place(project.id(), new PlaceComponent(0, project.sheets().getFirst().id(),
                "builtin.resistor", "R1", 10, 10));
        project = service.place(project.id(), new PlaceComponent(1, project.sheets().getFirst().id(),
                "builtin.resistor", "R2", 30, 10));
        project = service.connect(project.id(), new ConnectPins(2, "MID", List.of(
                project.components().get(0).pins().get(1).id(), project.components().get(1).pins().get(0).id())));
        assertThat(project.revision()).isEqualTo(3);
        assertThat(service.validate(project.id()).diagnostics()).extracting(Diagnostic::code)
                .contains("SCH_REQUIRED_PIN_UNCONNECTED");
        assertThatThrownBy(() -> service.place(project.id(), new PlaceComponent(3, UUID.randomUUID(),
                "builtin.resistor", "R3", 0, 0))).isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> service.place(project.id(), new PlaceComponent(3, project.sheets().getFirst().id(),
                "builtin.resistor", "R3", Double.NaN, 0))).isInstanceOf(DesignException.class);
    }

    @Test
    void importRejectsUnsupportedUnknownMissingAndMalformedDataWithoutOverwriting() {
        String original = service.exportProject(project.id()).json();
        assertThatThrownBy(() -> json.read(original.replace("\"0.5\"", "\"99.0\"")))
                .isInstanceOfSatisfying(DesignException.class,
                        e -> assertThat(e.code()).isEqualTo("UNSUPPORTED_SCHEMA"));
        assertThatThrownBy(() -> json.read(original.replaceFirst("\\{", "{\"unknown\":true,")))
                .isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> json.read(original.replace("\"revision\" : 0,", "")))
                .isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> json.read(original.replace("\"revision\" : 0", "\"revision\" : 0.5")))
                .isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> json.read(original.replace("\"board\" : {", "\"board\" : {\"x\":1,")))
                .isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> json.read(original + "{}")).isInstanceOf(DesignException.class);
        assertThatThrownBy(() -> json.read("null")).isInstanceOf(DesignException.class);
        assertThat(service.exportProject(project.id()).json()).isEqualTo(original);
    }

    @Test
    void searchExposesKindsParametersAndReadiness() {
        assertThat(service.search("")).hasSize(28);
        assertThat(service.search("74hc")).hasSize(6);
        assertThat(service.search("mos")).extracting(DefinitionView::kind).containsExactlyInAnyOrder("nmos", "pmos");
        assertThat(service.search("ground")).singleElement().satisfies(d -> assertThat(d.pcbReady()).isFalse());
        assertThat(service.capabilities()).allMatch(Capability::available);
    }

    static final class MemoryRepository implements ProjectRepository {
        private final Map<UUID, Project> projects = new HashMap<>();
        private final Map<String, StoredRevision> revisions = new HashMap<>();

        @Override
        public Project get(UUID id) {
            Project project = projects.get(id);
            if (project == null) {
                throw new DesignException("PROJECT_NOT_FOUND", "Project not found: " + id);
            }
            return project;
        }

        @Override
        public List<Project> list(int offset, int limit) {
            return new ArrayList<>(projects.values()).stream().skip(offset).limit(limit).toList();
        }

        @Override
        public void insert(Project project) {
            if (projects.putIfAbsent(project.id(), project) != null) {
                throw new DesignException("PROJECT_EXISTS", "Already exists");
            }
            revisions.put(project.id() + "@" + project.revision(), new StoredRevision(project, History.NONE));
        }

        @Override
        public void replace(Project project, long expectedRevision, History history) {
            assertThat(get(project.id()).revision()).isEqualTo(expectedRevision);
            projects.put(project.id(), project);
            revisions.put(project.id() + "@" + project.revision(), new StoredRevision(project, history));
        }

        @Override
        public java.util.Optional<StoredRevision> revision(UUID id, long revision) {
            return java.util.Optional.ofNullable(revisions.get(id + "@" + revision));
        }

        @Override
        public void delete(UUID id, long expectedRevision) {
            assertThat(get(id).revision()).isEqualTo(expectedRevision);
            projects.remove(id);
        }
    }
}
