package net.talaatharb.expanse.mcp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.talaatharb.expanse.api.WorkbenchApi;
import net.talaatharb.expanse.connector.ConnectorException;
import net.talaatharb.expanse.dto.ApiModels.*;

/**
 * MCP tool catalogue. Every tool delegates to the {@link WorkbenchApi} contract, so the same server-side validation,
 * revision checks and change-set engine apply to AI agents, the desktop and REST clients.
 */
public final class WorkbenchTools {
    private static final Map<String, Object> UUID_SCHEMA = Map.of("type", "string", "format", "uuid");
    private static final Map<String, Object> TEXT = Map.of("type", "string", "minLength", 1, "maxLength", 200);
    private static final Map<String, Object> NUMBER = Map.of("type", "number");
    private static final Map<String, Object> ROTATION = Map.of("type", "integer", "enum", List.of(0, 90, 180, 270));
    private static final Map<String, Object> OFFSET = Map.of("type", "integer", "minimum", 0);
    private static final Map<String, Object> LIMIT = Map.of("type", "integer", "minimum", 1, "maximum", 100);
    private static final Map<String, Object> REVISION = Map.of("type", "integer", "minimum", 0,
            "description", "Revision the change is based on; stale revisions are rejected with REVISION_CONFLICT.");
    private static final Map<String, Object> PROPERTIES = Map.of("type", "object",
            "additionalProperties", Map.of("type", "string"),
            "description", "Parameter values with SI prefixes, e.g. {\"resistance\":\"4.7k\"}.");
    private static final Map<String, Object> SELECTOR = Map.of("type", "string", "minLength", 1, "maxLength", 200,
            "description", "Component UUID or reference such as R1.");
    private static final Map<String, Object> PIN = Map.of("type", "string", "minLength", 1, "maxLength", 240,
            "description", "Pin UUID or REF.PIN using the pin number or name, e.g. R1.1 or Q1.B.");

    private enum Kind { READ, WRITE, DESTRUCTIVE }

    private record Arg(String name, Map<String, Object> schema, boolean required) {
    }

    private final WorkbenchApi api;
    private final ObjectMapper mapper;
    private final ObjectMapper operationReader;
    private final McpPolicy policy;

    public WorkbenchTools(WorkbenchApi api, ObjectMapper mapper, McpPolicy policy) {
        this.api = api;
        this.mapper = mapper;
        this.policy = policy;
        this.operationReader = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public List<SyncToolSpecification> specifications() {
        var tools = new ArrayList<SyncToolSpecification>();
        // ---- Read-only inspection ----
        add(tools, "design.get_capabilities", "Capabilities", "List available and unavailable workbench capabilities.",
                Kind.READ, List.of(), args -> api.capabilities());
        add(tools, "project.list", "List projects", "List projects ordered by stable ID (offset defaults to 0, limit to 50).",
                Kind.READ, List.of(opt("offset", OFFSET), opt("limit", LIMIT)),
                args -> api.projects(args.integer("offset", 0, Integer.MAX_VALUE, 0), args.integer("limit", 1, 100, 50)));
        add(tools, "project.get_summary", "Project summary", "Read project identity, revision and object counts.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA)), args -> {
                    var project = api.project(args.uuid("projectId"));
                    return Map.of("projectId", project.id(), "name", project.name(), "revision", project.revision(),
                            "components", project.components().size(), "nets", project.nets().size(),
                            "placements", project.board().placements().size(),
                            "traces", project.board().traces().size());
                });
        add(tools, "project.get_snapshot", "Project snapshot",
                "Read the complete project: sheets, components with pins and properties, nets and the PCB board.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA)), args -> api.project(args.uuid("projectId")));
        add(tools, "schematic.list_components", "List components",
                "Read component instances, kinds, positions, properties and pin IDs with pagination.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA), opt("offset", OFFSET), opt("limit", LIMIT)), args -> {
                    var project = api.project(args.uuid("projectId"));
                    return Map.of("projectId", project.id(), "revision", project.revision(),
                            "components", args.page(project.components()), "total", project.components().size());
                });
        add(tools, "schematic.get_component", "Get component", "Read one component by UUID or reference.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA), req("component", SELECTOR)), args -> {
                    var project = api.project(args.uuid("projectId"));
                    String selector = args.string("component");
                    return Map.of("projectId", project.id(), "revision", project.revision(), "component",
                            project.components().stream().filter(c -> c.id().toString().equals(selector)
                                            || c.reference().equals(selector)).findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("Component not found: " + selector)));
                });
        add(tools, "schematic.list_nets", "List nets", "Read explicit net membership with pagination.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA), opt("offset", OFFSET), opt("limit", LIMIT)), args -> {
                    var project = api.project(args.uuid("projectId"));
                    return Map.of("projectId", project.id(), "revision", project.revision(),
                            "nets", args.page(project.nets()), "total", project.nets().size());
                });
        add(tools, "schematic.get_net", "Get net", "Read one net by UUID or name with its member pin IDs.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA), req("net", TEXT)), args -> {
                    var project = api.project(args.uuid("projectId"));
                    String selector = args.string("net");
                    return Map.of("projectId", project.id(), "revision", project.revision(), "net",
                            project.nets().stream().filter(n -> n.id().toString().equals(selector)
                                            || n.name().equals(selector)).findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("Net not found: " + selector)));
                });
        add(tools, "library.search_components", "Search library",
                "Search built-in parts (resistor, capacitor, inductor, sources, ground, switches, BJTs, MOSFETs). "
                        + "Optional kind filters by part kind such as resistor or npn.",
                Kind.READ, List.of(opt("query", Map.of("type", "string", "maxLength", 200)), opt("kind", TEXT)), args -> {
                    String kind = args.optionalString("kind");
                    return api.search(args.optionalString("query") == null ? "" : args.optionalString("query")).stream()
                            .filter(d -> kind == null || d.kind().equalsIgnoreCase(kind)).toList();
                });
        add(tools, "design.get_violations", "Design diagnostics", "Run electrical rule checks without mutating the project.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA)), args -> api.validate(args.uuid("projectId")));
        add(tools, "simulation.run", "Run simulation",
                "Run a DC operating point (analysis=op), transient analysis (requires stopTimeS and timeStepS) or "
                        + "event-driven digital logic analysis (analysis=logic, requires stopTimeS; reports 0/1/X/Z "
                        + "levels and transitions per net). Does not modify the project. Analog series are "
                        + "down-sampled and logic transitions truncated to maxPoints (default 200).",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA),
                        req("analysis", Map.of("type", "string", "enum", List.of("op", "transient", "logic"))),
                        opt("stopTimeS", Map.of("type", "number", "exclusiveMinimum", 0)),
                        opt("timeStepS", Map.of("type", "number", "exclusiveMinimum", 0)),
                        opt("zeroInitialConditions", Map.of("type", "boolean")),
                        opt("switchEvents", schema(new TypeReference<List<SwitchEventView>>() { }.getType())),
                        opt("maxPoints", Map.of("type", "integer", "minimum", 2, "maximum", 5000))), args -> {
                    var events = args.has("switchEvents")
                            ? operationReader.convertValue(args.values.get("switchEvents"),
                            new TypeReference<List<SwitchEventView>>() { }) : null;
                    var report = api.simulate(args.uuid("projectId"), new SimulationRequest(args.string("analysis"),
                            args.optionalNumber("stopTimeS"), args.optionalNumber("timeStepS"),
                            args.has("zeroInitialConditions") ? args.bool("zeroInitialConditions") : null, events));
                    return downsample(report, args.integer("maxPoints", 2, 5000, 200));
                });
        add(tools, "pcb.get_board", "PCB board", "Read board outline, rules, placements, pads and traces.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA)), args -> {
                    var project = api.project(args.uuid("projectId"));
                    return Map.of("projectId", project.id(), "revision", project.revision(), "board", project.board());
                });
        add(tools, "pcb.get_analysis", "PCB analysis",
                "Read the ratsnest (unrouted connections) and design-rule findings for the board.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA)), args -> api.analyzeBoard(args.uuid("projectId")));
        add(tools, "assembly.get_scene", "3D assembly scene",
                "Read the 3D assembly: board, component bodies, pads and traces as positioned solids (mm, Z up).",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA)), args -> api.assembly(args.uuid("projectId")));
        add(tools, "assembly.check", "3D assembly check", "Read mechanical findings (collisions, overhangs) and height.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA)), args -> {
                    var scene = api.assembly(args.uuid("projectId"));
                    return Map.of("projectId", scene.projectId(), "revision", scene.revision(),
                            "maxHeightMm", scene.maxHeightMm(), "solids", scene.solids().size(),
                            "findings", scene.findings());
                });
        add(tools, "project.preview_changes", "Preview change set",
                "Dry-run an atomic list of operations and return the resulting changes and diagnostics without saving. "
                        + "Selectors accept UUIDs, references (R1), REF.PIN pins and net names.",
                Kind.READ, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("operations", operationsSchema()), opt("includeSnapshot", Map.of("type", "boolean"))),
                args -> trim(api.previewChanges(args.uuid("projectId"), changeSet(args, false)),
                        args.has("includeSnapshot") && args.bool("includeSnapshot")));
        if (!policy.allowMutations()) {
            return List.copyOf(tools);
        }
        // ---- Mutations (one revision per call, all-or-nothing) ----
        add(tools, "project.create", "Create project", "Create an empty project with one sheet and a default board.",
                Kind.WRITE, List.of(req("name", TEXT)), args -> api.create(new CreateProject(args.string("name"))));
        add(tools, "project.apply_changes", "Apply change set",
                "Atomically apply a list of operations as a single new revision. "
                        + (policy.allowDestructive() ? "" : "Destructive operations are disabled by policy. ")
                        + "Use project.preview_changes first for large edits.",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("operations", operationsSchema()), opt("includeSnapshot", Map.of("type", "boolean"))),
                args -> trim(api.applyChanges(args.uuid("projectId"), changeSet(args, true)),
                        args.has("includeSnapshot") && args.bool("includeSnapshot")));
        add(tools, "schematic.place_component", "Place component",
                "Place a library part on the schematic. Properties default to the part defaults.",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("definitionId", TEXT), req("reference", TEXT), req("xMm", NUMBER), req("yMm", NUMBER),
                        opt("rotation", ROTATION), opt("properties", PROPERTIES)),
                args -> single(args, new PlaceOp(args.string("definitionId"), args.string("reference"),
                        args.number("xMm"), args.number("yMm"), args.optionalInteger("rotation"),
                        args.has("properties") ? args.properties("properties") : null, null)));
        add(tools, "schematic.move_component", "Move component", "Move and optionally rotate a schematic symbol.",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("component", SELECTOR), req("xMm", NUMBER), req("yMm", NUMBER), opt("rotation", ROTATION)),
                args -> single(args, new MoveOp(args.string("component"), args.number("xMm"), args.number("yMm"),
                        args.optionalInteger("rotation"))));
        add(tools, "schematic.connect_pins", "Connect pins",
                "Connect two or more pins into one net, merging existing nets. Name is optional.",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("pins", Map.of("type", "array", "minItems", 2, "maxItems", 1000, "items", PIN)),
                        opt("name", TEXT)),
                args -> single(args, new ConnectOp(args.optionalString("name"), args.strings("pins"))));
        add(tools, "schematic.set_properties", "Set properties",
                "Set component parameter values such as resistance, capacitance, voltage or switch state.",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("component", SELECTOR), req("properties", PROPERTIES)),
                args -> single(args, new SetPropertiesOp(args.string("component"), args.properties("properties"))));
        add(tools, "pcb.sync_board", "Sync board", "Place every physical schematic component that is not on the board.",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION)),
                args -> single(args, new SyncBoardOp()));
        add(tools, "pcb.place_footprint", "Place footprint", "Move a footprint on the board (mm, rotation in degrees).",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("component", SELECTOR), req("xMm", NUMBER), req("yMm", NUMBER), opt("rotation", ROTATION)),
                args -> single(args, new PlaceFootprintOp(args.string("component"), args.number("xMm"),
                        args.number("yMm"), args.optionalInteger("rotation"))));
        add(tools, "pcb.route", "Route connection",
                "Route an orthogonal trace between two placed pads on the same net.",
                Kind.WRITE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("from", PIN), req("to", PIN),
                        opt("layer", Map.of("type", "string", "enum", List.of("top", "bottom"))),
                        opt("widthMm", Map.of("type", "number", "exclusiveMinimum", 0))),
                args -> single(args, new RouteOp(args.string("from"), args.string("to"),
                        args.optionalString("layer"), args.optionalNumber("widthMm"))));
        if (!policy.allowDestructive()) {
            return List.copyOf(tools);
        }
        add(tools, "schematic.delete_component", "Delete component",
                "Delete a component, its pins from nets and its board placement.",
                Kind.DESTRUCTIVE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION),
                        req("component", SELECTOR)),
                args -> single(args, new DeleteComponentOp(args.string("component"))));
        add(tools, "project.delete", "Delete project", "Permanently delete a project at the expected revision.",
                Kind.DESTRUCTIVE, List.of(req("projectId", UUID_SCHEMA), req("expectedRevision", REVISION)), args -> {
                    api.delete(args.uuid("projectId"), args.longValue("expectedRevision"));
                    return Map.of("deleted", args.uuid("projectId"));
                });
        return List.copyOf(tools);
    }

    private ChangeResult single(Args args, Operation operation) {
        var result = api.applyChanges(args.uuid("projectId"),
                new ChangeSet(args.longValue("expectedRevision"), List.of(operation)));
        return trim(result, false);
    }

    private ChangeSet changeSet(Args args, boolean applying) {
        List<Operation> operations;
        try {
            operations = operationReader.convertValue(args.values.get("operations"),
                    new TypeReference<List<Operation>>() { });
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid operations: " + rootMessage(e));
        }
        if (operations == null || operations.isEmpty() || operations.size() > 500) {
            throw new IllegalArgumentException("operations must contain 1..500 items");
        }
        if (applying && !policy.allowDestructive()) {
            for (int i = 0; i < operations.size(); i++) {
                if (operations.get(i).destructive()) {
                    throw new PolicyException("Operation " + (i + 1) + " is destructive and destructive MCP edits are "
                            + "disabled; ask the user to enable expanse.mcp.allow-destructive or perform it in the app.");
                }
            }
        }
        return new ChangeSet(args.longValue("expectedRevision"), operations);
    }

    private static ChangeResult trim(ChangeResult result, boolean includeSnapshot) {
        return includeSnapshot ? result : new ChangeResult(result.projectId(), result.baseRevision(), result.revision(),
                result.applied(), result.changes(), result.created(), result.diagnostics(), null);
    }

    static SimulationReport downsample(SimulationReport report, int maxPoints) {
        if (report.logicSeries() != null && !report.logicSeries().isEmpty()) {
            var series = new LinkedHashMap<String, List<LogicTransitionView>>();
            var truncated = new ArrayList<String>();
            report.logicSeries().forEach((net, transitions) -> {
                series.put(net, transitions.size() <= maxPoints ? transitions : transitions.subList(0, maxPoints));
                if (transitions.size() > maxPoints) {
                    truncated.add(net);
                }
            });
            if (truncated.isEmpty()) {
                return report;
            }
            var warnings = new ArrayList<>(report.warnings());
            warnings.add("Logic transitions truncated to the first " + maxPoints + " for " + String.join(", ",
                    truncated) + "; logicLevels still reports the final levels.");
            return new SimulationReport(report.projectId(), report.revision(), report.analysis(), report.engine(),
                    report.models(), report.nodeVoltages(), report.pinCurrents(), report.componentPower(),
                    report.time(), report.voltageSeries(), report.currentSeries(), report.logicLevels(), series,
                    warnings, report.diagnostics());
        }
        if (report.time() == null || report.time().size() <= maxPoints) {
            return report;
        }
        int n = report.time().size();
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < maxPoints; i++) {
            indices.add((int) Math.round((double) i * (n - 1) / (maxPoints - 1)));
        }
        Function<List<Double>, List<Double>> pick = series -> indices.stream().map(series::get).toList();
        var voltages = new LinkedHashMap<String, List<Double>>();
        report.voltageSeries().forEach((k, v) -> voltages.put(k, pick.apply(v)));
        var currents = new LinkedHashMap<String, List<Double>>();
        report.currentSeries().forEach((k, v) -> currents.put(k, pick.apply(v)));
        var warnings = new ArrayList<>(report.warnings());
        warnings.add("Series down-sampled from " + n + " to " + maxPoints + " points.");
        return new SimulationReport(report.projectId(), report.revision(), report.analysis(), report.engine(),
                report.models(), report.nodeVoltages(), report.pinCurrents(), report.componentPower(),
                pick.apply(report.time()), voltages, currents, report.logicLevels(), report.logicSeries(), warnings,
                report.diagnostics());
    }

    // ---- Tool plumbing ----

    private static Arg req(String name, Map<String, Object> schema) {
        return new Arg(name, schema, true);
    }

    private static Arg opt(String name, Map<String, Object> schema) {
        return new Arg(name, schema, false);
    }

    private void add(List<SyncToolSpecification> tools, String name, String title, String description, Kind kind,
                     List<Arg> arguments, Function<Args, Object> handler) {
        var properties = new LinkedHashMap<String, Object>();
        arguments.forEach(a -> properties.put(a.name(), a.schema()));
        var required = arguments.stream().filter(Arg::required).map(Arg::name).toList();
        String schema = serialize(Map.of("type", "object", "properties", properties, "required", required,
                "additionalProperties", false));
        var annotations = new McpSchema.ToolAnnotations(title, kind == Kind.READ, kind == Kind.DESTRUCTIVE
                || name.equals("project.apply_changes"), kind == Kind.READ, false, null);
        var tool = McpSchema.Tool.builder().name(name).title(title).description(description)
                .inputSchema(new JacksonMcpJsonMapper(mapper), schema).annotations(annotations).build();
        tools.add(SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> values = request.arguments() == null ? Map.of() : request.arguments();
            try {
                for (String key : values.keySet()) {
                    if (!properties.containsKey(key)) {
                        throw new IllegalArgumentException("Unknown argument '" + key + "'; allowed: "
                                + properties.keySet());
                    }
                }
                for (String key : required) {
                    if (values.get(key) == null) {
                        throw new IllegalArgumentException("Missing required argument '" + key + "'");
                    }
                }
                return result(Map.of("data", handler.apply(new Args(values))), false);
            } catch (ConnectorException e) {
                return result(e.error(), true);
            } catch (PolicyException e) {
                return result(new ApiError("POLICY_DENIED", e.getMessage(), null), true);
            } catch (IllegalArgumentException e) {
                return result(new ApiError("INVALID_ARGUMENT", e.getMessage(), null), true);
            } catch (FeignException e) {
                return result(new ApiError("SERVER_UNAVAILABLE",
                        "Cannot contact the configured server; check URL, server state and credentials.", null), true);
            }
        }).build());
    }

    private McpSchema.CallToolResult result(Object value, boolean error) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(serialize(value)))).isError(error).build();
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize MCP response", e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.toString() : root.getMessage();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    // ---- JSON schema for operations, derived from the shared DTO records ----

    static Map<String, Object> operationsSchema() {
        var variants = new ArrayList<Object>();
        for (JsonSubTypes.Type type : Operation.class.getAnnotation(JsonSubTypes.class).value()) {
            var object = new LinkedHashMap<>(recordSchema(type.value()));
            @SuppressWarnings("unchecked")
            var properties = new LinkedHashMap<>((Map<String, Object>) object.get("properties"));
            properties.put("op", Map.of("const", type.name()));
            var required = new ArrayList<String>();
            required.add("op");
            @SuppressWarnings("unchecked")
            var rest = (List<String>) object.get("required");
            required.addAll(rest);
            object.put("properties", properties);
            object.put("required", required);
            variants.add(object);
        }
        return Map.of("type", "array", "minItems", 1, "maxItems", 500, "items", Map.of("oneOf", variants),
                "description", "Operations applied in order as one atomic revision.");
    }

    private static Map<String, Object> recordSchema(Class<?> type) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (RecordComponent component : type.getRecordComponents()) {
            properties.put(component.getName(), schema(component.getGenericType()));
            var accessor = component.getAccessor();
            if (component.getType().isPrimitive() || accessor.isAnnotationPresent(NotBlank.class)
                    || accessor.isAnnotationPresent(NotNull.class)) {
                required.add(component.getName());
            }
        }
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> schema(Type type) {
        if (type == String.class) {
            return Map.of("type", "string");
        }
        if (type == UUID.class) {
            return UUID_SCHEMA;
        }
        if (type == double.class || type == Double.class) {
            return NUMBER;
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return Map.of("type", "integer");
        }
        if (type == boolean.class || type == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == List.class) {
                return Map.of("type", "array", "items", schema(arguments[0]));
            }
            if (raw == Map.class) {
                return Map.of("type", "object", "additionalProperties", schema(arguments[1]));
            }
        }
        if (type instanceof Class<?> record && record.isRecord()) {
            return recordSchema(record);
        }
        throw new IllegalStateException("No JSON schema mapping for " + type);
    }

    private static final class PolicyException extends RuntimeException {
        PolicyException(String message) {
            super(message);
        }
    }

    /** Typed, validated access to raw tool arguments. */
    private static final class Args {
        private final Map<String, Object> values;

        Args(Map<String, Object> values) {
            this.values = values;
        }

        boolean has(String key) {
            return values.get(key) != null;
        }

        UUID uuid(String key) {
            Object value = values.get(key);
            if (!(value instanceof String text) || text.length() != 36) {
                throw new IllegalArgumentException(key + " must be a canonical UUID");
            }
            try {
                UUID id = UUID.fromString(text);
                if (id.toString().equalsIgnoreCase(text)) {
                    return id;
                }
            } catch (IllegalArgumentException ignored) {
                // reported below
            }
            throw new IllegalArgumentException(key + " must be a canonical UUID");
        }

        String string(String key) {
            String value = optionalString(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(key + " must be a non-blank string");
            }
            return value;
        }

        String optionalString(String key) {
            Object value = values.get(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof String text) || text.length() > 240) {
                throw new IllegalArgumentException(key + " must be a string of at most 240 characters");
            }
            return text;
        }

        double number(String key) {
            Double value = optionalNumber(key);
            if (value == null) {
                throw new IllegalArgumentException(key + " must be a number");
            }
            return value;
        }

        Double optionalNumber(String key) {
            Object value = values.get(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException(key + " must be a finite number");
            }
            return number.doubleValue();
        }

        Integer optionalInteger(String key) {
            return has(key) ? integer(key, Integer.MIN_VALUE, Integer.MAX_VALUE, 0) : null;
        }

        int integer(String key, int minimum, int maximum, int fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != Math.rint(number.doubleValue())
                    || number.doubleValue() < minimum || number.doubleValue() > maximum) {
                throw new IllegalArgumentException(key + " must be an integer between " + minimum + " and " + maximum);
            }
            return number.intValue();
        }

        long longValue(String key) {
            Object value = values.get(key);
            if (!(value instanceof Number number) || number.doubleValue() < 0
                    || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new IllegalArgumentException(key + " must be a non-negative integer");
            }
            return number.longValue();
        }

        boolean bool(String key) {
            if (!(values.get(key) instanceof Boolean value)) {
                throw new IllegalArgumentException(key + " must be a boolean");
            }
            return value;
        }

        List<String> strings(String key) {
            if (!(values.get(key) instanceof List<?> list)) {
                throw new IllegalArgumentException(key + " must be an array of strings");
            }
            return list.stream().map(item -> {
                if (!(item instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException(key + " must contain only non-blank strings");
                }
                return text;
            }).toList();
        }

        Map<String, String> properties(String key) {
            if (!(values.get(key) instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(key + " must be an object of string values");
            }
            var result = new LinkedHashMap<String, String>();
            map.forEach((k, v) -> {
                if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)) {
                    throw new IllegalArgumentException(key + "." + k + " must be a string");
                }
                result.put(String.valueOf(k), String.valueOf(v));
            });
            return result;
        }

        <T> List<T> page(List<T> items) {
            int offset = integer("offset", 0, Integer.MAX_VALUE, 0);
            int limit = integer("limit", 1, 100, 50);
            return items.stream().skip(offset).limit(limit).toList();
        }
    }
}
