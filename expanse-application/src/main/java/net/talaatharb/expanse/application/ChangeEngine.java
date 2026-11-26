package net.talaatharb.expanse.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Board;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Wire;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.dto.ApiModels.*;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.pcb.BoardEditor;
import net.talaatharb.expanse.schematic.SchematicEditor;

/** Applies a batch of operations in memory. The result is a single revision after the base project. */
public final class ChangeEngine {
    public static final double DEFAULT_TRACE_WIDTH_MM = 0.4;

    public record Outcome(Project project, List<String> changes, List<CreatedObject> created) {
    }

    private final ComponentLibrary library;
    private final SchematicEditor editor;
    private final BoardEditor board;

    public ChangeEngine(ComponentLibrary library, SchematicEditor editor, BoardEditor board) {
        this.library = library;
        this.editor = editor;
        this.board = board;
    }

    public Outcome apply(Project base, List<Operation> operations) {
        Design.require(operations != null && !operations.isEmpty() && operations.size() <= 500,
                "A change set needs 1 to 500 operations");
        Project project = base;
        List<String> changes = new ArrayList<>();
        List<CreatedObject> created = new ArrayList<>();
        int index = 0;
        for (Operation operation : operations) {
            try {
                project = apply(project, operation, changes, created);
            } catch (DesignException e) {
                throw new DesignException(e.code(), "Operation " + (index + 1) + " (" + name(operation) + "): "
                        + e.getMessage(), e.currentRevision());
            }
            index++;
        }
        return new Outcome(project.committed(base.revision()), List.copyOf(changes), List.copyOf(created));
    }

    private Project apply(Project p, Operation operation, List<String> changes, List<CreatedObject> created) {
        Objects.requireNonNull(operation);
        return switch (operation) {
            case PlaceOp op -> {
                Design.require(op.definitionId() != null && op.reference() != null,
                        "place_component requires definitionId and reference");
                UUID sheet = op.sheetId() == null ? p.sheets().getFirst().id() : op.sheetId();
                Project next = editor.place(p, sheet, definition(p, op.definitionId()), op.reference(), op.xMm(),
                        op.yMm(), op.rotation() == null ? 0 : op.rotation(),
                        op.properties() == null ? java.util.Map.of() : op.properties());
                Component c = next.component(op.reference());
                created.add(new CreatedObject("component", c.reference(), c.id()));
                changes.add("Placed " + c.reference() + " (" + c.definition().name() + ")");
                yield next;
            }
            case MoveOp op -> {
                Component c = component(p, op.component());
                changes.add("Moved " + c.reference());
                yield editor.move(p, c.id(), op.xMm(), op.yMm(), op.rotation() == null ? c.rotation() : op.rotation());
            }
            case SetPropertiesOp op -> {
                Component c = component(p, op.component());
                Design.require(op.properties() != null && !op.properties().isEmpty(), "No properties given");
                changes.add("Set " + op.properties() + " on " + c.reference());
                yield editor.setProperties(p, c.id(), op.properties());
            }
            case SetReferenceOp op -> {
                Component c = component(p, op.component());
                changes.add("Renamed " + c.reference() + " to " + op.reference());
                yield editor.setReference(p, c.id(), op.reference());
            }
            case DeleteComponentOp op -> {
                Component c = component(p, op.component());
                changes.add("Deleted " + c.reference());
                yield editor.delete(p, c.id());
            }
            case ConnectOp op -> {
                Design.require(op.pins() != null && op.pins().size() >= 2, "connect_pins requires at least two pins");
                List<UUID> pins = op.pins().stream().map(s -> pin(p, s)).toList();
                Project next = editor.connect(p, netName(p, op.name(), pins), pins);
                Net net = next.nets().stream().filter(n -> n.pinIds().contains(pins.getFirst())).findFirst()
                        .orElseThrow();
                created.add(new CreatedObject("net", net.name(), net.id()));
                changes.add("Connected " + String.join(", ", op.pins()) + " as " + net.name());
                yield next;
            }
            case DisconnectOp op -> {
                changes.add("Disconnected " + op.pin());
                yield editor.disconnect(p, pin(p, op.pin()));
            }
            case RenameNetOp op -> {
                Net net = net(p, op.net());
                changes.add("Renamed net " + net.name() + " to " + op.name());
                yield editor.renameNet(p, net.id(), op.name());
            }
            case RenameProjectOp op -> {
                changes.add("Renamed project to " + op.name());
                yield p.renamed(op.name());
            }
            case SyncBoardOp ignored -> {
                Project next = board.syncFromSchematic(p);
                int added = next.board().placements().size() - p.board().placements().size();
                changes.add("Synchronized board: placed " + added + " footprint(s)");
                yield next;
            }
            case PlaceFootprintOp op -> {
                Component c = component(p, op.component());
                int rotation = op.rotation() != null ? op.rotation() : p.board().placements().stream()
                        .filter(pl -> pl.componentId().equals(c.id())).mapToInt(Design.Placement::rotation)
                        .findFirst().orElse(0);
                changes.add("Placed footprint " + c.reference() + " at (" + op.xMm() + ", " + op.yMm() + ") mm");
                yield board.place(p, c.id(), op.xMm(), op.yMm(), rotation);
            }
            case UnplaceFootprintOp op -> {
                Component c = component(p, op.component());
                changes.add("Removed footprint " + c.reference() + " from the board");
                yield board.unplace(p, c.id());
            }
            case SetBoardOp op -> {
                Board b = p.board();
                changes.add("Updated board settings");
                yield board.setBoard(p, or(op.widthMm(), b.widthMm()), or(op.heightMm(), b.heightMm()),
                        or(op.thicknessMm(), b.thicknessMm()), or(op.clearanceMm(), b.clearanceMm()));
            }
            case RouteOp op -> {
                UUID id = UUID.randomUUID();
                Project next = board.route(p, id, pin(p, op.from()), pin(p, op.to()),
                        op.layer() == null ? "top" : op.layer(), or(op.widthMm(), DEFAULT_TRACE_WIDTH_MM));
                created.add(new CreatedObject("trace", op.from() + "-" + op.to(), id));
                changes.add("Routed " + op.from() + " to " + op.to());
                yield next;
            }
            case AddTraceOp op -> {
                UUID id = UUID.randomUUID();
                Net net = net(p, op.net());
                Design.require(op.points() != null, "add_trace requires points");
                Project next = board.addTrace(p, id, net.id(), op.layer() == null ? "top" : op.layer(),
                        or(op.widthMm(), DEFAULT_TRACE_WIDTH_MM),
                        op.points().stream().map(pt -> new Point(pt.xMm(), pt.yMm())).toList());
                created.add(new CreatedObject("trace", net.name(), id));
                changes.add("Added trace on " + net.name());
                yield next;
            }
            case DeleteTraceOp op -> {
                changes.add("Deleted trace " + op.traceId());
                yield board.deleteTrace(p, op.traceId());
            }
            case DrawWireOp op -> drawWire(p, op, changes, created);
            case DeleteWireOp op -> {
                changes.add("Deleted wire " + op.wireId());
                yield editor.deleteWire(p, op.wireId());
            }
            case DefinePartOp op -> {
                Design.Definition part = PartSpecs.toDefinition(op.part(), library);
                var parts = new ArrayList<>(p.parts().stream().filter(d -> !d.id().equals(part.id())).toList());
                boolean update = parts.size() < p.parts().size();
                parts.add(part);
                long instances = p.components().stream().filter(c -> c.definition().id().equals(part.id())).count();
                created.add(new CreatedObject("part", part.id(), null));
                changes.add((update ? "Updated" : "Defined") + " part " + part.id() + " (" + part.name() + ")"
                        + (instances > 0 ? "; re-pinned " + instances + " instance(s)" : ""));
                yield editor.redefine(p, parts, part);
            }
            case DeletePartOp op -> {
                String id = PartSpecs.id(op.partId());
                if (p.parts().stream().noneMatch(d -> d.id().equals(id))) {
                    throw new DesignException("PART_NOT_FOUND", "Project part not found: " + id);
                }
                var users = p.components().stream().filter(c -> c.definition().id().equals(id))
                        .map(Component::reference).toList();
                if (!users.isEmpty()) {
                    throw new DesignException("PART_IN_USE", id + " is used by " + String.join(", ", users)
                            + "; delete those components first");
                }
                changes.add("Deleted part " + id);
                yield p.withParts(p.parts().stream().filter(d -> !d.id().equals(id)).toList(), p.components(),
                        p.nets(), p.board());
            }
        };
    }

    private Project drawWire(Project p, DrawWireOp op, List<String> changes, List<CreatedObject> created) {
        UUID from = blank(op.from()) ? null : pin(p, op.from());
        UUID to = blank(op.to()) ? null : pin(p, op.to());
        Net target = blank(op.net()) ? null : net(p, op.net());
        var involved = new java.util.LinkedHashSet<UUID>();
        if (from != null) {
            involved.add(from);
        }
        if (to != null) {
            involved.add(to);
        }
        if (target != null) {
            involved.add(target.pinIds().getFirst());
        }
        Design.require(!involved.isEmpty(), "draw_wire needs a pin at one end or a net to draw on");
        List<UUID> pins = List.copyOf(involved);
        Project next = p;
        boolean joined = pins.size() >= 2 && p.nets().stream().noneMatch(n -> n.pinIds().containsAll(pins));
        if (joined) {
            boolean existing = p.nets().stream().anyMatch(n -> pins.stream().anyMatch(n.pinIds()::contains));
            next = editor.connect(p, netName(p, existing ? null : op.name(), pins), pins);
        }
        final Project connected = next;
        Net net = connected.nets().stream().filter(n -> n.pinIds().contains(pins.getFirst())).findFirst()
                .orElseThrow(() -> new DesignException("PIN_NOT_CONNECTED", "A wire from an unconnected pin must "
                        + "end on another pin or on a net"));
        UUID id = UUID.randomUUID();
        var points = op.points() == null ? List.<Point>of()
                : op.points().stream().map(pt -> new Point(pt.xMm(), pt.yMm())).toList();
        created.add(new CreatedObject("wire", net.name(), id));
        changes.add("Drew wire " + (op.from() == null ? "(free)" : op.from()) + " to "
                + (op.to() == null ? "(free)" : op.to()) + " on " + net.name() + (joined ? " (connected)" : ""));
        return editor.addWire(connected, net.id(), new Wire(id, from, to, points));
    }

    /** Project parts take precedence over library parts. */
    public Design.Definition definition(Project project, String id) {
        return project.parts().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseGet(() -> library.get(id));
    }

    private static String netName(Project p, String requested, List<UUID> pins) {
        return requested != null && !requested.isBlank() ? requested
                : p.nets().stream().filter(n -> pins.stream().anyMatch(n.pinIds()::contains))
                .map(Net::name).findFirst().orElse(defaultNetName(p, pins.getFirst()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static Component component(Project project, String selector) {
        Design.require(selector != null && !selector.isBlank(), "A component reference or ID is required");
        UUID id = uuid(selector);
        return id != null ? project.component(id) : project.component(selector);
    }

    /** Resolves a pin by UUID or {@code REF.PIN} where PIN is the pin number or name. */
    public static UUID pin(Project project, String selector) {
        Design.require(selector != null && !selector.isBlank(), "A pin reference or ID is required");
        UUID id = uuid(selector);
        if (id != null) {
            Design.require(project.components().stream().flatMap(c -> c.pins().stream())
                    .anyMatch(pin -> pin.id().equals(id)), "Pin not found: " + selector);
            return id;
        }
        int dot = selector.lastIndexOf('.');
        if (dot <= 0 || dot == selector.length() - 1) {
            throw new DesignException("PIN_NOT_FOUND", "Use REF.PIN (for example R1.1 or Q1.B) or a pin UUID: "
                    + selector);
        }
        return project.component(selector.substring(0, dot)).pin(selector.substring(dot + 1)).id();
    }

    public static Net net(Project project, String selector) {
        Design.require(selector != null && !selector.isBlank(), "A net name or ID is required");
        UUID id = uuid(selector);
        var matches = project.nets().stream().filter(n -> id != null ? n.id().equals(id) : n.name().equals(selector))
                .toList();
        if (matches.isEmpty()) {
            throw new DesignException("NET_NOT_FOUND", "Net not found: " + selector);
        }
        if (matches.size() > 1) {
            throw new DesignException("NET_AMBIGUOUS", "Several nets are named " + selector + "; use the net ID");
        }
        return matches.getFirst();
    }

    private static String defaultNetName(Project project, UUID pinId) {
        var owner = project.components().stream().filter(c -> c.pins().stream().anyMatch(p -> p.id().equals(pinId)))
                .findFirst().orElseThrow();
        String number = owner.pins().stream().filter(p -> p.id().equals(pinId)).findFirst().orElseThrow().number();
        var names = new HashMap<String, Boolean>();
        project.nets().forEach(n -> names.put(n.name(), true));
        String base = "N_" + owner.reference() + "_" + number;
        String name = base;
        for (int i = 2; names.containsKey(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    static String name(Operation operation) {
        for (var type : Operation.class.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class).value()) {
            if (type.value().equals(operation.getClass())) {
                return type.name();
            }
        }
        return operation.getClass().getSimpleName();
    }

    private static double or(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static UUID uuid(String value) {
        if (value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
