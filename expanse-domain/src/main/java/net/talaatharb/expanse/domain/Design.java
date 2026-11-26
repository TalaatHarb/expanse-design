package net.talaatharb.expanse.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Design {
    public static final String SCHEMA_VERSION = "0.2";
    public static final Set<String> KINDS = Set.of("resistor", "capacitor", "inductor", "voltage-source",
            "power-rail", "ground", "switch", "npn", "pnp", "nmos", "pmos", "logic-input", "logic-clock", "logic-and",
            "logic-or", "logic-nand", "logic-nor", "logic-xor", "logic-not", "logic-74hc00", "logic-74hc04",
            "logic-74hc08", "logic-74hc32", "logic-74hc86", "logic-74hc74");
    public static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);
    public static final Set<String> LAYERS = Set.of("top", "bottom");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private Design() {
    }

    public record Position(double x, double y, String unit) {
        public Position {
            require(Double.isFinite(x) && Double.isFinite(y), "Coordinates must be finite");
            require(Math.abs(x) <= 100_000 && Math.abs(y) <= 100_000, "Coordinates must be within ±100 m");
            require("mm".equals(unit), "Only millimetre coordinates are supported");
        }
    }

    public record PinDefinition(String number, String name, boolean required) {
        public PinDefinition {
            number = text(number, "Pin number");
            name = text(name, "Pin name");
        }
    }

    /** A typed component parameter. Numeric when {@code allowedValues} is empty, otherwise an enumeration. */
    public record ParameterDefinition(String name, String unit, String defaultValue, List<String> allowedValues) {
        public ParameterDefinition {
            name = text(name, "Parameter name");
            require(unit != null && unit.length() <= 20, "Parameter unit must be at most 20 characters");
            allowedValues = List.copyOf(allowedValues);
            unique(allowedValues, "allowed value");
            check(defaultValue, unit, allowedValues, name);
        }

        public double numeric(String value) {
            require(allowedValues.isEmpty(), "Parameter " + name + " is not numeric");
            return Quantity.parse(value, unit);
        }

        public void check(String value) {
            check(value, unit, allowedValues, name);
        }

        private static void check(String value, String unit, List<String> allowed, String name) {
            require(value != null, "Parameter " + name + " requires a value");
            if (allowed.isEmpty()) {
                Quantity.parse(value, unit);
            } else {
                require(allowed.contains(value), "Parameter " + name + " must be one of " + allowed);
            }
        }
    }

    public record Pad(String number, double xMm, double yMm, double widthMm, double heightMm, double drillMm) {
        public Pad {
            number = text(number, "Pad number");
            require(finite(xMm, yMm) && widthMm > 0 && heightMm > 0 && widthMm <= 100 && heightMm <= 100,
                    "Pads need finite positions and sizes between 0 and 100 mm");
            require(drillMm >= 0 && drillMm < Math.min(widthMm, heightMm), "Drill must fit inside the pad");
        }
    }

    /** A footprint without pads represents a schematic-only (virtual) part, such as ground or a power rail. */
    public record Footprint(String name, List<Pad> pads, double courtyardWidthMm, double courtyardHeightMm) {
        public Footprint {
            name = text(name, "Footprint name");
            pads = List.copyOf(pads);
            unique(pads.stream().map(Pad::number).toList(), "pad number");
            require(courtyardWidthMm >= 0 && courtyardHeightMm >= 0 && courtyardWidthMm <= 500
                    && courtyardHeightMm <= 500, "Courtyard must be between 0 and 500 mm");
        }

        public boolean physical() {
            return !pads.isEmpty();
        }
    }

    public record Body(String shape, double widthMm, double depthMm, double heightMm, String color) {
        public Body {
            require(Set.of("box", "cylinder", "axial", "none").contains(shape),
                    "Body shape must be box, cylinder (vertical), axial (horizontal along X) or none");
            require(widthMm >= 0 && depthMm >= 0 && heightMm >= 0 && widthMm <= 500 && depthMm <= 500
                    && heightMm <= 500, "Body dimensions must be between 0 and 500 mm");
            require(color != null && COLOR.matcher(color).matches(), "Body colour must be #RRGGBB");
        }
    }

    public record Definition(String id, String version, String name, String kind, List<PinDefinition> pins,
                             List<ParameterDefinition> parameters, Footprint footprint, Body body) {
        public Definition {
            id = text(id, "Definition ID");
            version = text(version, "Definition version");
            name = text(name, "Definition name");
            require(KINDS.contains(kind), "Unknown component kind: " + kind);
            pins = List.copyOf(pins);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(footprint, "Footprint");
            Objects.requireNonNull(body, "Body");
            require(!pins.isEmpty(), "A definition must contain pins");
            unique(pins.stream().map(PinDefinition::number).toList(), "definition pin number");
            unique(parameters.stream().map(ParameterDefinition::name).toList(), "parameter name");
            require(!footprint.physical() || new HashSet<>(footprint.pads().stream().map(Pad::number).toList())
                            .equals(new HashSet<>(pins.stream().map(PinDefinition::number).toList())),
                    "Footprint pads must map one-to-one to definition pins");
        }

        public ParameterDefinition parameter(String parameterName) {
            return parameters.stream().filter(p -> p.name().equals(parameterName)).findFirst()
                    .orElseThrow(() -> new DesignException("INVALID_DESIGN",
                            name + " has no parameter " + parameterName));
        }

        public Map<String, String> defaults() {
            Map<String, String> values = new HashMap<>();
            parameters.forEach(p -> values.put(p.name(), p.defaultValue()));
            return values;
        }
    }

    public record Pin(UUID id, String number) {
        public Pin {
            Objects.requireNonNull(id, "Pin ID");
            number = text(number, "Pin number");
        }
    }

    public record Sheet(UUID id, String name) {
        public Sheet {
            Objects.requireNonNull(id, "Sheet ID");
            name = text(name, "Sheet name");
        }
    }

    public record Component(UUID id, UUID sheetId, String reference, Definition definition,
                            Position position, int rotation, Map<String, String> properties, List<Pin> pins) {
        public Component {
            Objects.requireNonNull(id, "Component ID");
            Objects.requireNonNull(sheetId, "Sheet ID");
            Objects.requireNonNull(definition, "Definition snapshot");
            Objects.requireNonNull(position, "Position");
            reference = text(reference, "Component reference");
            require(ROTATIONS.contains(rotation), "Rotation must be 0, 90, 180 or 270 degrees");
            properties = Map.copyOf(properties);
            pins = List.copyOf(pins);
            unique(pins.stream().map(Pin::number).toList(), "component pin number");
            require(new HashSet<>(pins.stream().map(Pin::number).toList())
                    .equals(new HashSet<>(definition.pins().stream().map(PinDefinition::number).toList())),
                    "Instance pins must match the pinned definition");
            require(properties.keySet().equals(new HashSet<>(definition.parameters().stream()
                    .map(ParameterDefinition::name).toList())), "Component properties must match definition parameters");
            for (var parameter : definition.parameters()) {
                parameter.check(properties.get(parameter.name()));
            }
        }

        public Pin pin(String numberOrName) {
            return pins.stream().filter(p -> p.number().equals(numberOrName)).findFirst()
                    .or(() -> definition.pins().stream().filter(d -> d.name().equals(numberOrName)).findFirst()
                            .flatMap(d -> pins.stream().filter(p -> p.number().equals(d.number())).findFirst()))
                    .orElseThrow(() -> new DesignException("PIN_NOT_FOUND",
                            reference + " has no pin " + numberOrName));
        }

        public double value(String parameterName) {
            return definition.parameter(parameterName).numeric(properties.get(parameterName));
        }
    }

    public record Net(UUID id, String name, List<UUID> pinIds) {
        public Net {
            Objects.requireNonNull(id, "Net ID");
            name = text(name, "Net name");
            pinIds = List.copyOf(pinIds);
            require(pinIds.size() >= 2, "A net requires at least two pins");
            unique(pinIds, "net member");
        }
    }

    public record Placement(UUID componentId, double xMm, double yMm, int rotation) {
        public Placement {
            Objects.requireNonNull(componentId, "Placement component");
            require(finite(xMm, yMm) && Math.abs(xMm) <= 10_000 && Math.abs(yMm) <= 10_000,
                    "Placement coordinates must be finite and within ±10 m");
            require(ROTATIONS.contains(rotation), "Rotation must be 0, 90, 180 or 270 degrees");
        }
    }

    public record Point(double xMm, double yMm) {
        public Point {
            require(finite(xMm, yMm) && Math.abs(xMm) <= 10_000 && Math.abs(yMm) <= 10_000,
                    "Trace points must be finite and within ±10 m");
        }
    }

    public record Trace(UUID id, UUID netId, String layer, double widthMm, List<Point> points) {
        public Trace {
            Objects.requireNonNull(id, "Trace ID");
            Objects.requireNonNull(netId, "Trace net");
            require(LAYERS.contains(layer), "Trace layer must be top or bottom");
            require(widthMm >= 0.05 && widthMm <= 20, "Trace width must be between 0.05 and 20 mm");
            points = List.copyOf(points);
            require(points.size() >= 2 && points.size() <= 1000, "A trace requires 2 to 1000 points");
        }
    }

    public record Board(double widthMm, double heightMm, double thicknessMm, double clearanceMm,
                        List<Placement> placements, List<Trace> traces) {
        public Board {
            require(widthMm > 0 && heightMm > 0 && widthMm <= 2000 && heightMm <= 2000,
                    "Board size must be between 0 and 2000 mm");
            require(thicknessMm > 0 && thicknessMm <= 10, "Board thickness must be between 0 and 10 mm");
            require(clearanceMm > 0 && clearanceMm <= 10, "Clearance must be between 0 and 10 mm");
            placements = List.copyOf(placements);
            traces = List.copyOf(traces);
            unique(placements.stream().map(Placement::componentId).toList(), "board placement");
        }

        public static Board empty() {
            return new Board(80, 60, 1.6, 0.2, List.of(), List.of());
        }

        public Board with(List<Placement> nextPlacements, List<Trace> nextTraces) {
            return new Board(widthMm, heightMm, thicknessMm, clearanceMm, nextPlacements, nextTraces);
        }
    }

    public record Project(String schemaVersion, UUID id, String name, long revision,
                          Instant createdAt, Instant modifiedAt, List<Sheet> sheets,
                          List<Component> components, List<Net> nets, Board board, Map<String, String> extensions) {
        public Project {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new DesignException("UNSUPPORTED_SCHEMA", "Unsupported project schema: " + schemaVersion);
            }
            Objects.requireNonNull(id, "Project ID");
            name = text(name, "Project name");
            require(revision >= 0, "Revision cannot be negative");
            Objects.requireNonNull(createdAt, "Creation time");
            Objects.requireNonNull(modifiedAt, "Modification time");
            Objects.requireNonNull(board, "Board");
            require(!modifiedAt.isBefore(createdAt), "Modification precedes creation");
            sheets = List.copyOf(sheets);
            components = List.copyOf(components);
            nets = List.copyOf(nets);
            extensions = Map.copyOf(extensions);
            require(!sheets.isEmpty(), "A project requires a sheet");
            require(components.size() <= 5_000 && nets.size() <= 10_000, "Project exceeds supported object counts");
            Set<UUID> ids = new HashSet<>();
            require(ids.add(id), "Duplicate project ID");
            for (var sheet : sheets) {
                require(ids.add(sheet.id()), "Duplicate entity ID");
            }
            Set<UUID> sheetIds = new HashSet<>(sheets.stream().map(Sheet::id).toList());
            Set<UUID> pinIds = new HashSet<>();
            Map<UUID, Component> byId = new HashMap<>();
            for (var component : components) {
                require(ids.add(component.id()), "Duplicate entity ID");
                byId.put(component.id(), component);
                require(sheetIds.contains(component.sheetId()), "Component references a missing sheet");
                for (var pin : component.pins()) {
                    require(ids.add(pin.id()), "Duplicate entity ID");
                    pinIds.add(pin.id());
                }
            }
            unique(components.stream().map(Component::reference).toList(), "component reference");
            Set<UUID> connected = new HashSet<>();
            Set<UUID> netIds = new HashSet<>();
            for (var net : nets) {
                require(ids.add(net.id()), "Duplicate entity ID");
                netIds.add(net.id());
                for (var pinId : net.pinIds()) {
                    require(pinIds.contains(pinId), "Net references a missing pin");
                    require(connected.add(pinId), "A pin cannot belong to multiple nets");
                }
                long sheetCount = components.stream()
                        .filter(c -> c.pins().stream().anyMatch(p -> net.pinIds().contains(p.id())))
                        .map(Component::sheetId).distinct().count();
                require(sheetCount == 1, "Cross-sheet nets require hierarchy support, which is not yet available");
            }
            for (var placement : board.placements()) {
                Component component = byId.get(placement.componentId());
                require(component != null, "Board placement references a missing component");
                require(component.definition().footprint().physical(),
                        component.reference() + " is schematic-only and cannot be placed on the board");
            }
            for (var trace : board.traces()) {
                require(ids.add(trace.id()), "Duplicate entity ID");
                require(netIds.contains(trace.netId()), "Trace references a missing net");
            }
        }

        public static Project create(String name) {
            Instant now = Instant.now();
            return new Project(SCHEMA_VERSION, UUID.randomUUID(), name, 0, now, now,
                    List.of(new Sheet(UUID.randomUUID(), "Main")), List.of(), List.of(), Board.empty(), Map.of());
        }

        public Project edited(List<Component> nextComponents, List<Net> nextNets, Board nextBoard) {
            return new Project(schemaVersion, id, name, Math.incrementExact(revision), createdAt,
                    Instant.now(), sheets, nextComponents, nextNets, nextBoard, extensions);
        }

        public Project renamed(String nextName) {
            return new Project(schemaVersion, id, nextName, Math.incrementExact(revision), createdAt,
                    Instant.now(), sheets, components, nets, board, extensions);
        }

        /** Collapses any number of intermediate edits into a single revision after {@code baseRevision}. */
        public Project committed(long baseRevision) {
            return new Project(schemaVersion, id, name, Math.incrementExact(baseRevision), createdAt,
                    Instant.now(), sheets, components, nets, board, extensions);
        }

        public Project withBoard(Board nextBoard) {
            return new Project(schemaVersion, id, name, Math.incrementExact(revision), createdAt,
                    Instant.now(), sheets, components, nets, nextBoard, extensions);
        }

        public Component component(UUID componentId) {
            return components.stream().filter(c -> c.id().equals(componentId)).findFirst()
                    .orElseThrow(() -> new DesignException("COMPONENT_NOT_FOUND", "Component not found: " + componentId));
        }

        public Component component(String reference) {
            return components.stream().filter(c -> c.reference().equals(reference)).findFirst()
                    .orElseThrow(() -> new DesignException("COMPONENT_NOT_FOUND", "Component not found: " + reference));
        }
    }

    public static String text(String value, String label) {
        require(value != null && !value.isBlank() && value.length() <= 200,
                label + " must contain 1 to 200 non-blank characters");
        return value;
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new DesignException("INVALID_DESIGN", message);
        }
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static void unique(List<?> values, String label) {
        require(new HashSet<>(values).size() == values.size(), "Duplicate " + label);
    }
}
