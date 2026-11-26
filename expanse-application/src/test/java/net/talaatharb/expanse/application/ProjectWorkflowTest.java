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
                new ConnectOp("VIN", List.of("V1.+", "S1.1")),
                new ConnectOp("SW", List.of("S1.2", "RB.1")),
                new ConnectOp("BASE", List.of("RB.2", "Q1.B")),
                new ConnectOp("VCC", List.of("VCC.VCC", "RC.1", "Q2.E", "M2.S")),
                new ConnectOp("OUT", List.of("RC.2", "Q1.C", "C1.1", "M1.G", "Q2.B", "M2.G")),
                new ConnectOp("LOAD", List.of("M1.D", "L1.1", "Q2.C", "M2.D")),
                new ConnectOp("GND", List.of("GND1.GND", "V1.-", "Q1.E", "C1.2", "M1.S", "L1.2")),
                new SyncBoardOp(),
                new RouteOp("RC.2", "Q1.C", "top", 0.4),
                new RouteOp("RB.2", "Q1.B", "bottom", null));
    }

    @Test
    void changeSetsPreviewWithoutPersistingAndApplyAsOneRevision() {
        var preview = service.preview(project.id(), new ChangeSet(0, allPartsCircuit()));
        assertThat(preview.applied()).isFalse();
        assertThat(preview.revision()).isEqualTo(1);
        assertThat(preview.snapshot().components()).hasSize(12);
        assertThat(service.get(project.id()).revision()).isZero();
        assertThat(service.get(project.id()).components()).isEmpty();

        var applied = service.apply(project.id(), new ChangeSet(0, allPartsCircuit()));
        assertThat(applied.applied()).isTrue();
        assertThat(applied.revision()).isEqualTo(1);
        assertThat(applied.created()).filteredOn(c -> c.kind().equals("trace")).hasSize(2);
        var snapshot = service.get(project.id());
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.components()).extracting(ComponentView::kind)
                .containsExactlyInAnyOrderElementsOf(Design.KINDS.stream().filter(k -> !k.startsWith("logic-"))
                        .flatMap(k -> k.equals("resistor") ? java.util.stream.Stream.of(k, k) : java.util.stream.Stream.of(k))
                        .toList());
        assertThat(snapshot.board().placements()).hasSize(10);
        assertThat(snapshot.board().traces()).hasSize(2);
        assertThat(snapshot.board().pads()).hasSize(24);
        assertThat(snapshot.components()).filteredOn(c -> c.reference().equals("RB")).singleElement()
                .satisfies(c -> {
                    assertThat(c.rotation()).isEqualTo(90);
                    assertThat(c.properties()).containsEntry("resistance", "220k");
                });

        var simulation = service.simulate(project.id(), new SimulationRequest("op", null, null, null, null));
        assertThat(simulation.nodeVoltages()).containsKeys("VIN", "OUT", "LOAD", "GND");
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
        assertThat(reopened.schemaVersion()).isEqualTo("0.2");

        assertThatThrownBy(() -> service.importProject(new Document(saved)))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("PROJECT_EXISTS"));
        var copy = service.importCopy(new Document(saved));
        assertThat(copy.id()).isNotEqualTo(project.id());
        assertThat(copy.revision()).isZero();
        assertThat(copy.components()).hasSize(12);
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
                original.nets(), original.board(), Map.of("vendor.note", "{\"opaque\":\"value\"}"));
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
        assertThatThrownBy(() -> json.read(original.replace("\"0.2\"", "\"99.0\"")))
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
        assertThat(service.search("")).hasSize(25);
        assertThat(service.search("74hc")).hasSize(6);
        assertThat(service.search("mos")).extracting(DefinitionView::kind).containsExactlyInAnyOrder("nmos", "pmos");
        assertThat(service.search("ground")).singleElement().satisfies(d -> assertThat(d.pcbReady()).isFalse());
        assertThat(service.capabilities()).allMatch(Capability::available);
    }

    static final class MemoryRepository implements ProjectRepository {
        private final Map<UUID, Project> projects = new HashMap<>();

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
        }

        @Override
        public void replace(Project project, long expectedRevision) {
            assertThat(get(project.id()).revision()).isEqualTo(expectedRevision);
            projects.put(project.id(), project);
        }

        @Override
        public void delete(UUID id, long expectedRevision) {
            assertThat(get(id).revision()).isEqualTo(expectedRevision);
            projects.remove(id);
        }
    }
}
