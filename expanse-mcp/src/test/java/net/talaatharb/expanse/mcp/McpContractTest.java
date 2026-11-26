package net.talaatharb.expanse.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.talaatharb.expanse.api.WorkbenchApi;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.junit.jupiter.api.Test;

class McpContractTest {
    @Test
    void readOnlyPolicyOverRealStdioExposesOnlyAnnotatedQueryTools() throws Exception {
        var mapper = JsonMapper.builder().build();
        var api = mock(WorkbenchApi.class);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(api.project(id)).thenReturn(new Snapshot(id, "MCP circuit", 7, List.of(), List.of(), List.of(),
                new BoardView(100, 80, 1.6, 0.2, List.of(), List.of(), List.of()), List.of()));
        try (var serverInput = new PipedInputStream(65536);
             var clientWrite = new PipedOutputStream(serverInput);
             var clientInput = new PipedInputStream(65536);
             var serverOutput = new PipedOutputStream(clientInput);
             var reader = new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
             var reads = Executors.newSingleThreadExecutor()) {
            var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(mapper), serverInput, serverOutput);
            var server = McpServer.sync(transport).serverInfo("expanse-test", "0.1.0")
                    .tools(new WorkbenchTools(api, mapper, McpPolicy.readOnly()).specifications()).build();
            try {
                send(clientWrite, """
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"independent-test-peer","version":"1"}}}
                        """);
                JsonNode initialized = mapper.readTree(reads.submit(reader::readLine).get(10, TimeUnit.SECONDS));
                assertThat(initialized.path("result").path("serverInfo").path("name").asText()).isEqualTo("expanse-test");
                send(clientWrite, """
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                        """);
                JsonNode discovered = mapper.readTree(reads.submit(reader::readLine).get(10, TimeUnit.SECONDS));
                JsonNode tools = discovered.path("result").path("tools");
                assertThat(tools.size()).isEqualTo(18);
                for (JsonNode tool : tools) {
                    assertThat(tool.path("name").asText()).doesNotContain("place", "apply", "connect", "delete", "create");
                    assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).as(tool.toString()).isTrue();
                    assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
                }
                send(clientWrite, """
                        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"project.get_summary","arguments":{"projectId":"00000000-0000-0000-0000-000000000001"}}}
                        """);
                JsonNode queried = mapper.readTree(reads.submit(reader::readLine).get(10, TimeUnit.SECONDS));
                JsonNode summary = mapper.readTree(queried.path("result").path("content").get(0).path("text").asText());
                assertThat(summary.path("data").path("revision").asLong()).isEqualTo(7);
                verify(api).project(id);
                send(clientWrite, """
                        {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"project.get_summary","arguments":{"projectId":"bad","unexpected":true}}}
                        """);
                JsonNode rejected = mapper.readTree(reads.submit(reader::readLine).get(10, TimeUnit.SECONDS));
                assertThat(rejected.has("error") || rejected.path("result").path("isError").asBoolean()).isTrue();
                verifyNoMoreInteractions(api);
            } finally {
                server.closeGracefully();
            }
        }
    }

    @Test
    void editingPolicyDispatchesChangeSetsAndRefusesDestructiveOperations() throws Exception {
        var mapper = JsonMapper.builder().build();
        var api = mock(WorkbenchApi.class);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var applied = new ChangeResult(id, 3, 4, true, List.of("Placed R1"), List.of(), List.of(), null);
        when(api.applyChanges(eq(id), any())).thenReturn(applied);
        var tools = byName(new WorkbenchTools(api, mapper, McpPolicy.editing()).specifications());
        assertThat(tools).containsKeys("project.apply_changes", "schematic.place_component", "pcb.route",
                        "project.undo", "project.redo", "project.get_history", "schematic.draw_wire", "parts.define",
                        "parts.list")
                .doesNotContainKeys("project.delete", "schematic.delete_component");
        assertThat(tools.get("project.apply_changes").tool().annotations().destructiveHint()).isTrue();
        assertThat(tools.get("schematic.place_component").tool().annotations().readOnlyHint()).isFalse();

        var placed = call(mapper, tools, "schematic.place_component", Map.of("projectId", id.toString(),
                "expectedRevision", 3, "definitionId", "builtin.resistor", "reference", "R1", "xMm", 10, "yMm", 5,
                "properties", Map.of("resistance", "4.7k")));
        assertThat(placed.path("data").path("revision").asLong()).isEqualTo(4);
        verify(api).applyChanges(id, new ChangeSet(3, List.of(new PlaceOp("builtin.resistor", "R1", 10, 5, null,
                Map.of("resistance", "4.7k"), null))));

        var operations = mapper.readValue("""
                [{"op":"connect_pins","pins":["R1.1","V1.+"]},{"op":"delete_component","component":"C1"}]""", List.class);
        var denied = call(mapper, tools, "project.apply_changes",
                Map.of("projectId", id.toString(), "expectedRevision", 4, "operations", operations));
        assertThat(denied.path("code").asText()).isEqualTo("POLICY_DENIED");

        var unknownField = mapper.readValue("[{\"op\":\"sync_board\",\"force\":true}]", List.class);
        assertThat(call(mapper, tools, "project.preview_changes", Map.of("projectId", id.toString(),
                "expectedRevision", 4, "operations", unknownField)).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(call(mapper, tools, "schematic.place_component", Map.of("projectId", id.toString()))
                .path("code").asText()).isEqualTo("INVALID_ARGUMENT");

        when(api.undo(id, new HistoryStep(4))).thenReturn(new ChangeResult(id, 4, 5, true,
                List.of("Undid revision 4; restored the content of revision 3"), List.of(), List.of(), null));
        assertThat(tools.get("project.undo").tool().annotations().destructiveHint()).isFalse();
        var undone = call(mapper, tools, "project.undo", Map.of("projectId", id.toString(), "expectedRevision", 4));
        assertThat(undone.path("data").path("revision").asLong()).isEqualTo(5);
        verify(api).undo(id, new HistoryStep(4));

        call(mapper, tools, "schematic.draw_wire", Map.of("projectId", id.toString(), "expectedRevision", 5,
                "from", "R1.2", "net", "VIN", "points", List.of(Map.of("xMm", 5, "yMm", 0))));
        verify(api).applyChanges(id, new ChangeSet(5, List.of(new DrawWireOp("R1.2", null, "VIN",
                List.of(new PointView(5, 0)), null))));
        call(mapper, tools, "parts.define", Map.of("projectId", id.toString(), "expectedRevision", 6, "part",
                Map.of("id", "sensor", "name", "Sensor", "pins", List.of(Map.of("number", "1", "name", "OUT",
                        "required", true)))));
        verify(api).applyChanges(id, new ChangeSet(6, List.of(new DefinePartOp(new PartSpec("sensor", null, "Sensor",
                null, List.of(new PinSpec("1", "OUT", true)), null, null, null, 0, 0, null)))));
        verifyNoMoreInteractions(api);

        var full = byName(new WorkbenchTools(api, mapper, McpPolicy.full()).specifications());
        assertThat(full).containsKeys("project.delete", "schematic.delete_component");
        assertThat(full.get("project.delete").tool().annotations().destructiveHint()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationSchemaIsDerivedFromTheSharedContractAndSeriesAreDownSampled() {
        var schema = WorkbenchTools.operationsSchema();
        @SuppressWarnings("unchecked")
        var variants = (List<Map<String, Object>>) ((Map<String, Object>) schema.get("items")).get("oneOf");
        assertThat(variants).hasSize(22);
        var place = variants.stream().filter(v -> ((Map<?, ?>) v.get("properties")).get("op")
                .equals(Map.of("const", "place_component"))).findFirst().orElseThrow();
        assertThat((List<Object>) place.get("required"))
                .containsExactly("op", "definitionId", "reference", "xMm", "yMm");

        var time = java.util.stream.IntStream.range(0, 1001).mapToObj(i -> i * 1e-3).toList();
        var report = new SimulationReport(UUID.randomUUID(), 1, "transient", "e", "m", Map.of(), Map.of(), Map.of(),
                time, Map.of("OUT", time), Map.of(), Map.of(), Map.of(), List.of(), List.of());
        var small = WorkbenchTools.downsample(report, 11);
        assertThat(small.time()).hasSize(11).startsWith(0.0).endsWith(1.0);
        assertThat(small.voltageSeries().get("OUT")).isEqualTo(small.time());
        assertThat(small.warnings()).hasSize(1);

        var transitions = time.stream().map(t -> new LogicTransitionView(t, "1")).toList();
        var logic = new SimulationReport(UUID.randomUUID(), 1, "logic", "e", "m", Map.of(), Map.of(), Map.of(),
                List.of(), Map.of(), Map.of(), Map.of("CLK", "1", "Q", "0"),
                Map.of("CLK", transitions, "Q", transitions.subList(0, 3)), List.of(), List.of());
        var trimmed = WorkbenchTools.downsample(logic, 11);
        assertThat(trimmed.logicSeries().get("CLK")).hasSize(11);
        assertThat(trimmed.logicSeries().get("Q")).hasSize(3);
        assertThat(trimmed.logicLevels()).containsEntry("CLK", "1");
        assertThat(trimmed.warnings()).singleElement().asString().contains("CLK").doesNotContain("Q,");
    }

    private static Map<String, SyncToolSpecification> byName(List<SyncToolSpecification> specifications) {
        var map = new java.util.LinkedHashMap<String, SyncToolSpecification>();
        specifications.forEach(s -> map.put(s.tool().name(), s));
        return map;
    }

    @SuppressWarnings("unchecked")
    private static JsonNode call(com.fasterxml.jackson.databind.ObjectMapper mapper,
                                 Map<String, SyncToolSpecification> tools, String name, Map<String, ?> arguments)
            throws Exception {
        var result = tools.get(name).callHandler().apply(null,
                new McpSchema.CallToolRequest(name, (Map<String, Object>) arguments));
        return mapper.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }

    private static void send(PipedOutputStream stream, String message) throws Exception {
        stream.write(message.getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }
}
