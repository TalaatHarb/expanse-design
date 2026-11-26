package net.talaatharb.expanse.desktop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.talaatharb.expanse.dto.ApiModels.*;

/**
 * Schematic copy/paste without any server round trip: a copy captures the selected components with the connectivity,
 * wires and pin labels among them; a paste turns the clip into one change set (so one undo step) that places renumbered
 * copies, reconnects the copied pins into new nets, redraws the internal wires and adds any project parts the target
 * project lacks. Pin labels are copied too, so a labelled pin joins the net of that name, as in any EDA tool.
 */
final class SchematicClipboard {
    /** A copied selection. {@code originX/Y} is the top-left component position, the anchor for pasting at a point. */
    record Clip(List<ComponentView> components, List<NetView> nets, List<PartSpec> parts, double originX,
                double originY) {
    }

    /** The change set for a paste and the references of the new components, in clip order. */
    record Paste(List<Operation> operations, List<String> references) {
    }

    private SchematicClipboard() {
    }

    /** Returns null when none of {@code ids} is a component of the snapshot. */
    static Clip copy(Snapshot snapshot, Collection<UUID> ids) {
        var wanted = new HashSet<>(ids);
        List<ComponentView> components = snapshot.components().stream().filter(c -> wanted.contains(c.id())).toList();
        if (components.isEmpty()) {
            return null;
        }
        var pins = new HashSet<UUID>();
        components.forEach(c -> c.pins().forEach(p -> pins.add(p.id())));
        var nets = new ArrayList<NetView>();
        for (NetView net : snapshot.nets()) {
            List<UUID> copied = net.pinIds().stream().filter(pins::contains).toList();
            List<NetLabelView> labels = net.labels().stream()
                    .filter(l -> l.pinId() != null && pins.contains(l.pinId())).toList();
            if (copied.size() < 2 && labels.isEmpty()) {
                continue;
            }
            List<WireView> wires = net.wires().stream()
                    .filter(w -> (w.fromPinId() != null || w.toPinId() != null)
                            && (w.fromPinId() == null || pins.contains(w.fromPinId()))
                            && (w.toPinId() == null || pins.contains(w.toPinId())))
                    .toList();
            nets.add(new NetView(net.id(), net.name(), copied, wires, labels));
        }
        var definitions = new HashSet<String>();
        components.forEach(c -> definitions.add(c.definitionId()));
        List<PartSpec> parts = snapshot.parts() == null ? List.of()
                : snapshot.parts().stream().filter(p -> definitions.contains(p.id())).toList();
        double originX = components.stream().mapToDouble(ComponentView::xMm).min().orElse(0);
        double originY = components.stream().mapToDouble(ComponentView::yMm).min().orElse(0);
        return new Clip(components, nets, parts, originX, originY);
    }

    /** Plans pasting {@code clip} into {@code target} shifted by {@code dx}/{@code dy} millimetres. */
    static Paste paste(Clip clip, Snapshot target, double dx, double dy) {
        var operations = new ArrayList<Operation>();
        var known = new HashSet<String>();
        if (target.parts() != null) {
            target.parts().forEach(p -> known.add(p.id()));
        }
        clip.parts().stream().filter(p -> !known.contains(p.id())).forEach(p -> operations.add(new DefinePartOp(p)));

        var used = new HashSet<String>();
        target.components().forEach(c -> used.add(c.reference()));
        var pinSelectors = new HashMap<UUID, String>();
        var references = new ArrayList<String>();
        for (ComponentView c : clip.components()) {
            String reference = nextReference(prefix(c.reference()), used);
            used.add(reference);
            references.add(reference);
            c.pins().forEach(p -> pinSelectors.put(p.id(), reference + "." + p.number()));
            operations.add(new PlaceOp(c.definitionId(), reference, c.xMm() + dx, c.yMm() + dy, c.rotation(),
                    new LinkedHashMap<>(c.properties()), null));
        }
        var connectedPins = new HashSet<UUID>();
        for (NetView net : clip.nets()) {
            if (net.pinIds().size() >= 2) {
                operations.add(new ConnectOp(null, net.pinIds().stream().map(pinSelectors::get).toList()));
                connectedPins.addAll(net.pinIds());
            }
        }
        for (NetView net : clip.nets()) {
            for (NetLabelView label : net.labels()) {
                operations.add(new PlaceNetLabelOp(net.name(), pinSelectors.get(label.pinId()), null, null, null));
                connectedPins.add(label.pinId());
            }
        }
        for (NetView net : clip.nets()) {
            for (WireView wire : net.wires()) {
                UUID anchor = wire.fromPinId() != null ? wire.fromPinId() : wire.toPinId();
                if (!connectedPins.contains(anchor)) {
                    continue;
                }
                var points = wire.points().stream().map(p -> new PointView(p.xMm() + dx, p.yMm() + dy)).toList();
                operations.add(new DrawWireOp(selector(pinSelectors, wire.fromPinId()),
                        selector(pinSelectors, wire.toPinId()), null, points, null));
            }
        }
        return new Paste(operations, references);
    }

    private static String selector(Map<UUID, String> selectors, UUID pin) {
        return pin == null ? null : selectors.get(pin);
    }

    /** {@code R12} → {@code R}; a reference without a letter prefix falls back to {@code X}. */
    static String prefix(String reference) {
        int end = reference.length();
        while (end > 0 && Character.isDigit(reference.charAt(end - 1))) {
            end--;
        }
        return end == 0 ? "X" : reference.substring(0, end);
    }

    static String nextReference(String prefix, Set<String> used) {
        int max = 0;
        for (String reference : used) {
            if (reference.startsWith(prefix) && reference.length() > prefix.length()
                    && reference.substring(prefix.length()).chars().allMatch(Character::isDigit)
                    && reference.length() - prefix.length() < 9) {
                max = Math.max(max, Integer.parseInt(reference.substring(prefix.length())));
            }
        }
        return prefix + (max + 1);
    }
}
