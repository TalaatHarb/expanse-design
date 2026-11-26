package net.talaatharb.expanse.schematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Definition;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Pin;
import net.talaatharb.expanse.domain.Design.Position;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Trace;
import net.talaatharb.expanse.domain.Design.Wire;
import net.talaatharb.expanse.domain.DesignException;

/** Pure schematic commands. Every method returns a new, fully validated project at the next revision. */
public final class SchematicEditor {
    public Project place(Project project, UUID sheetId, Definition definition, String reference,
                         double xMm, double yMm, int rotation, Map<String, String> properties) {
        require(project.components().stream().noneMatch(c -> c.reference().equals(reference)),
                "REFERENCE_EXISTS", "Reference " + reference + " is already used");
        Map<String, String> values = new HashMap<>(definition.defaults());
        properties.forEach((key, value) -> {
            definition.parameter(key);
            values.put(key, value);
        });
        var components = new ArrayList<>(project.components());
        components.add(new Component(UUID.randomUUID(), sheetId, reference, definition,
                new Position(xMm, yMm, "mm"), rotation, values, definition.pins().stream()
                .map(p -> new Pin(UUID.randomUUID(), p.number())).toList()));
        return project.edited(components, project.nets(), project.board());
    }

    public Project move(Project project, UUID componentId, double xMm, double yMm, int rotation) {
        return replace(project, componentId, c -> new Component(c.id(), c.sheetId(), c.reference(), c.definition(),
                new Position(xMm, yMm, "mm"), rotation, c.properties(), c.pins()));
    }

    public Project setProperties(Project project, UUID componentId, Map<String, String> properties) {
        Design.require(!properties.isEmpty(), "Supply at least one property");
        return replace(project, componentId, c -> {
            Map<String, String> values = new HashMap<>(c.properties());
            properties.forEach((key, value) -> {
                c.definition().parameter(key);
                values.put(key, value);
            });
            return new Component(c.id(), c.sheetId(), c.reference(), c.definition(), c.position(), c.rotation(),
                    values, c.pins());
        });
    }

    public Project setReference(Project project, UUID componentId, String reference) {
        require(project.components().stream().noneMatch(c -> c.reference().equals(reference)
                && !c.id().equals(componentId)), "REFERENCE_EXISTS", "Reference " + reference + " is already used");
        return replace(project, componentId, c -> new Component(c.id(), c.sheetId(), reference, c.definition(),
                c.position(), c.rotation(), c.properties(), c.pins()));
    }

    public Project delete(Project project, UUID componentId) {
        Component removed = project.component(componentId);
        Set<UUID> pins = new HashSet<>(removed.pins().stream().map(Pin::id).toList());
        var components = project.components().stream().filter(c -> !c.id().equals(componentId)).toList();
        var placements = project.board().placements().stream()
                .filter(p -> !p.componentId().equals(componentId)).toList();
        return withNets(project, components, shrink(project.nets(), pins), placements);
    }

    public Project connect(Project project, String name, List<UUID> selectedPins) {
        Design.require(selectedPins != null && selectedPins.size() >= 2, "Select at least two pins");
        var members = new LinkedHashSet<>(selectedPins);
        Design.require(members.size() == selectedPins.size(), "Select distinct pins");
        var merged = project.nets().stream()
                .filter(n -> n.pinIds().stream().anyMatch(members::contains)).toList();
        merged.forEach(n -> members.addAll(n.pinIds()));
        var nets = new ArrayList<>(project.nets());
        nets.removeAll(merged);
        UUID id = merged.isEmpty() ? UUID.randomUUID() : merged.getFirst().id();
        nets.add(new Net(id, name, members.stream().sorted().toList(),
                merged.stream().flatMap(n -> n.wires().stream()).toList()));
        Set<UUID> mergedIds = new HashSet<>(merged.stream().map(Net::id).toList());
        var traces = project.board().traces().stream().map(t -> mergedIds.contains(t.netId())
                ? new Trace(t.id(), id, t.layer(), t.widthMm(), t.points()) : t).toList();
        return project.edited(project.components(), nets, project.board().with(project.board().placements(), traces));
    }

    public Project disconnect(Project project, UUID pinId) {
        require(project.nets().stream().anyMatch(n -> n.pinIds().contains(pinId)), "PIN_NOT_CONNECTED",
                "The pin is not connected to a net");
        return withNets(project, project.components(), shrink(project.nets(), Set.of(pinId)),
                project.board().placements());
    }

    public Project renameNet(Project project, UUID netId, String name) {
        require(project.nets().stream().anyMatch(n -> n.id().equals(netId)), "NET_NOT_FOUND", "Net not found: " + netId);
        var nets = project.nets().stream().map(n -> n.id().equals(netId) ? new Net(n.id(), name, n.pinIds(), n.wires()) : n).toList();
        return project.edited(project.components(), nets, project.board());
    }

    /** Adds wire graphics to an existing net. Every attached wire end must be a pin of that net. */
    public Project addWire(Project project, UUID netId, Wire wire) {
        require(project.nets().stream().anyMatch(n -> n.id().equals(netId)), "NET_NOT_FOUND", "Net not found: " + netId);
        var nets = project.nets().stream().map(n -> {
            if (!n.id().equals(netId)) {
                return n;
            }
            var wires = new ArrayList<>(n.wires());
            wires.add(wire);
            return n.withWires(wires);
        }).toList();
        return project.edited(project.components(), nets, project.board());
    }

    /** Removes wire graphics only; the net's connectivity is unchanged. */
    public Project deleteWire(Project project, UUID wireId) {
        require(project.nets().stream().anyMatch(n -> n.wires().stream().anyMatch(w -> w.id().equals(wireId))),
                "WIRE_NOT_FOUND", "Wire not found: " + wireId);
        var nets = project.nets().stream().map(n -> n.wires().stream().anyMatch(w -> w.id().equals(wireId))
                ? n.withWires(n.wires().stream().filter(w -> !w.id().equals(wireId)).toList()) : n).toList();
        return project.edited(project.components(), nets, project.board());
    }

    /**
     * Stores the project's custom parts and re-pins every instance of {@code definition} to it. Pin IDs are kept by
     * pin number, pins that no longer exist leave their nets (dropping attached wires), property values that remain
     * valid are kept and board placements are dropped when the footprint becomes schematic-only.
     */
    public Project redefine(Project project, List<Definition> parts, Definition definition) {
        Set<UUID> removedPins = new HashSet<>();
        Set<UUID> unplaced = new HashSet<>();
        var components = project.components().stream().map(c -> {
            if (!c.definition().id().equals(definition.id())) {
                return c;
            }
            Map<String, UUID> byNumber = new HashMap<>();
            c.pins().forEach(p -> byNumber.put(p.number(), p.id()));
            var pins = definition.pins().stream().map(d -> new Pin(byNumber.getOrDefault(d.number(), UUID.randomUUID()),
                    d.number())).toList();
            Set<String> kept = new HashSet<>(definition.pins().stream().map(Design.PinDefinition::number).toList());
            c.pins().stream().filter(p -> !kept.contains(p.number())).forEach(p -> removedPins.add(p.id()));
            Map<String, String> values = new HashMap<>(definition.defaults());
            for (var parameter : definition.parameters()) {
                String old = c.properties().get(parameter.name());
                if (old != null && valid(parameter, old)) {
                    values.put(parameter.name(), old);
                }
            }
            if (!definition.footprint().physical()) {
                unplaced.add(c.id());
            }
            return new Component(c.id(), c.sheetId(), c.reference(), definition, c.position(), c.rotation(), values,
                    pins);
        }).toList();
        var nets = shrink(project.nets(), removedPins);
        Set<UUID> netIds = new HashSet<>(nets.stream().map(Net::id).toList());
        var placements = project.board().placements().stream().filter(p -> !unplaced.contains(p.componentId()))
                .toList();
        var traces = project.board().traces().stream().filter(t -> netIds.contains(t.netId())).toList();
        return project.withParts(parts, components, nets, project.board().with(placements, traces));
    }

    private static boolean valid(Design.ParameterDefinition parameter, String value) {
        try {
            parameter.check(value);
            return true;
        } catch (DesignException e) {
            return false;
        }
    }

    private static List<Net> shrink(List<Net> nets, Set<UUID> removedPins) {
        return nets.stream().map(n -> {
            var remaining = n.pinIds().stream().filter(p -> !removedPins.contains(p)).toList();
            if (remaining.size() == n.pinIds().size()) {
                return n;
            }
            return remaining.size() < 2 ? null : new Net(n.id(), n.name(), remaining, n.wires().stream()
                    .filter(w -> removedPins.stream().noneMatch(w::touches)).toList());
        }).filter(n -> n != null).toList();
    }

    private static Project withNets(Project project, List<Component> components, List<Net> nets,
                                    List<Design.Placement> placements) {
        Set<UUID> netIds = new HashSet<>(nets.stream().map(Net::id).toList());
        var traces = project.board().traces().stream().filter(t -> netIds.contains(t.netId())).toList();
        return project.edited(components, nets, project.board().with(placements, traces));
    }

    private static Project replace(Project project, UUID componentId,
                                   java.util.function.UnaryOperator<Component> change) {
        Component current = project.component(componentId);
        var components = project.components().stream().map(c -> c == current ? change.apply(c) : c).toList();
        return project.edited(components, project.nets(), project.board());
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new DesignException(code, message);
        }
    }
}
