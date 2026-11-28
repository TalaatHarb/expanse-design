package net.talaatharb.expanse.pcb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Placement;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Trace;
import net.talaatharb.expanse.domain.Design.Via;
import net.talaatharb.expanse.pcb.PcbGeometry.PlacedPad;
import net.talaatharb.expanse.pcb.PcbGeometry.Rect;
import net.talaatharb.expanse.pcb.ZoneFiller.FillRect;
import net.talaatharb.expanse.pcb.ZoneFiller.Island;
import net.talaatharb.expanse.pcb.ZoneFiller.ZoneFill;

/** Read-only ratsnest and design-rule analysis of the board. */
public final class BoardAnalyzer {
    public record Airwire(UUID netId, String netName, PlacedPad from, PlacedPad to) {
    }

    public record Finding(String code, String severity, String message, List<UUID> affectedIds) {
    }

    /** {@code fills} are the derived copper-zone fills, in zone order. */
    public record Report(List<PlacedPad> pads, List<Airwire> airwires, List<Finding> findings, List<ZoneFill> fills) {
    }

    /** A zone island with its layer, as a ratsnest element. */
    private record Piece(String layer, Island island) {
    }

    public Report analyze(Project project) {
        var board = project.board();
        List<PlacedPad> pads = PcbGeometry.pads(project);
        List<ZoneFill> fills = new ZoneFiller().fill(project);
        List<Finding> findings = new ArrayList<>();
        List<Airwire> airwires = new ArrayList<>();
        for (Net net : project.nets()) {
            List<PlacedPad> netPads = pads.stream().filter(p -> net.id().equals(p.netId())).toList();
            List<Trace> netTraces = board.traces().stream().filter(t -> t.netId().equals(net.id())).toList();
            List<Via> netVias = board.vias().stream().filter(v -> v.netId().equals(net.id())).toList();
            List<Piece> pieces = fills.stream().filter(f -> f.netId().equals(net.id()))
                    .flatMap(f -> f.islands().stream().map(i -> new Piece(f.layer(), i))).toList();
            var wires = ratsnest(net, netPads, netTraces, netVias, pieces);
            airwires.addAll(wires);
            if (!wires.isEmpty()) {
                findings.add(new Finding("PCB_UNROUTED", "WARNING", "Net " + net.name() + " has "
                        + wires.size() + " unrouted connection(s).", List.of(net.id())));
            }
        }
        List<Placement> placements = board.placements();
        for (int i = 0; i < placements.size(); i++) {
            Component a = project.component(placements.get(i).componentId());
            Rect ra = PcbGeometry.courtyard(a, placements.get(i));
            if (!ra.within(board.widthMm(), board.heightMm())) {
                findings.add(new Finding("PCB_OUTSIDE_BOARD", "ERROR", a.reference()
                        + " extends beyond the board outline.", List.of(a.id())));
            }
            for (int j = i + 1; j < placements.size(); j++) {
                Component b = project.component(placements.get(j).componentId());
                if (ra.overlaps(PcbGeometry.courtyard(b, placements.get(j)))) {
                    findings.add(new Finding("PCB_COURTYARD_OVERLAP", "WARNING", a.reference() + " and "
                            + b.reference() + " courtyards overlap.", List.of(a.id(), b.id())));
                }
            }
        }
        for (Trace trace : board.traces()) {
            if (trace.points().stream().anyMatch(p -> p.xMm() < 0 || p.yMm() < 0
                    || p.xMm() > board.widthMm() || p.yMm() > board.heightMm())) {
                findings.add(new Finding("PCB_TRACE_OUTSIDE_BOARD", "ERROR", "A trace leaves the board outline.",
                        List.of(trace.id())));
            }
        }
        for (Via via : board.vias()) {
            double r = via.diameterMm() / 2;
            if (via.xMm() - r < 0 || via.yMm() - r < 0 || via.xMm() + r > board.widthMm()
                    || via.yMm() + r > board.heightMm()) {
                findings.add(new Finding("PCB_VIA_OUTSIDE_BOARD", "ERROR", "A via leaves the board outline.",
                        List.of(via.id())));
            }
        }
        for (ZoneFill fill : fills) {
            if (fill.islands().isEmpty()) {
                String name = project.nets().stream().filter(n -> n.id().equals(fill.netId())).findFirst()
                        .map(Net::name).orElse("?");
                findings.add(new Finding("PCB_ZONE_EMPTY", "WARNING", "The " + fill.layer() + " zone on net " + name
                        + " has no copper connected to its net.", List.of(fill.zoneId())));
            }
        }
        clearance(project, pads, findings);
        return new Report(pads, airwires, findings, fills);
    }

    private void clearance(Project project, List<PlacedPad> pads, List<Finding> findings) {
        double clearance = project.board().clearanceMm();
        for (int i = 0; i < pads.size(); i++) {
            PlacedPad a = pads.get(i);
            for (int j = i + 1; j < pads.size(); j++) {
                PlacedPad b = pads.get(j);
                if (!sameNet(a.netId(), b.netId())) {
                    check(findings, PcbGeometry.rectToRect(rect(a), rect(b)), clearance,
                            label(a) + " and " + label(b), a.pinId(), b.pinId());
                }
            }
        }
        List<Trace> traces = project.board().traces();
        for (int i = 0; i < traces.size(); i++) {
            Trace t = traces.get(i);
            for (PlacedPad pad : pads) {
                if (!sameNet(t.netId(), pad.netId()) && pad.onLayer(t.layer())) {
                    double distance = Double.MAX_VALUE;
                    for (int s = 0; s + 1 < t.points().size(); s++) {
                        distance = Math.min(distance, PcbGeometry.segmentToRect(t.points().get(s),
                                t.points().get(s + 1), rect(pad)));
                    }
                    check(findings, distance - t.widthMm() / 2, clearance, "A trace and " + label(pad),
                            t.id(), pad.pinId());
                }
            }
            for (int j = i + 1; j < traces.size(); j++) {
                Trace u = traces.get(j);
                if (!t.netId().equals(u.netId()) && t.layer().equals(u.layer())) {
                    check(findings, traceDistance(t, u) - (t.widthMm() + u.widthMm()) / 2, clearance,
                            "Two traces", t.id(), u.id());
                }
            }
        }
        List<Via> vias = project.board().vias();
        for (int i = 0; i < vias.size(); i++) {
            Via v = vias.get(i);
            double r = v.diameterMm() / 2;
            for (PlacedPad pad : pads) {
                if (!sameNet(v.netId(), pad.netId())) {
                    check(findings, PcbGeometry.pointToRect(v.xMm(), v.yMm(), rect(pad)) - r, clearance,
                            "A via and " + label(pad), v.id(), pad.pinId());
                }
            }
            for (Trace t : traces) {
                if (!t.netId().equals(v.netId())) {
                    check(findings, viaToTrace(v, t) - r - t.widthMm() / 2, clearance, "A via and a trace", v.id(),
                            t.id());
                }
            }
            for (int j = i + 1; j < vias.size(); j++) {
                Via w = vias.get(j);
                if (!w.netId().equals(v.netId())) {
                    check(findings, Math.hypot(v.xMm() - w.xMm(), v.yMm() - w.yMm()) - r - w.diameterMm() / 2,
                            clearance, "Two vias", v.id(), w.id());
                }
            }
        }
    }

    private static double viaToTrace(Via via, Trace trace) {
        double best = Double.MAX_VALUE;
        for (int s = 0; s + 1 < trace.points().size(); s++) {
            best = Math.min(best, PcbGeometry.pointToSegment(via.xMm(), via.yMm(), trace.points().get(s),
                    trace.points().get(s + 1)));
        }
        return best;
    }

    private static void check(List<Finding> findings, double distance, double clearance, String what,
                              UUID first, UUID second) {
        if (distance <= 1e-9) {
            findings.add(new Finding("PCB_SHORT", "ERROR", what + " of different nets touch.", List.of(first, second)));
        } else if (distance < clearance - 1e-9) {
            findings.add(new Finding("PCB_CLEARANCE", "ERROR", String.format(java.util.Locale.ROOT,
                    "%s are %.3f mm apart; the minimum is %.3f mm.", what, distance, clearance), List.of(first, second)));
        }
    }

    private static List<Airwire> ratsnest(Net net, List<PlacedPad> pads, List<Trace> traces, List<Via> vias,
                                          List<Piece> pieces) {
        int n = pads.size() + traces.size() + vias.size() + pieces.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        int viaBase = pads.size() + traces.size();
        int pieceBase = viaBase + vias.size();
        for (int v = 0; v < vias.size(); v++) {
            Via via = vias.get(v);
            for (int p = 0; p < pads.size(); p++) {
                if (PcbGeometry.pointToRect(via.xMm(), via.yMm(), rect(pads.get(p))) <= via.diameterMm() / 2) {
                    union(parent, p, viaBase + v);
                }
            }
            for (int t = 0; t < traces.size(); t++) {
                if (viaToTrace(via, traces.get(t)) <= (via.diameterMm() + traces.get(t).widthMm()) / 2) {
                    union(parent, pads.size() + t, viaBase + v);
                }
            }
            for (int w = v + 1; w < vias.size(); w++) {
                Via other = vias.get(w);
                if (Math.hypot(via.xMm() - other.xMm(), via.yMm() - other.yMm())
                        <= (via.diameterMm() + other.diameterMm()) / 2) {
                    union(parent, viaBase + v, viaBase + w);
                }
            }
        }
        for (int z = 0; z < pieces.size(); z++) {
            Piece piece = pieces.get(z);
            for (int p = 0; p < pads.size(); p++) {
                PlacedPad pad = pads.get(p);
                if (pad.onLayer(piece.layer()) && touches(piece, r -> PcbGeometry.rectToRect(r, rect(pad)) <= 1e-9)) {
                    union(parent, p, pieceBase + z);
                }
            }
            for (int t = 0; t < traces.size(); t++) {
                Trace trace = traces.get(t);
                if (trace.layer().equals(piece.layer()) && touches(piece, r -> segmentsToRect(trace, r)
                        <= trace.widthMm() / 2)) {
                    union(parent, pads.size() + t, pieceBase + z);
                }
            }
            for (int v = 0; v < vias.size(); v++) {
                Via via = vias.get(v);
                if (touches(piece, r -> PcbGeometry.pointToRect(via.xMm(), via.yMm(), r) <= via.diameterMm() / 2)) {
                    union(parent, viaBase + v, pieceBase + z);
                }
            }
            for (int w = z + 1; w < pieces.size(); w++) {
                Piece other = pieces.get(w);
                if (other.layer().equals(piece.layer()) && touches(piece, r -> other.island().rects().stream()
                        .anyMatch(o -> PcbGeometry.rectToRect(r, o.rect()) <= 1e-9))) {
                    union(parent, pieceBase + z, pieceBase + w);
                }
            }
        }
        for (int t = 0; t < traces.size(); t++) {
            Trace trace = traces.get(t);
            for (int p = 0; p < pads.size(); p++) {
                PlacedPad pad = pads.get(p);
                if (pad.onLayer(trace.layer()) && touches(trace, pad)) {
                    union(parent, p, pads.size() + t);
                }
            }
            for (int u = t + 1; u < traces.size(); u++) {
                Trace other = traces.get(u);
                if (trace.layer().equals(other.layer())
                        && traceDistance(trace, other) <= (trace.widthMm() + other.widthMm()) / 2) {
                    union(parent, pads.size() + t, pads.size() + u);
                }
            }
        }
        Map<Integer, List<PlacedPad>> clusters = new HashMap<>();
        for (int p = 0; p < pads.size(); p++) {
            clusters.computeIfAbsent(find(parent, p), k -> new ArrayList<>()).add(pads.get(p));
        }
        List<List<PlacedPad>> groups = new ArrayList<>(clusters.values());
        List<Airwire> wires = new ArrayList<>();
        if (groups.size() < 2) {
            return wires;
        }
        List<List<PlacedPad>> connected = new ArrayList<>();
        connected.add(groups.removeFirst());
        while (!groups.isEmpty()) {
            double best = Double.MAX_VALUE;
            PlacedPad bestFrom = null;
            PlacedPad bestTo = null;
            int bestGroup = -1;
            for (List<PlacedPad> tree : connected) {
                for (int g = 0; g < groups.size(); g++) {
                    for (PlacedPad a : tree) {
                        for (PlacedPad b : groups.get(g)) {
                            double d = Math.hypot(a.xMm() - b.xMm(), a.yMm() - b.yMm());
                            if (d < best) {
                                best = d;
                                bestFrom = a;
                                bestTo = b;
                                bestGroup = g;
                            }
                        }
                    }
                }
            }
            wires.add(new Airwire(net.id(), net.name(), bestFrom, bestTo));
            connected.add(groups.remove(bestGroup));
        }
        return wires;
    }

    private static boolean touches(Piece piece, java.util.function.Predicate<Rect> hit) {
        for (FillRect r : piece.island().rects()) {
            if (hit.test(r.rect())) {
                return true;
            }
        }
        return false;
    }

    private static double segmentsToRect(Trace trace, Rect rect) {
        double best = Double.MAX_VALUE;
        for (int s = 0; s + 1 < trace.points().size(); s++) {
            best = Math.min(best, PcbGeometry.segmentToRect(trace.points().get(s), trace.points().get(s + 1), rect));
        }
        return best;
    }

    private static boolean touches(Trace trace, PlacedPad pad) {
        for (int s = 0; s + 1 < trace.points().size(); s++) {
            if (PcbGeometry.segmentToRect(trace.points().get(s), trace.points().get(s + 1), rect(pad))
                    <= trace.widthMm() / 2) {
                return true;
            }
        }
        return false;
    }

    private static double traceDistance(Trace a, Trace b) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < a.points().size(); i++) {
            for (int j = 0; j + 1 < b.points().size(); j++) {
                Point p = a.points().get(i);
                Point q = a.points().get(i + 1);
                best = Math.min(best, PcbGeometry.segmentToSegment(p, q, b.points().get(j), b.points().get(j + 1)));
            }
        }
        return best;
    }

    private static Rect rect(PlacedPad pad) {
        return new Rect(pad.xMm(), pad.yMm(), pad.widthMm(), pad.heightMm());
    }

    private static boolean sameNet(UUID a, UUID b) {
        return a != null && Objects.equals(a, b);
    }

    private static String label(PlacedPad pad) {
        return pad.reference() + "." + pad.pinNumber();
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
