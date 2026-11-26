package net.talaatharb.expanse.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.dto.ApiModels.NetView;
import net.talaatharb.expanse.dto.ApiModels.PointView;
import net.talaatharb.expanse.dto.ApiModels.WireView;

/** Pure schematic wire geometry in sheet millimetres: paths, orthogonal elbows, junctions and hit testing. */
final class WireGeometry {
    static final double EPSILON = 1e-6;

    private WireGeometry() {
    }

    /** Start, bends and end of a wire; attached ends follow their pins. Null when an attached pin is unknown. */
    static List<double[]> path(WireView wire, Map<UUID, double[]> pins) {
        var points = new ArrayList<double[]>();
        if (wire.fromPinId() != null) {
            double[] at = pins.get(wire.fromPinId());
            if (at == null) {
                return null;
            }
            points.add(at);
        }
        for (PointView p : wire.points()) {
            points.add(new double[] {p.xMm(), p.yMm()});
        }
        if (wire.toPinId() != null) {
            double[] at = pins.get(wire.toPinId());
            if (at == null) {
                return null;
            }
            points.add(at);
        }
        return points.size() < 2 ? null : orthogonal(points);
    }

    /** Inserts a horizontal-then-vertical elbow wherever two consecutive points are not axis-aligned. */
    static List<double[]> orthogonal(List<double[]> points) {
        var result = new ArrayList<double[]>();
        for (double[] p : points) {
            if (!result.isEmpty()) {
                double[] last = result.getLast();
                if (same(last, p)) {
                    continue;
                }
                if (Math.abs(last[0] - p[0]) > EPSILON && Math.abs(last[1] - p[1]) > EPSILON) {
                    result.add(new double[] {p[0], last[1]});
                }
            }
            result.add(p);
        }
        return result;
    }

    static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < EPSILON && Math.abs(a[1] - b[1]) < EPSILON;
    }

    /** Closest point of the path to (x, y). */
    static double[] closest(List<double[]> path, double x, double y) {
        double[] best = path.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (int i = 1; i < path.size(); i++) {
            double[] candidate = project(path.get(i - 1), path.get(i), x, y);
            double distance = Math.hypot(candidate[0] - x, candidate[1] - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    static double distance(List<double[]> path, double x, double y) {
        double[] at = closest(path, x, y);
        return Math.hypot(at[0] - x, at[1] - y);
    }

    private static double[] project(double[] a, double[] b, double x, double y) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / length));
        return new double[] {a[0] + t * dx, a[1] + t * dy};
    }

    /** Points where three or more wire branches meet, including a wire end touching another wire mid-segment. */
    static List<double[]> junctions(List<List<double[]>> paths) {
        var candidates = new ArrayList<double[]>();
        for (var path : paths) {
            candidates.add(path.getFirst());
            candidates.add(path.getLast());
        }
        var result = new ArrayList<double[]>();
        for (double[] point : candidates) {
            if (result.stream().anyMatch(r -> same(r, point))) {
                continue;
            }
            int degree = 0;
            for (var path : paths) {
                for (int i = 1; i < path.size(); i++) {
                    double[] a = path.get(i - 1);
                    double[] b = path.get(i);
                    if (same(a, point) || same(b, point)) {
                        degree++;
                    } else if (Math.hypot(project(a, b, point[0], point[1])[0] - point[0],
                            project(a, b, point[0], point[1])[1] - point[1]) < EPSILON) {
                        degree += 2;
                    }
                }
            }
            if (degree >= 3) {
                result.add(point);
            }
        }
        return result;
    }

    /**
     * Groups the net's pins by drawn wire connectivity. Wires join through their attached pins, through free ends
     * that touch another wire or a pin, so each group is one visually connected cluster. Pins without a known
     * position are skipped.
     */
    static List<List<UUID>> groups(NetView net, Map<UUID, double[]> pins, Map<UUID, List<double[]>> paths) {
        var parent = new HashMap<Object, Object>();
        for (UUID pin : net.pinIds()) {
            if (pins.containsKey(pin)) {
                parent.put(pin, pin);
            }
        }
        for (WireView wire : net.wires()) {
            var path = paths.get(wire.id());
            if (path == null) {
                continue;
            }
            parent.put(wire.id(), wire.id());
            for (UUID end : new UUID[] {wire.fromPinId(), wire.toPinId()}) {
                if (end != null && parent.containsKey(end)) {
                    union(parent, wire.id(), end);
                }
            }
        }
        for (WireView wire : net.wires()) {
            var path = paths.get(wire.id());
            if (path == null) {
                continue;
            }
            for (double[] end : List.of(path.getFirst(), path.getLast())) {
                for (WireView other : net.wires()) {
                    var otherPath = paths.get(other.id());
                    if (other != wire && otherPath != null && distance(otherPath, end[0], end[1]) < EPSILON) {
                        union(parent, wire.id(), other.id());
                    }
                }
                for (UUID pin : net.pinIds()) {
                    if (pins.containsKey(pin) && same(pins.get(pin), end)) {
                        union(parent, wire.id(), pin);
                    }
                }
            }
        }
        var clusters = new LinkedHashMap<Object, List<UUID>>();
        for (UUID pin : net.pinIds()) {
            if (pins.containsKey(pin)) {
                clusters.computeIfAbsent(find(parent, pin), k -> new ArrayList<>()).add(pin);
            }
        }
        return List.copyOf(clusters.values());
    }

    private static Object find(Map<Object, Object> parent, Object node) {
        Object root = node;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }

    private static void union(Map<Object, Object> parent, Object a, Object b) {
        parent.put(find(parent, a), find(parent, b));
    }
}
