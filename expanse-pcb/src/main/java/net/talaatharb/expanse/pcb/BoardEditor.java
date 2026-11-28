package net.talaatharb.expanse.pcb;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Board;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Placement;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Trace;
import net.talaatharb.expanse.domain.Design.Via;
import net.talaatharb.expanse.domain.Design.Zone;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.pcb.PcbGeometry.PlacedPad;
import net.talaatharb.expanse.pcb.PcbGeometry.Rect;

/** Pure board commands; each returns a new validated project. */
public final class BoardEditor {
    private static final double GRID = 2.54;

    public Project setBoard(Project project, double widthMm, double heightMm, double thicknessMm, double clearanceMm) {
        Board board = project.board();
        return project.withBoard(board.resized(widthMm, heightMm, thicknessMm, clearanceMm));
    }

    /** Places every physical component that is not yet on the board on free grid positions. */
    public Project syncFromSchematic(Project project) {
        Board board = project.board();
        List<Placement> placements = new ArrayList<>(board.placements());
        List<Rect> occupied = new ArrayList<>();
        for (Placement p : placements) {
            occupied.add(PcbGeometry.courtyard(project.component(p.componentId()), p));
        }
        for (Component c : project.components()) {
            if (!c.definition().footprint().physical()
                    || placements.stream().anyMatch(p -> p.componentId().equals(c.id()))) {
                continue;
            }
            Placement placement = freeSpot(c, board, occupied);
            occupied.add(PcbGeometry.courtyard(c, placement));
            placements.add(placement);
        }
        return project.withBoard(board.with(placements, board.traces()));
    }

    public Project place(Project project, UUID componentId, double xMm, double yMm, int rotation) {
        Component component = project.component(componentId);
        Design.require(component.definition().footprint().physical(),
                component.reference() + " is schematic-only and has no footprint");
        List<Placement> placements = new ArrayList<>(project.board().placements().stream()
                .filter(p -> !p.componentId().equals(componentId)).toList());
        placements.add(new Placement(componentId, xMm, yMm, rotation));
        return project.withBoard(project.board().with(placements, project.board().traces()));
    }

    public Project unplace(Project project, UUID componentId) {
        var placements = project.board().placements().stream().filter(p -> !p.componentId().equals(componentId)).toList();
        if (placements.size() == project.board().placements().size()) {
            throw new DesignException("COMPONENT_NOT_PLACED", "The component is not on the board");
        }
        return project.withBoard(project.board().with(placements, project.board().traces()));
    }

    public Project addTrace(Project project, UUID traceId, UUID netId, String layer, double widthMm, List<Point> points) {
        var traces = new ArrayList<>(project.board().traces());
        traces.add(new Trace(traceId, netId, layer, widthMm, points));
        return project.withBoard(project.board().with(project.board().placements(), traces));
    }

    /** Adds an orthogonal (L-shaped) trace between two placed pads of the same net. */
    public Project route(Project project, UUID traceId, UUID fromPin, UUID toPin, String layer, double widthMm) {
        var pads = PcbGeometry.pads(project);
        PlacedPad from = pad(pads, fromPin);
        PlacedPad to = pad(pads, toPin);
        if (from.netId() == null || !from.netId().equals(to.netId())) {
            throw new DesignException("PCB_NOT_SAME_NET", "Both pads must belong to the same net");
        }
        Design.require(!from.pinId().equals(to.pinId()), "Choose two different pads");
        List<Point> points = new ArrayList<>();
        points.add(new Point(from.xMm(), from.yMm()));
        if (from.xMm() != to.xMm() && from.yMm() != to.yMm()) {
            points.add(new Point(to.xMm(), from.yMm()));
        }
        points.add(new Point(to.xMm(), to.yMm()));
        return addTrace(project, traceId, from.netId(), layer, widthMm, points);
    }

    public Project deleteTrace(Project project, UUID traceId) {
        var traces = project.board().traces().stream().filter(t -> !t.id().equals(traceId)).toList();
        if (traces.size() == project.board().traces().size()) {
            throw new DesignException("TRACE_NOT_FOUND", "Trace not found: " + traceId);
        }
        return project.withBoard(project.board().with(project.board().placements(), traces));
    }

    public Project addVia(Project project, Via via) {
        Board board = project.board();
        var vias = new ArrayList<>(board.vias());
        vias.add(via);
        return project.withBoard(board.withCopper(board.traces(), vias, board.zones()));
    }

    public Project deleteVia(Project project, UUID viaId) {
        Board board = project.board();
        var vias = board.vias().stream().filter(v -> !v.id().equals(viaId)).toList();
        if (vias.size() == board.vias().size()) {
            throw new DesignException("VIA_NOT_FOUND", "Via not found: " + viaId);
        }
        return project.withBoard(board.withCopper(board.traces(), vias, board.zones()));
    }

    public Project addZone(Project project, Zone zone) {
        Board board = project.board();
        var zones = new ArrayList<>(board.zones());
        zones.add(zone);
        return project.withBoard(board.withCopper(board.traces(), board.vias(), zones));
    }

    public Project deleteZone(Project project, UUID zoneId) {
        Board board = project.board();
        var zones = board.zones().stream().filter(z -> !z.id().equals(zoneId)).toList();
        if (zones.size() == board.zones().size()) {
            throw new DesignException("ZONE_NOT_FOUND", "Zone not found: " + zoneId);
        }
        return project.withBoard(board.withCopper(board.traces(), board.vias(), zones));
    }

    /** Routes the unrouted connections and adds the resulting traces and vias in one edit. */
    public Routed autoroute(Project project, AutoRouter.Options options) {
        var result = new AutoRouter().route(project, options);
        Board board = project.board();
        var traces = new ArrayList<>(board.traces());
        traces.addAll(result.traces());
        var vias = new ArrayList<>(board.vias());
        vias.addAll(result.vias());
        return new Routed(project.withBoard(board.withCopper(traces, vias, board.zones())), result);
    }

    public record Routed(Project project, AutoRouter.Result result) {
    }

    private static PlacedPad pad(List<PlacedPad> pads, UUID pinId) {
        return pads.stream().filter(p -> p.pinId().equals(pinId)).findFirst()
                .orElseThrow(() -> new DesignException("PAD_NOT_PLACED", "The pin's component is not on the board"));
    }

    private static Placement freeSpot(Component component, Board board, List<Rect> occupied) {
        var footprint = component.definition().footprint();
        double width = footprint.courtyardWidthMm();
        double height = footprint.courtyardHeightMm();
        for (double y = snap(height / 2 + 1); y + height / 2 <= board.heightMm(); y += GRID) {
            for (double x = snap(width / 2 + 1); x + width / 2 <= board.widthMm(); x += GRID) {
                Rect candidate = new Rect(x, y, width + 1, height + 1);
                if (occupied.stream().noneMatch(candidate::overlaps)) {
                    return new Placement(component.id(), x, y, 0);
                }
            }
        }
        double x = board.widthMm() + width / 2 + 5;
        double y = height / 2 + occupied.stream().filter(r -> r.centerX() > board.widthMm())
                .mapToDouble(r -> r.centerY() + r.height() / 2 + 1).max().orElse(0);
        return new Placement(component.id(), snap(x), snap(y), 0);
    }

    private static double snap(double value) {
        return Math.ceil(value / GRID) * GRID;
    }
}
