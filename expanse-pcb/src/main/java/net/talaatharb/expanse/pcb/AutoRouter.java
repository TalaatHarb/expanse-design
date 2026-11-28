package net.talaatharb.expanse.pcb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Board;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Trace;
import net.talaatharb.expanse.domain.Design.Via;
import net.talaatharb.expanse.pcb.BoardAnalyzer.Airwire;
import net.talaatharb.expanse.pcb.PcbGeometry.PlacedPad;
import net.talaatharb.expanse.pcb.PcbGeometry.Rect;

/**
 * Two-layer grid autorouter. Every unrouted connection (airwire) is routed with A* on a grid of {@link #PITCH_MM}
 * (coarser on very large boards) with 45° moves, a turn penalty and a via cost. Obstacles are the copper of other
 * nets inflated by the board clearance plus half the trace width (vias: half the via diameter); copper zones are not
 * obstacles because their fills are derived and give way to traces. Connections are routed shortest first and each
 * new route becomes an obstacle for the next. Layer changes produce vias.
 */
public final class AutoRouter {
    public static final double PITCH_MM = 0.254;
    private static final int MAX_NODES = 250_000;
    private static final int MAX_EXPANSIONS = 3_000_000;
    private static final double VIA_COST = 12;
    private static final double TURN_COST = 0.4;
    private static final int FREE = -1;
    private static final int BLOCKED = -2;
    private static final int[][] DIRECTIONS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final List<String> LAYERS = List.of("top", "bottom");

    /** {@code nets} limits routing to those nets; empty routes every net. */
    public record Options(Set<UUID> nets, double widthMm, double viaDiameterMm, double viaDrillMm) {
        public Options {
            nets = Set.copyOf(nets);
            Design.require(widthMm >= 0.05 && widthMm <= 20, "Trace width must be between 0.05 and 20 mm");
            Design.require(viaDiameterMm >= 0.2 && viaDiameterMm <= 10 && viaDrillMm >= 0.1
                    && viaDrillMm < viaDiameterMm, "Via drill must be at least 0.1 mm and fit inside the via");
        }
    }

    public record Result(List<Trace> traces, List<Via> vias, int attempted, List<String> unrouted) {
        public Result {
            traces = List.copyOf(traces);
            vias = List.copyOf(vias);
            unrouted = List.copyOf(unrouted);
        }

        public int routed() {
            return attempted - unrouted.size();
        }
    }

    public Result route(Project project, Options options) {
        Board board = project.board();
        List<Airwire> airwires = new BoardAnalyzer().analyze(project).airwires().stream()
                .filter(a -> options.nets().isEmpty() || options.nets().contains(a.netId()))
                .sorted(Comparator.comparingDouble(a -> Math.hypot(a.from().xMm() - a.to().xMm(),
                        a.from().yMm() - a.to().yMm())))
                .toList();
        if (airwires.isEmpty()) {
            return new Result(List.of(), List.of(), 0, List.of());
        }
        double pitch = PITCH_MM;
        while ((board.widthMm() / pitch + 1) * (board.heightMm() / pitch + 1) > MAX_NODES) {
            pitch *= 2;
        }
        Maps maps = new Maps(board, pitch, options);
        Map<UUID, Integer> netIndex = new HashMap<>();
        project.nets().forEach(n -> netIndex.put(n.id(), netIndex.size()));
        for (PlacedPad pad : PcbGeometry.pads(project)) {
            maps.addPad(pad, pad.netId() == null ? BLOCKED : netIndex.get(pad.netId()));
        }
        for (Trace trace : board.traces()) {
            maps.addTrace(trace, netIndex.get(trace.netId()));
        }
        for (Via via : board.vias()) {
            maps.addVia(via.xMm(), via.yMm(), via.diameterMm(), netIndex.get(via.netId()));
        }
        List<Trace> traces = new ArrayList<>();
        List<Via> vias = new ArrayList<>();
        List<String> unrouted = new ArrayList<>();
        for (Airwire airwire : airwires) {
            int net = netIndex.get(airwire.netId());
            List<int[]> path = maps.search(airwire.from(), airwire.to(), net);
            String label = airwire.netName() + " " + airwire.from().reference() + "." + airwire.from().pinNumber()
                    + "-" + airwire.to().reference() + "." + airwire.to().pinNumber();
            if (path == null) {
                unrouted.add(label);
                continue;
            }
            var routed = geometry(path, airwire, pitch, options);
            for (Trace trace : routed.traces()) {
                maps.addTrace(trace, net);
                traces.add(trace);
            }
            for (Via via : routed.vias()) {
                maps.addVia(via.xMm(), via.yMm(), via.diameterMm(), net);
                vias.add(via);
            }
        }
        return new Result(traces, vias, airwires.size(), unrouted);
    }

    private record Routed(List<Trace> traces, List<Via> vias) {
    }

    /** Turns a node path (i, j, layer) into traces split at layer changes plus vias at each change. */
    private static Routed geometry(List<int[]> path, Airwire airwire, double pitch, Options options) {
        List<Trace> traces = new ArrayList<>();
        List<Via> vias = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        addPoint(points, new Point(airwire.from().xMm(), airwire.from().yMm()));
        int layer = path.getFirst()[2];
        for (int[] node : path) {
            Point p = new Point(round(node[0] * pitch), round(node[1] * pitch));
            if (node[2] != layer) {
                if (points.size() >= 2) {
                    traces.add(new Trace(UUID.randomUUID(), airwire.netId(), LAYERS.get(layer), options.widthMm(),
                            points));
                }
                vias.add(new Via(UUID.randomUUID(), airwire.netId(), p.xMm(), p.yMm(), options.viaDiameterMm(),
                        options.viaDrillMm()));
                points = new ArrayList<>();
                layer = node[2];
            }
            addPoint(points, p);
        }
        addPoint(points, new Point(airwire.to().xMm(), airwire.to().yMm()));
        if (points.size() >= 2) {
            traces.add(new Trace(UUID.randomUUID(), airwire.netId(), LAYERS.get(layer), options.widthMm(), points));
        }
        return new Routed(traces, vias);
    }

    /** Appends a point, dropping duplicates and the middle of collinear runs. */
    private static void addPoint(List<Point> points, Point p) {
        if (!points.isEmpty() && same(points.getLast(), p)) {
            return;
        }
        if (points.size() >= 2) {
            Point a = points.get(points.size() - 2);
            Point b = points.getLast();
            double cross = (b.xMm() - a.xMm()) * (p.yMm() - b.yMm()) - (b.yMm() - a.yMm()) * (p.xMm() - b.xMm());
            double dot = (b.xMm() - a.xMm()) * (p.xMm() - b.xMm()) + (b.yMm() - a.yMm()) * (p.yMm() - b.yMm());
            if (Math.abs(cross) < 1e-9 && dot > 0) {
                points.removeLast();
            }
        }
        points.add(p);
    }

    private static boolean same(Point a, Point b) {
        return Math.abs(a.xMm() - b.xMm()) < 1e-9 && Math.abs(a.yMm() - b.yMm()) < 1e-9;
    }

    private static double round(double value) {
        return Math.round(value * 1e6) / 1e6;
    }

    /**
     * Occupancy maps. Each cell holds {@link #FREE}, the index of the only net whose copper is near it (free for that
     * net) or {@link #BLOCKED}. Trace maps use half-pitch resolution so the midpoints of moves are checked too.
     */
    private static final class Maps {
        private final double pitch;
        private final double fine;
        private final int cols;
        private final int rows;
        private final int fineCols;
        private final int fineRows;
        private final int[][] traceMaps = new int[2][];
        private final int[] viaMap;
        private final double traceReach;
        private final double viaReach;
        private final double clearance;
        private float[] cost;
        private int[] parent;
        private boolean[] goal;
        private double slack;

        Maps(Board board, double pitch, Options options) {
            this.pitch = pitch;
            this.fine = pitch / 2;
            cols = (int) Math.floor(board.widthMm() / pitch) + 1;
            rows = (int) Math.floor(board.heightMm() / pitch) + 1;
            fineCols = 2 * cols - 1;
            fineRows = 2 * rows - 1;
            clearance = board.clearanceMm();
            double margin = fine / 2;
            traceReach = clearance + options.widthMm() / 2 + margin;
            viaReach = clearance + options.viaDiameterMm() / 2 + margin;
            for (int l = 0; l < 2; l++) {
                traceMaps[l] = new int[fineCols * fineRows];
                Arrays.fill(traceMaps[l], FREE);
            }
            viaMap = new int[cols * rows];
            Arrays.fill(viaMap, FREE);
            double traceEdge = clearance + options.widthMm() / 2;
            double viaEdge = clearance + options.viaDiameterMm() / 2;
            for (int r = 0; r < fineRows; r++) {
                for (int c = 0; c < fineCols; c++) {
                    double x = c * fine;
                    double y = r * fine;
                    if (x < traceEdge || y < traceEdge || x > board.widthMm() - traceEdge
                            || y > board.heightMm() - traceEdge) {
                        traceMaps[0][r * fineCols + c] = BLOCKED;
                        traceMaps[1][r * fineCols + c] = BLOCKED;
                    }
                }
            }
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double x = c * pitch;
                    double y = r * pitch;
                    if (x < viaEdge || y < viaEdge || x > board.widthMm() - viaEdge || y > board.heightMm() - viaEdge) {
                        viaMap[r * cols + c] = BLOCKED;
                    }
                }
            }
        }

        void addPad(PlacedPad pad, int net) {
            Rect rect = new Rect(pad.xMm(), pad.yMm(), pad.widthMm(), pad.heightMm());
            for (int l = 0; l < 2; l++) {
                if (pad.onLayer(LAYERS.get(l))) {
                    mark(traceMaps[l], fineCols, fineRows, fine, rect, traceReach, net);
                }
            }
            // Vias keep clearance from every pad and never land in a drilled pad of their own net.
            mark(viaMap, cols, rows, pitch, rect, viaReach, pad.drillMm() > 0 ? BLOCKED : net);
        }

        void addTrace(Trace trace, int net) {
            int l = LAYERS.indexOf(trace.layer());
            double half = trace.widthMm() / 2;
            for (int s = 0; s + 1 < trace.points().size(); s++) {
                Point a = trace.points().get(s);
                Point b = trace.points().get(s + 1);
                double r = traceReach + half;
                update(traceMaps[l], fineCols, fineRows, fine, Math.min(a.xMm(), b.xMm()) - r,
                        Math.min(a.yMm(), b.yMm()) - r, Math.max(a.xMm(), b.xMm()) + r,
                        Math.max(a.yMm(), b.yMm()) + r, (x, y) -> PcbGeometry.pointToSegment(x, y, a, b) < r, net);
                double rv = viaReach + half;
                update(viaMap, cols, rows, pitch, Math.min(a.xMm(), b.xMm()) - rv, Math.min(a.yMm(), b.yMm()) - rv,
                        Math.max(a.xMm(), b.xMm()) + rv, Math.max(a.yMm(), b.yMm()) + rv,
                        (x, y) -> PcbGeometry.pointToSegment(x, y, a, b) < rv, net);
            }
        }

        void addVia(double vx, double vy, double diameter, int net) {
            double r = traceReach + diameter / 2;
            for (int l = 0; l < 2; l++) {
                update(traceMaps[l], fineCols, fineRows, fine, vx - r, vy - r, vx + r, vy + r,
                        (x, y) -> Math.hypot(x - vx, y - vy) < r, net);
            }
            double rv = viaReach + diameter / 2;
            update(viaMap, cols, rows, pitch, vx - rv, vy - rv, vx + rv, vy + rv,
                    (x, y) -> Math.hypot(x - vx, y - vy) < rv, BLOCKED);
        }

        private static void mark(int[] map, int mapCols, int mapRows, double step, Rect rect, double reach, int net) {
            update(map, mapCols, mapRows, step, rect.centerX() - rect.width() / 2 - reach,
                    rect.centerY() - rect.height() / 2 - reach, rect.centerX() + rect.width() / 2 + reach,
                    rect.centerY() + rect.height() / 2 + reach, (x, y) -> PcbGeometry.pointToRect(x, y, rect) < reach,
                    net);
        }

        private interface Hit {
            boolean test(double x, double y);
        }

        private static void update(int[] map, int mapCols, int mapRows, double step, double x0, double y0, double x1,
                                   double y1, Hit hit, int net) {
            int c0 = Math.max(0, (int) Math.floor(x0 / step));
            int c1 = Math.min(mapCols - 1, (int) Math.ceil(x1 / step));
            int r0 = Math.max(0, (int) Math.floor(y0 / step));
            int r1 = Math.min(mapRows - 1, (int) Math.ceil(y1 / step));
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    int i = r * mapCols + c;
                    int current = map[i];
                    if (current == BLOCKED || current == net || !hit.test(c * step, r * step)) {
                        continue;
                    }
                    map[i] = current == FREE ? net : BLOCKED;
                }
            }
        }

        private static boolean free(int value, int net) {
            return value == FREE || value == net;
        }

        private boolean nodeFree(int layer, int i, int j, int net) {
            return free(traceMaps[layer][(2 * j) * fineCols + 2 * i], net);
        }

        /** Grid nodes inside the pad that are free for the net, closest to the pad centre first. */
        private List<int[]> terminals(PlacedPad pad, int net) {
            List<int[]> nodes = new ArrayList<>();
            int i0 = (int) Math.ceil((pad.xMm() - pad.widthMm() / 2) / pitch);
            int i1 = (int) Math.floor((pad.xMm() + pad.widthMm() / 2) / pitch);
            int j0 = (int) Math.ceil((pad.yMm() - pad.heightMm() / 2) / pitch);
            int j1 = (int) Math.floor((pad.yMm() + pad.heightMm() / 2) / pitch);
            for (int l = 0; l < 2; l++) {
                if (!pad.onLayer(LAYERS.get(l))) {
                    continue;
                }
                for (int j = Math.max(0, j0); j <= Math.min(rows - 1, j1); j++) {
                    for (int i = Math.max(0, i0); i <= Math.min(cols - 1, i1); i++) {
                        if (nodeFree(l, i, j, net)) {
                            nodes.add(new int[]{i, j, l});
                        }
                    }
                }
            }
            nodes.sort(Comparator.comparingDouble(n -> Math.hypot(n[0] * pitch - pad.xMm(), n[1] * pitch - pad.yMm())));
            return nodes.size() > 8 ? nodes.subList(0, 8) : nodes;
        }

        /** A* over (layer, node, arrival direction); returns the node path from {@code from} to {@code to}. */
        List<int[]> search(PlacedPad from, PlacedPad to, int net) {
            List<int[]> starts = terminals(from, net);
            List<int[]> goals = terminals(to, net);
            if (starts.isEmpty() || goals.isEmpty()) {
                return null;
            }
            int nodes = cols * rows;
            if (cost == null) {
                cost = new float[2 * nodes * 9];
                parent = new int[2 * nodes * 9];
                goal = new boolean[2 * nodes];
            }
            Arrays.fill(cost, Float.POSITIVE_INFINITY);
            Arrays.fill(goal, false);
            double gx = to.xMm() / pitch;
            double gy = to.yMm() / pitch;
            slack = 0;
            for (int[] g : goals) {
                goal[g[2] * nodes + g[1] * cols + g[0]] = true;
                slack = Math.max(slack, octile(g[0] - gx, g[1] - gy));
            }
            Heap heap = new Heap();
            for (int[] s : starts) {
                int state = ((s[2] * nodes) + s[1] * cols + s[0]) * 9 + 8;
                cost[state] = 0;
                parent[state] = -1;
                heap.push(state, heuristic(s[0], s[1], gx, gy));
            }
            int expansions = 0;
            while (!heap.isEmpty() && expansions++ < MAX_EXPANSIONS) {
                double key = heap.peekKey();
                int state = heap.pop();
                int dir = state % 9;
                int node = state / 9;
                int layer = node / nodes;
                int cell = node % nodes;
                int i = cell % cols;
                int j = cell / cols;
                double g = cost[state];
                if (key > g + heuristic(i, j, gx, gy) + 1e-6) {
                    continue;
                }
                if (goal[node]) {
                    return path(parent, state, nodes);
                }
                for (int d = 0; d < 8; d++) {
                    int ni = i + DIRECTIONS[d][0];
                    int nj = j + DIRECTIONS[d][1];
                    if (ni < 0 || nj < 0 || ni >= cols || nj >= rows || !nodeFree(layer, ni, nj, net)
                            || !free(traceMaps[layer][(2 * j + DIRECTIONS[d][1]) * fineCols + 2 * i
                            + DIRECTIONS[d][0]], net)) {
                        continue;
                    }
                    if (dir != 8 && Math.abs(d - dir) == 4) {
                        continue;
                    }
                    double step = d % 2 == 0 ? 1 : Math.sqrt(2);
                    double turn = dir == 8 || dir == d ? 0 : TURN_COST * Math.min(Math.abs(d - dir), 8 - Math.abs(d - dir));
                    relax(heap, cost, parent, state, ((layer * nodes) + nj * cols + ni) * 9 + d, g + step + turn,
                            heuristic(ni, nj, gx, gy));
                }
                int other = 1 - layer;
                if (free(viaMap[cell], net) && nodeFree(other, i, j, net)) {
                    relax(heap, cost, parent, state, ((other * nodes) + cell) * 9 + 8, g + VIA_COST,
                            heuristic(i, j, gx, gy));
                }
            }
            return null;
        }

        private static void relax(Heap heap, float[] cost, int[] parent, int from, int to, double g, double h) {
            if (g < cost[to] - 1e-6) {
                cost[to] = (float) g;
                parent[to] = from;
                heap.push(to, (float) g + h);
            }
        }

        /** Octile distance to the goal pad centre minus the farthest goal node offset, so it never overestimates. */
        private double heuristic(int i, int j, double gx, double gy) {
            return Math.max(0, octile(i - gx, j - gy) - slack);
        }

        private static double octile(double dx, double dy) {
            dx = Math.abs(dx);
            dy = Math.abs(dy);
            return Math.max(dx, dy) + (Math.sqrt(2) - 1) * Math.min(dx, dy);
        }

        private List<int[]> path(int[] parent, int state, int nodes) {
            List<int[]> path = new ArrayList<>();
            for (int s = state; s != -1; s = parent[s]) {
                int node = s / 9;
                int cell = node % nodes;
                path.addFirst(new int[]{cell % cols, cell / cols, node / nodes});
            }
            return path;
        }
    }

    /** Minimal binary min-heap of (state, key) pairs with lazy deletion by the caller. */
    private static final class Heap {
        private int[] states = new int[1024];
        private double[] keys = new double[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        double peekKey() {
            return keys[0];
        }

        void push(int state, double key) {
            if (size == states.length) {
                states = Arrays.copyOf(states, size * 2);
                keys = Arrays.copyOf(keys, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) / 2;
                if (keys[p] <= key) {
                    break;
                }
                states[i] = states[p];
                keys[i] = keys[p];
                i = p;
            }
            states[i] = state;
            keys[i] = key;
        }

        int pop() {
            int top = states[0];
            size--;
            int state = states[size];
            double key = keys[size];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= size) {
                    break;
                }
                if (c + 1 < size && keys[c + 1] < keys[c]) {
                    c++;
                }
                if (keys[c] >= key) {
                    break;
                }
                states[i] = states[c];
                keys[i] = keys[c];
                i = c;
            }
            states[i] = state;
            keys[i] = key;
            return top;
        }
    }
}
