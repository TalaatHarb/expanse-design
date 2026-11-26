package net.talaatharb.expanse.server;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.talaatharb.expanse.connector.ConnectorConfiguration;
import net.talaatharb.expanse.connector.ConnectorException;
import net.talaatharb.expanse.connector.WorkbenchClient;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:contract;DB_CLOSE_DELAY=-1",
        "expanse.api-token=contract-owner-012345678901234567890123456789",
        "expanse.read-token=contract-reader-012345678901234567890123456789",
        "expanse.server-url=http://127.0.0.1:${local.server.port}",
        "spring.cloud.openfeign.lazy-attributes-resolution=true"})
@Import(ConnectorConfiguration.class)
class ServerContractTest {
    @Autowired
    private WorkbenchClient client;
    @LocalServerPort
    private int port;

    @Test
    void feignClientAndControllerShareAWorkingRevisionedContract() {
        assertThat(client.search("")).hasSize(25);
        assertThat(client.search("74hc")).hasSize(6);
        assertThat(client.search("resistor")).hasSize(1);
        assertThat(client.projects(0, 100)).isNotNull();
        var project = client.create(new CreateProject("Contract circuit"));
        project = client.place(project.id(), new PlaceComponent(0, project.sheets().getFirst().id(),
                "builtin.resistor", "R1", 10, 20));
        assertThat(project.components()).hasSize(1);
        var pins = project.components().getFirst().pins();
        project = client.connect(project.id(), new ConnectPins(1, "LOOP", pins.stream().map(PinView::id).toList()));
        assertThat(client.validate(project.id()).diagnostics()).noneMatch(d -> d.severity().equals("ERROR"));
        assertThat(client.project(project.id())).isEqualTo(project);
        var id = project.id();
        assertThatThrownBy(() -> client.connect(id, new ConnectPins(1, "STALE",
                pins.stream().map(PinView::id).toList())))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.error().code()).isEqualTo("REVISION_CONFLICT");
                    assertThat(e.error().currentRevision()).isEqualTo(2);
                });
        Document document = client.exportProject(id);
        assertThat(document.json()).contains("\"schemaVersion\" : \"0.3\"");
        assertThatThrownBy(() -> client.importProject(document))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThat(client.capabilities()).anyMatch(c -> c.id().equals("simulation") && c.available());
    }

    @Test
    void logicDividerSimulatesPlacesOnTheBoardAndRendersInThreeDOverFeign() {
        var project = client.create(new CreateProject("Divider over the wire"));
        var applied = client.applyChanges(project.id(), new ChangeSet(0, List.of(
                new PlaceOp("builtin.74hc74", "U1", 0, 0, null, null, null),
                new PlaceOp("builtin.logic-clock", "CK", -20, 0, null, java.util.Map.of("frequency", "1k"), null),
                new PlaceOp("builtin.74hc04", "U2", 30, 0, null, null, null),
                new PlaceOp("builtin.power-rail", "PWR", 0, -20, null, null, null),
                new PlaceOp("builtin.ground", "G1", 0, 20, null, null, null),
                new ConnectOp("VCC", List.of("PWR.1", "U1.VCC", "U1.1PRE", "U1.1CLR", "U2.VCC")),
                new ConnectOp("GND", List.of("G1.1", "U1.GND", "U2.GND")),
                new ConnectOp("CLK", List.of("CK.1", "U1.1CLK")),
                new ConnectOp("FB", List.of("U1.1QN", "U1.1D")),
                new ConnectOp("Q", List.of("U1.1Q", "U2.1A")),
                new SyncBoardOp())));
        assertThat(applied.diagnostics()).noneMatch(d -> d.code().startsWith("LOGIC_"));
        var logic = client.simulate(project.id(), new SimulationRequest("logic", 2.2e-3, null, null, null));
        assertThat(logic.analysis()).isEqualTo("logic");
        assertThat(logic.logicSeries().get("Q")).extracting(LogicTransitionView::level)
                .containsExactly("0", "1", "0");
        assertThat(logic.logicLevels()).containsEntry("CLK", "0").containsEntry("VCC", "1");
        assertThat(logic.voltageSeries()).isEmpty();
        var analog = client.simulate(project.id(), new SimulationRequest("op", null, null, null, null));
        assertThat(analog.nodeVoltages().get("FB")).isCloseTo(5, within(1e-6));
        assertThat(client.analyzeBoard(project.id()).pads()).hasSize(28);
        assertThat(client.assembly(project.id()).solids()).filteredOn(s -> s.role().equals("body")).hasSize(2);
    }

    @Test
    void changeSetsSimulationBoardAssemblyAndDocumentLifecycleWorkOverFeign() throws Exception {
        var project = client.create(new CreateProject("RC over the wire"));
        var changes = new ChangeSet(0, List.of(
                new PlaceOp("builtin.dc-source", "V1", 0, 0, null, java.util.Map.of("voltage", "5"), null),
                new PlaceOp("builtin.resistor", "R1", 20, 0, null, java.util.Map.of("resistance", "1k"), null),
                new PlaceOp("builtin.capacitor", "C1", 40, 0, null, java.util.Map.of("capacitance", "1u"), null),
                new PlaceOp("builtin.ground", "G1", 0, 20, null, null, null),
                new ConnectOp("VIN", List.of("V1.+", "R1.1")),
                new ConnectOp("OUT", List.of("R1.2", "C1.1")),
                new ConnectOp("GND", List.of("C1.2", "V1.-", "G1.GND")),
                new SyncBoardOp(),
                new RouteOp("R1.2", "C1.1", null, null)));
        var preview = client.previewChanges(project.id(), changes);
        assertThat(preview.applied()).isFalse();
        assertThat(client.project(project.id()).components()).isEmpty();
        var applied = client.applyChanges(project.id(), changes);
        assertThat(applied.revision()).isEqualTo(1);
        assertThat(applied.snapshot().board().traces()).hasSize(1);

        var transient_ = client.simulate(project.id(), new SimulationRequest("transient", 5e-3, 1e-5, true, null));
        assertThat(transient_.voltageSeries().get("OUT").getLast()).isCloseTo(5 * (1 - Math.exp(-5)), withinPercentage(3));
        var board = client.analyzeBoard(project.id());
        assertThat(board.pads()).hasSize(6);
        assertThat(board.airwires()).hasSize(2);
        assertThat(client.assembly(project.id()).solids()).anyMatch(s -> s.role().equals("body"));

        String reader = "contract-reader-012345678901234567890123456789";
        String body = "{\"expectedRevision\":1,\"operations\":[{\"op\":\"rename_project\",\"name\":\"x\"}]}";
        assertThat(request("POST", "/api/v1/projects/" + project.id() + "/changes/preview", reader, body)
                .statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/v1/projects/" + project.id() + "/changes", reader, body)
                .statusCode()).isEqualTo(403);
        var op = request("POST", "/api/v1/projects/" + project.id() + "/simulations", reader, "{\"analysis\":\"op\",\"stopTimeS\":null,\"timeStepS\":null,\"zeroInitialConditions\":null,\"switchEvents\":null}");
        assertThat(op.statusCode()).as(op.body()).isEqualTo(200);
        assertThat(request("POST", "/api/v1/projects/" + project.id() + "/changes/preview",
                "contract-owner-012345678901234567890123456789",
                "{\"expectedRevision\":1,\"operations\":[{\"op\":\"format_disk\"}]}").statusCode()).isEqualTo(400);

        String saved = client.exportProject(project.id()).json();
        client.applyChanges(project.id(), new ChangeSet(1, List.of(new DeleteComponentOp("C1"))));
        var restored = client.replaceDocument(project.id(), new ReplaceDocument(2, saved));
        assertThat(restored.revision()).isEqualTo(3);
        assertThat(restored.components()).hasSize(4);
        var undone = client.undo(project.id(), new HistoryStep(3));
        assertThat(undone.revision()).isEqualTo(4);
        assertThat(undone.snapshot().components()).hasSize(3);
        assertThat(client.history(project.id())).satisfies(h -> {
            assertThat(h.canUndo()).isTrue();
            assertThat(h.redoRevision()).isEqualTo(3L);
        });
        assertThat(client.redo(project.id(), new HistoryStep(4)).snapshot().components()).hasSize(4);
        assertThatThrownBy(() -> client.redo(project.id(), new HistoryStep(5))).isInstanceOfSatisfying(
                ConnectorException.class, e -> assertThat(e.error().code()).isEqualTo("NOTHING_TO_REDO"));

        var custom = client.applyChanges(project.id(), new ChangeSet(5, List.of(
                new DefinePartOp(new PartSpec("probe", null, "Probe", null, List.of(new PinSpec("1", "TIP", true)),
                        null, null, null, 0, 0, null)),
                new PlaceOp("project.probe", "TP1", 30, 10, null, null, null),
                new DrawWireOp(null, "TP1.TIP", "OUT", List.of(new PointView(30, 0), new PointView(30, 5)), null))));
        assertThat(custom.snapshot().parts()).extracting(PartSpec::id).containsExactly("project.probe");
        var tee = custom.snapshot().nets().stream().filter(n -> n.name().equals("OUT")).findFirst().orElseThrow()
                .wires().getFirst();
        assertThat(tee.fromPinId()).isNull();
        assertThat(tee.toPinId()).isNotNull();
        String wired = client.exportProject(project.id()).json();
        assertThat(wired).contains("\"fromPinId\" : null", "\"id\" : \"project.probe\"");

        var copy = client.importCopy(new Document(wired));
        assertThat(copy.id()).isNotEqualTo(project.id());
        assertThat(copy.parts()).hasSize(1);
        var copiedTee = copy.nets().stream().filter(n -> n.name().equals("OUT")).findFirst().orElseThrow()
                .wires().getFirst();
        assertThat(copiedTee.id()).isNotEqualTo(tee.id());
        assertThat(copy.components().stream().flatMap(c -> c.pins().stream()).map(PinView::id))
                .contains(copiedTee.toPinId());
        client.delete(copy.id(), 0);
        assertThatThrownBy(() -> client.project(copy.id()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.status()).isEqualTo(404));
    }
    @Test
    void concurrentWritersHaveExactlyOneWinner() throws Exception {
        var project = client.create(new CreateProject("Concurrent circuit"));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start signal timed out");
                }
                try {
                    client.place(project.id(), new PlaceComponent(0, project.sheets().getFirst().id(),
                            "builtin.resistor", "R" + index, index, 0));
                    return "success";
                } catch (ConnectorException conflict) {
                    return conflict.error().code();
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("success", "REVISION_CONFLICT");
        }
        assertThat(client.project(project.id()).revision()).isEqualTo(1);
        assertThat(client.project(project.id()).components()).hasSize(1);
    }

    @Test
    void missingCredentialsAndReadOnlyMutationsAreRejectedAndInputIsStrict() throws Exception {
        assertThat(request("GET", "/api/v1/capabilities", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/capabilities",
                "contract-reader-012345678901234567890123456789", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/projects",
                "contract-reader-012345678901234567890123456789", null).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/api/v1/unknown",
                "contract-reader-012345678901234567890123456789", null).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/api/v1/projects",
                "contract-reader-012345678901234567890123456789", "{\"name\":\"denied\"}").statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> client.create(new CreateProject(" ")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> client.projects(-1, 100))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThat(request("POST", "/api/v1/projects",
                "contract-owner-012345678901234567890123456789", "{\"name\":\"bad\",\"unknown\":true}").statusCode())
                .isEqualTo(400);
        var project = client.create(new CreateProject("Missing revision"));
        assertThat(request("POST", "/api/v1/projects/" + project.id() + "/components",
                "contract-owner-012345678901234567890123456789",
                "{\"sheetId\":\"" + project.sheets().getFirst().id()
                        + "\",\"definitionId\":\"builtin.resistor\",\"reference\":\"R1\",\"xMm\":0,\"yMm\":0}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void healthAndApiDocsArePublicWhileEverythingElseStaysProtected() throws Exception {
        var health = request("GET", "/actuator/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}");
        assertThat(request("GET", "/actuator/health/readiness", null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/env", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/actuator/env", "contract-owner-012345678901234567890123456789", null)
                .statusCode()).isEqualTo(403);
        assertThat(request("POST", "/actuator/health", null, "{}").statusCode()).isEqualTo(401);

        var docs = request("GET", "/v3/api-docs", null, null);
        assertThat(docs.statusCode()).as(docs.body()).isEqualTo(200);
        assertThat(docs.body()).contains("\"title\":\"Expanse Design API\"", "/api/v1/projects/{id}/changes",
                "\"bearerToken\"", "\"draw_wire\":\"#/components/schemas/DrawWireOp\"").doesNotContain("/actuator");
        assertThat(request("GET", "/swagger-ui/index.html", null, null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
