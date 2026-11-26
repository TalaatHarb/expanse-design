package net.talaatharb.expanse.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
            .build();

    public static final String LEGACY_VERSION = "0.1";
    private final Schema legacySchema;
    private final ComponentLibrary library;

    public ProjectJson() {
        this(new ComponentLibrary());
    }

    public ProjectJson(ComponentLibrary library) {
        this.library = library;
        schema = load(Design.SCHEMA_VERSION);
        legacySchema = load(LEGACY_VERSION);
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
     * Identity and revision are preserved so a migrated project keeps its history position.
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
        return tree;
    }
}