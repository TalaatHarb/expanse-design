package net.talaatharb.expanse.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.library.ComponentLibrary;

public final class ProjectJson {
    public static final int MAX_CHARACTERS = 8_388_608;
    private final Schema schema;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                            .maxStringLength(MAX_CHARACTERS).maxDocumentLength(MAX_CHARACTERS).build()).build())
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .addModule(new SimpleModule().addDeserializer(Design.Wire.class, new WireDeserializer())
                    .addDeserializer(Design.NetLabel.class, new NetLabelDeserializer()))
            .build();

    public static final String LEGACY_VERSION = "0.1";
    public static final String PREVIOUS_VERSION = "0.2";
    public static final String WIRES_VERSION = "0.3";
    public static final String LABELS_VERSION = "0.4";
    private final Schema legacySchema;
    private final Schema labelsSchema;
    private final Schema previousSchema;
    private final Schema wiresSchema;
    private final ComponentLibrary library;

    public ProjectJson() {
        this(new ComponentLibrary());
    }

    public ProjectJson(ComponentLibrary library) {
        this.library = library;
        schema = load(Design.SCHEMA_VERSION);
        legacySchema = load(LEGACY_VERSION);
        previousSchema = load(PREVIOUS_VERSION);
        wiresSchema = load(WIRES_VERSION);
        labelsSchema = load(LABELS_VERSION);
    }

    private static Schema load(String version) {
        try (var input = ProjectJson.class.getResourceAsStream("/schemas/project/" + version + ".schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Project schema " + version + " is missing from the application");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .formatAssertionsEnabled(true).build())).getSchema(input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load project schema " + version, e);
        }
    }
    public String write(Project project) {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(project);
            if (json.length() > MAX_CHARACTERS) {
                throw new DesignException("PROJECT_TOO_LARGE", "Project JSON exceeds the 8 MiB character limit");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize a validated project", e);
        }
    }

    public Project read(String json) {
        if (json == null || json.length() > MAX_CHARACTERS) {
            throw new DesignException("INVALID_DOCUMENT", "Project JSON is missing or exceeds 8 MiB characters");
        }
        try {
            JsonNode tree = mapper.readTree(json);
            if (tree == null || !tree.isObject()) {
                throw new DesignException("INVALID_DOCUMENT", "Project JSON must be an object");
            }
            JsonNode version = tree.path("schemaVersion");
            if (version.isTextual() && LEGACY_VERSION.equals(version.textValue())) {
                if (!legacySchema.validate(tree).isEmpty()) {
                    throw new DesignException("INVALID_DOCUMENT",
                            "Project JSON does not conform to schema 0.1; check required fields, types, IDs and units");
                }
                tree = migrateFromLegacy((ObjectNode) tree);
            } else if (version.isTextual() && PREVIOUS_VERSION.equals(version.textValue())) {
                if (!previousSchema.validate(tree).isEmpty()) {
                    throw new DesignException("INVALID_DOCUMENT",
                            "Project JSON does not conform to schema 0.2; check required fields, types, IDs and units");
                }
                tree = migrateFromPrevious((ObjectNode) tree);
            } else if (version.isTextual() && WIRES_VERSION.equals(version.textValue())) {
                if (!wiresSchema.validate(tree).isEmpty()) {
                    throw new DesignException("INVALID_DOCUMENT",
                            "Project JSON does not conform to schema 0.3; check required fields, types, IDs and units");
                }
                tree = migrateFromWires((ObjectNode) tree);
            } else if (version.isTextual() && LABELS_VERSION.equals(version.textValue())) {
                if (!labelsSchema.validate(tree).isEmpty()) {
                    throw new DesignException("INVALID_DOCUMENT",
                            "Project JSON does not conform to schema 0.4; check required fields, types, IDs and units");
                }
                tree = migrateFromLabels((ObjectNode) tree);
            } else if (version.isTextual() && !Design.SCHEMA_VERSION.equals(version.textValue())) {
                throw new DesignException("UNSUPPORTED_SCHEMA", "Unsupported project schema: " + version.textValue());
            }
            if (!schema.validate(tree).isEmpty()) {
                throw new DesignException("INVALID_DOCUMENT", "Project JSON does not conform to schema "
                        + Design.SCHEMA_VERSION + "; check required fields, types, IDs and units");
            }
            return mapper.treeToValue(tree, Project.class);
        } catch (JsonProcessingException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof DesignException design) {
                    throw design;
                }
            }
            throw new DesignException("INVALID_DOCUMENT", "Invalid project JSON: " + e.getOriginalMessage());
        }
    }

    /**
     * 0.1 documents carried pin-only definition snapshots. Migration substitutes the current built-in definition
     * with the same ID (pins must match), adds default parameter values, rotation 0 and an empty board.
     * Identity and revision are preserved so a migrated project keeps its history position. Continues to 0.5.
     */
    private JsonNode migrateFromLegacy(ObjectNode tree) {
        tree.put("schemaVersion", Design.SCHEMA_VERSION);
        for (JsonNode node : tree.withArray("components")) {
            ObjectNode component = (ObjectNode) node;
            String definitionId = component.path("definition").path("id").asText();
            Design.Definition definition = library.find(definitionId).orElseThrow(() -> new DesignException(
                    "MIGRATION_FAILED", "Cannot migrate 0.1 definition " + definitionId + "; it is not in the library"));
            var legacyPins = new java.util.HashSet<String>();
            component.path("definition").path("pins").forEach(p -> legacyPins.add(p.path("number").asText()));
            var currentPins = new java.util.HashSet<>(definition.pins().stream().map(Design.PinDefinition::number).toList());
            if (!legacyPins.equals(currentPins)) {
                throw new DesignException("MIGRATION_FAILED", "Pins of " + definitionId + " changed; cannot migrate");
            }
            component.set("definition", mapper.valueToTree(definition));
            component.put("rotation", 0);
            component.set("properties", mapper.valueToTree(definition.defaults()));
        }
        tree.set("board", mapper.valueToTree(Design.Board.empty()));
        return migrateFromPrevious(tree);
    }

    /** 0.2 → 0.3: nets gain (empty) wire graphics and the project gains an (empty) custom part list. Continues. */
    private JsonNode migrateFromPrevious(ObjectNode tree) {
        tree.put("schemaVersion", Design.SCHEMA_VERSION);
        for (JsonNode net : tree.withArray("nets")) {
            ((ObjectNode) net).putArray("wires");
        }
        tree.putArray("parts");
        return migrateFromWires(tree);
    }

    /** 0.3 → 0.4: nets gain (empty) net labels; 0.4 also adds the diode, LED and op-amp kinds. Continues. */
    private JsonNode migrateFromWires(ObjectNode tree) {
        tree.put("schemaVersion", Design.SCHEMA_VERSION);
        for (JsonNode net : tree.withArray("nets")) {
            ((ObjectNode) net).putArray("labels");
        }
        return migrateFromLabels(tree);
    }

    /** 0.4 → 0.5: the board gains (empty) vias and copper zones. */
    private JsonNode migrateFromLabels(ObjectNode tree) {
        tree.put("schemaVersion", Design.SCHEMA_VERSION);
        ObjectNode board = (ObjectNode) tree.get("board");
        board.putArray("vias");
        board.putArray("zones");
        return tree;
    }

    /** Exactly one of pinId and at is set; the other is null or absent. */
    private static final class NetLabelDeserializer extends StdDeserializer<Design.NetLabel> {
        NetLabelDeserializer() {
            super(Design.NetLabel.class);
        }

        @Override
        public Design.NetLabel deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = context.readTree(parser);
            for (var field : node.properties()) {
                if (!java.util.Set.of("id", "pinId", "at").contains(field.getKey())) {
                    throw new DesignException("INVALID_DOCUMENT", "Unknown net label property " + field.getKey());
                }
            }
            JsonNode at = node.get("at");
            Design.Point point = at == null || at.isNull() ? null
                    : new Design.Point(at.path("xMm").asDouble(), at.path("yMm").asDouble());
            return new Design.NetLabel(WireDeserializer.uuid(node.get("id")), WireDeserializer.uuid(node.get("pinId")),
                    point);
        }
    }

    /** Wire ends may be null (free ends), which the strict creator settings would otherwise reject. */
    private static final class WireDeserializer extends StdDeserializer<Design.Wire> {
        WireDeserializer() {
            super(Design.Wire.class);
        }

        @Override
        public Design.Wire deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = context.readTree(parser);
            for (var field : node.properties()) {
                if (!java.util.Set.of("id", "fromPinId", "toPinId", "points").contains(field.getKey())) {
                    throw new DesignException("INVALID_DOCUMENT", "Unknown wire property " + field.getKey());
                }
            }
            var points = new java.util.ArrayList<Design.Point>();
            node.path("points").forEach(p -> points.add(new Design.Point(p.path("xMm").asDouble(),
                    p.path("yMm").asDouble())));
            return new Design.Wire(uuid(node.get("id")), uuid(node.get("fromPinId")), uuid(node.get("toPinId")),
                    points);
        }

        private static java.util.UUID uuid(JsonNode value) {
            return value == null || value.isNull() ? null : java.util.UUID.fromString(value.asText());
        }
    }
}