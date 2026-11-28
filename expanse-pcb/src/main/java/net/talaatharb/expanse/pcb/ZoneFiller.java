package net.talaatharb.expanse.pcb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Board;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Trace;
import net.talaatharb.expanse.domain.Design.Via;
import net.talaatharb.expanse.domain.Design.Zone;
import net.talaatharb.expanse.pcb.PcbGeometry.PlacedPad;
import net.talaatharb.expanse.pcb.PcbGeometry.Rect;

/**
 * Derives copper-pour fills from zone outlines. Each layer is rasterised on a square grid; a cell is copper when its
 * centre lies inside the zone outline and the whole cell keeps the zone clearance from the board edge and from
 * copper of other nets (pads, traces, vias and the fills of earlier zones). Islands that touch no copper of the zone's
 * net are removed, and the remaining cells are merged into rectangles. Zones are filled in list order, so earlier
 * zones take priority where zones of different nets overlap.
 */
public final class ZoneFiller {
    /** Preferred raster pitch; it grows on very large boards to bound the cell count. */
    public static final double CELL_MM = 0.1;
    private static final long MAX_CELLS = 2_000_000;

    /** An axis-aligned copper rectangle; {@code xMm}/{@code yMm} is the minimum corner. */
    public record FillRect(double xMm, double yMm, double widthMm, double heightMm) {
        public Rect rect() {
            return new Rect(xMm + widthMm / 2, yMm + heightMm / 2, widthMm, heightMm);
        }
    }

    /** One connected piece of a zone fill. */
    public record Island(List<FillRect> rects) {
        public Island {
            rects = List.copyOf(rects);
        }
    }

    public record ZoneFill(UUID zoneId, UUID netId, String layer, List<Island> islands) {
        public ZoneFill {
            islands = List.copyOf(islands);
        }

        public List<FillRect> rects() {
            return islands.stream().flatMap(i -> i.rects().stream()).toList();
        }
    }

    public List<ZoneFill> fill(Project project) {
        Board board = project.board();
        if (board.zones().isEmpty()) {
            return List.of();
        }
        List<PlacedPad> pads = PcbGeometry.pads(project);
        double cell = Math.max(CELL_MM, Math.sqrt(board.widthMm() * board.heightMm() / MAX_CELLS));
        int cols = (int) Math.ceil(board.widthMm() / cell);
        int rows = (int) Math.ceil(board.heightMm() / cell);
        List<ZoneFill> fills = new ArrayList<>();
        for (Zone zone : board.zones()) {
            List<ZoneFill> earlier = fills.stream().filter(f -> f.layer().equals(zone.layer())
                    && !f.netId().equals(zone.netId())).toList();
            fills.add(fill(board, pads, zone, earlier, cell, cols, rows));
        }
        return fills;
    }

    private static ZoneFill fill(Board board, List<PlacedPad> pads, Zone zone, List<ZoneFill> earlier, double cell,
                                 int cols, int rows) {
        double half = cell * Math.sqrt(0.5);
        double clearance = Math.max(zone.clearanceMm(), board.clearanceMm());
        boolean[] copper = new boolean[cols * rows];
        double minX = zone.outline().stream().mapToDouble(Point::xMm).min().orElse(0);
        double maxX = zone.outline().stream().mapToDouble(Point::xMm).max().orElse(0);
        double minY = zone.outline().stream().mapToDouble(Point::yMm).min().orElse(0);
        double maxY = zone.outline().stream().mapToDouble(Point::yMm).max().orElse(0);
        int c0 = clamp((int) Math.floor(minX / cell), cols);
        int c1 = clamp((int) Math.ceil(maxX / cell), cols);
        int r0 = clamp((int) Math.floor(minY / cell), rows);
        int r1 = clamp((int) Math.ceil(maxY / cell), rows);
        for (int r = r0; r < r1; r++) {
            double y = (r + 0.5) * cell;
            for (int c = c0; c < c1; c++) {
                double x = (c + 0.5) * cell;
                boolean insideBoard = x - cell / 2 >= clearance && y - cell / 2 >= clearance
                        && x + cell / 2 <= board.widthMm() - clearance && y + cell / 2 <= board.heightMm() - clearance;
                copper[r * cols + c] = insideBoard && inside(zone.outline(), x, y);
            }
        }
        Grid grid = new Grid(copper, cols, rows, cell);
        double reach = clearance + half;
        for (PlacedPad pad : pads) {
            if (pad.onLayer(zone.layer()) && !zone.netId().equals(pad.netId())) {
                Rect rect = new Rect(pad.xMm(), pad.yMm(), pad.widthMm(), pad.heightMm());
                grid.clear(rect.centerX() - rect.width() / 2 - reach, rect.centerY() - rect.height() / 2 - reach,
                        rect.centerX() + rect.width() / 2 + reach, rect.centerY() + rect.height() / 2 + reach,
                        (x, y) -> PcbGeometry.pointToRect(x, y, rect) < reach);
            }
        }
        for (Trace trace : board.traces()) {
            if (trace.layer().equals(zone.layer()) && !trace.netId().equals(zone.netId())) {
                double r = reach + trace.widthMm() / 2;
                for (int s = 0; s + 1 < trace.points().size(); s++) {
                    Point a = trace.points().get(s);
                    Point b = trace.points().get(s + 1);
                    grid.clear(Math.min(a.xMm(), b.xMm()) - r, Math.min(a.yMm(), b.yMm()) - r,
                            Math.max(a.xMm(), b.xMm()) + r, Math.max(a.yMm(), b.yMm()) + r,
                            (x, y) -> PcbGeometry.pointToSegment(x, y, a, b) < r);
                }
            }
        }
        for (Via via : board.vias()) {
            if (!via.netId().equals(zone.netId())) {
                double r = reach + via.diameterMm() / 2;
                grid.clear(via.xMm() - r, via.yMm() - r, via.xMm() + r, via.yMm() + r,
                        (x, y) -> Math.hypot(x - via.xMm(), y - via.yMm()) < r);
            }
        }
        for (ZoneFill other : earlier) {
            for (FillRect f : other.rects()) {
                Rect rect = f.rect();
                grid.clear(f.xMm() - reach, f.yMm() - reach, f.xMm() + f.widthMm() + reach,
                        f.yMm() + f.heightMm() + reach, (x, y) -> PcbGeometry.pointToRect(x, y, rect) < reach);
            }
        }
        boolean[] seed = new boolean[cols * rows];
        Grid seeds = new Grid(seed, cols, rows, cell);
        for (PlacedPad pad : pads) {
            if (pad.onLayer(zone.layer()) && zone.netId().equals(pad.netId())) {
                Rect rect = new Rect(pad.xMm(), pad.yMm(), pad.widthMm(), pad.heightMm());
                seeds.mark(rect.centerX() - rect.width() / 2, rect.centerY() - rect.height() / 2,
                        rect.centerX() + rect.width() / 2, rect.centerY() + rect.height() / 2,
                        (x, y) -> PcbGeometry.pointToRect(x, y, rect) <= half);
            }
        }
        for (Trace trace : board.traces()) {
            if (trace.layer().equals(zone.layer()) && trace.netId().equals(zone.netId())) {
                double r = trace.widthMm() / 2 + half;
                for (int s = 0; s + 1 < trace.points().size(); s++) {
                    Point a = trace.points().get(s);
                    Point b = trace.points().get(s + 1);
                    seeds.mark(Math.min(a.xMm(), b.xMm()) - r, Math.min(a.yMm(), b.yMm()) - r,
                            Math.max(a.xMm(), b.xMm()) + r, Math.max(a.yMm(), b.yMm()) + r,
                            (x, y) -> PcbGeometry.pointToSegment(x, y, a, b) <= r);
                }
            }
        }
        for (Via via : board.vias()) {
            if (via.netId().equals(zone.netId())) {
                double r = via.diameterMm() / 2 + half;
                seeds.mark(via.xMm() - r, via.yMm() - r, via.xMm() + r, via.yMm() + r,
                        (x, y) -> Math.hypot(x - via.xMm(), y - via.yMm()) <= r);
            }
        }
        return new ZoneFill(zone.id(), zone.netId(), zone.layer(), islands(copper, seed, cols, rows, cell));
    }

    /** Groups 4-connected copper cells, keeps islands containing a seed cell and merges each into rectangles. */
    private static List<Island> islands(boolean[] copper, boolean[] seed, int cols, int rows, double cell) {
        int[] label = new int[cols * rows];
        List<Island> islands = new ArrayList<>();
        int next = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        List<Integer> members = new ArrayList<>();
        for (int start = 0; start < copper.length; start++) {
            if (!copper[start] || label[start] != 0) {
                continue;
            }
            next++;
            members.clear();
            boolean seeded = false;
            label[start] = next;
            queue.add(start);
            while (!queue.isEmpty()) {
                int i = queue.poll();
                members.add(i);
                seeded |= seed[i];
                int r = i / cols;
                int c = i % cols;
                int[] neighbours = {c > 0 ? i - 1 : -1, c + 1 < cols ? i + 1 : -1, r > 0 ? i - cols : -1,
                        r + 1 < rows ? i + cols : -1};
                for (int n : neighbours) {
                    if (n >= 0 && copper[n] && label[n] == 0) {
                        label[n] = next;
                        queue.add(n);
                    }
                }
            }
            if (seeded) {
                islands.add(new Island(merge(label, next, members, cols, cell)));
            }
        }
        return islands;
    }

    /** Merges the cells labelled {@code id} into horizontal runs and stacks identical runs of adjacent rows. */
    private static List<FillRect> merge(int[] label, int id, List<Integer> members, int cols, double cell) {
        int minRow = members.stream().mapToInt(i -> i / cols).min().orElse(0);
        int maxRow = members.stream().mapToInt(i -> i / cols).max().orElse(-1);
        int minCol = members.stream().mapToInt(i -> i % cols).min().orElse(0);
        int maxCol = members.stream().mapToInt(i -> i % cols).max().orElse(-1);
        List<FillRect> rects = new ArrayList<>();
        List<int[]> open = new ArrayList<>();
        for (int r = minRow; r <= maxRow + 1; r++) {
            List<int[]> runs = new ArrayList<>();
            if (r <= maxRow) {
                int c = minCol;
                while (c <= maxCol) {
                    if (label[r * cols + c] != id) {
                        c++;
                        continue;
                    }
                    int from = c;
                    while (c <= maxCol && label[r * cols + c] == id) {
                        c++;
                    }
                    runs.add(new int[]{from, c, r});
                }
            }
            List<int[]> stillOpen = new ArrayList<>();
            for (int[] run : open) {
                int[] continued = runs.stream().filter(n -> n[0] == run[0] && n[1] == run[1]).findFirst().orElse(null);
                if (continued != null) {
                    runs.remove(continued);
                    stillOpen.add(run);
                } else {
                    rects.add(new FillRect(run[0] * cell, run[2] * cell, (run[1] - run[0]) * cell, (r - run[2]) * cell));
                }
            }
            stillOpen.addAll(runs);
            open = stillOpen;
        }
        return rects;
    }

    static boolean inside(List<Point> polygon, double x, double y) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point a = polygon.get(i);
            Point b = polygon.get(j);
            if ((a.yMm() > y) != (b.yMm() > y)
                    && x < (b.xMm() - a.xMm()) * (y - a.yMm()) / (b.yMm() - a.yMm()) + a.xMm()) {
                in = !in;
            }
        }
        return in;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size, value));
    }

    private interface CellTest {
        boolean test(double x, double y);
    }

    private record Grid(boolean[] cells, int cols, int rows, double cell) {
        void clear(double x0, double y0, double x1, double y1, CellTest hit) {
            set(x0, y0, x1, y1, hit, false);
        }

        void mark(double x0, double y0, double x1, double y1, CellTest hit) {
            set(x0, y0, x1, y1, hit, true);
        }

        private void set(double x0, double y0, double x1, double y1, CellTest hit, boolean value) {
            int c0 = clamp((int) Math.floor(x0 / cell), cols);
            int c1 = clamp((int) Math.ceil(x1 / cell), cols);
            int r0 = clamp((int) Math.floor(y0 / cell), rows);
            int r1 = clamp((int) Math.ceil(y1 / cell), rows);
            for (int r = r0; r < r1; r++) {
                for (int c = c0; c < c1; c++) {
                    if (cells[r * cols + c] != value && hit.test((c + 0.5) * cell, (r + 0.5) * cell)) {
                        cells[r * cols + c] = value;
                    }
                }
            }
        }
    }
}
