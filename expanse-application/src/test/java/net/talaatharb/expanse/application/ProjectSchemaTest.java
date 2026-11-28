package net.talaatharb.expanse.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.talaatharb.expanse.domain.DesignException;
import org.junit.jupiter.api.Test;

class ProjectSchemaTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ProjectJson codec = new ProjectJson();

    @Test
    void currentFixtureIsCanonicalAndConformsToSchema05() throws Exception {
        Schema schema = schema("0.5");
        String fixture = resource("/fixtures/projects/all-parts.json");
        assertThat(schema.validate(mapper.readTree(fixture))).isEmpty();
        var project = codec.read(fixture);
        assertThat(project.components()).hasSize(15);
        assertThat(project.nets()).filteredOn(n -> n.name().equals("BUF")).singleElement()
                .satisfies(n -> assertThat(n.labels()).singleElement().satisfies(l -> assertThat(l.pinId())
                        .isEqualTo(project.component("U1").pin("OUT").id())));
        assertThat(project.board().traces()).hasSize(2);
        assertThat(new net.talaatharb.expanse.pcb.BoardAnalyzer().analyze(project).findings())
                .as("the demo fixture must be free of design-rule errors")
                .noneMatch(f -> f.severity().equals("ERROR"));
        assertThat(codec.write(project)).isEqualToNormalizingNewlines(fixture.strip());
        var invalid = (ObjectNode) mapper.readTree(fixture);
        invalid.put("schemaVersion", "99");
        assertThat(schema.validate(invalid)).isNotEmpty();
    }

    @Test
    void logicDemoFixtureIsCanonicalCleanAndSimulates() throws Exception {
        String fixture = resource("/fixtures/projects/logic-demo.json");
        assertThat(schema("0.5").validate(mapper.readTree(fixture))).isEmpty();
        var project = codec.read(fixture);
        assertThat(codec.write(project)).isEqualToNormalizingNewlines(fixture.strip());
        assertThat(new net.talaatharb.expanse.validation.DesignValidator().validate(project))
                .noneMatch(f -> f.severity().equals("ERROR") || f.code().startsWith("LOGIC_")
                        || f.code().equals("ERC_SINGLE_COMPONENT_NET"));
        assertThat(new net.talaatharb.expanse.pcb.BoardAnalyzer().analyze(project).findings())
                .noneMatch(f -> f.severity().equals("ERROR"));
        var result = new net.talaatharb.expanse.simulation.LogicSimulator().simulate(project, 3e-3,
                java.util.List.of(new net.talaatharb.expanse.simulation.CircuitSimulator.SwitchEvent(
                        "SW1", 1.2e-3, "closed")));
        assertThat(result.series().get("Q")).extracting(t -> t.level()).containsExactly("0", "1", "0", "1");
        assertThat(result.series().get("EN")).extracting(t -> t.level()).containsExactly("1", "0");
        assertThat(result.levels()).containsEntry("GATED", "0");
    }

    @Test
    void legacy01DocumentsMigrateToCurrentDefinitionsPreservingIdentity() throws Exception {
        String legacy = resource("/fixtures/projects/legacy/resistor-loop-0.1.json");
        assertThat(schema("0.1").validate(mapper.readTree(legacy))).isEmpty();
        var project = codec.read(legacy);
        assertThat(project.schemaVersion()).isEqualTo("0.5");
        assertThat(project.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(project.revision()).isEqualTo(2);
        var resistor = project.component("R1");
        assertThat(resistor.definition().version()).isEqualTo("2.0");
        assertThat(resistor.properties()).containsEntry("resistance", "1k");
        assertThat(resistor.pins()).extracting(p -> p.id().toString())
                .containsExactly("00000000-0000-0000-0000-000000000004", "00000000-0000-0000-0000-000000000005");
        assertThat(project.board().placements()).isEmpty();
        assertThat(schema("0.5").validate(mapper.readTree(codec.write(project)))).isEmpty();

        String unknownPart = legacy.replace("builtin.resistor", "vendor.mystery");
        assertThatThrownBy(() -> codec.read(unknownPart)).isInstanceOfSatisfying(DesignException.class,
                e -> assertThat(e.code()).isEqualTo("MIGRATION_FAILED"));
        assertThatThrownBy(() -> codec.read(legacy.replace("\"unit\": \"mm\"", "\"unit\": \"in\"")))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DOCUMENT"));
    }

    @Test
    void schema02DocumentsMigrateLosslesslyWithEmptyWiresAndParts() throws Exception {
        String previous = resource("/fixtures/projects/legacy/logic-demo-0.2.json");
        assertThat(schema("0.2").validate(mapper.readTree(previous))).isEmpty();
        var project = codec.read(previous);
        assertThat(project.schemaVersion()).isEqualTo("0.5");
        assertThat(project.parts()).isEmpty();
        assertThat(project.nets()).isNotEmpty().allMatch(n -> n.wires().isEmpty() && n.labels().isEmpty());
        assertThat(codec.write(project)).as("migration only adds the new collections")
                .isEqualToNormalizingNewlines(resource("/fixtures/projects/logic-demo.json").strip());
    }

    @Test
    void schema03DocumentsMigrateWithEmptyLabelsAndKeepEverythingElse() throws Exception {
        String wires = resource("/fixtures/projects/legacy/all-parts-0.3.json");
        assertThat(schema("0.3").validate(mapper.readTree(wires))).isEmpty();
        var project = codec.read(wires);
        assertThat(project.schemaVersion()).isEqualTo("0.5");
        assertThat(project.components()).hasSize(12);
        assertThat(project.board().traces()).hasSize(2);
        assertThat(project.nets()).isNotEmpty().allMatch(n -> n.labels().isEmpty());
        var rewritten = mapper.readTree(codec.write(project));
        assertThat(schema("0.5").validate(rewritten)).isEmpty();
        var original = (ObjectNode) mapper.readTree(wires);
        original.put("schemaVersion", "0.5");
        original.withArray("nets").forEach(n -> ((ObjectNode) n).putArray("labels"));
        ((ObjectNode) original.get("board")).putArray("vias");
        ((ObjectNode) original.get("board")).putArray("zones");
        assertThat(rewritten).as("migration only adds empty label lists").isEqualTo(original);
        assertThatThrownBy(() -> codec.read(wires.replace("\"wires\" : [ ]", "\"wires\" : [ ], \"labels\" : [ ]")))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DOCUMENT"));
    }

    @Test
    void schema04DocumentsMigrateWithEmptyViasAndZones() throws Exception {
        String labels = resource("/fixtures/projects/legacy/all-parts-0.4.json");
        assertThat(schema("0.4").validate(mapper.readTree(labels))).isEmpty();
        var project = codec.read(labels);
        assertThat(project.schemaVersion()).isEqualTo("0.5");
        assertThat(project.board().vias()).isEmpty();
        assertThat(project.board().zones()).isEmpty();
        assertThat(codec.write(project)).as("migration only adds empty via and zone lists")
                .isEqualToNormalizingNewlines(resource("/fixtures/projects/all-parts.json").strip());
        assertThatThrownBy(() -> codec.read(labels.replace("\"traces\" : [", "\"vias\" : [ ], \"traces\" : [")))
                .isInstanceOfSatisfying(DesignException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DOCUMENT"));
    }

    private String resource(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Schema schema(String version) throws IOException {
        try (var input = getClass().getResourceAsStream("/schemas/project/" + version + ".schema.json")) {
            assertThat(input).isNotNull();
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .formatAssertionsEnabled(true).build())).getSchema(input);
        }
    }
}
