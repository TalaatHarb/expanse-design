package net.talaatharb.expanse.pcb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Placement;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;

/** Board-space geometry derived from footprints and placements. Y grows downwards, rotation is clockwise on screen. */
public final class PcbGeometry {
    private PcbGeometry() {
    }

    public record PlacedPad(UUID componentId, String reference, UUID pinId, String pinNumber, UUID netId,
                            double xMm, double yMm, double widthMm, double heightMm, double drillMm) {
        public boolean onLayer(String layer) {
            return drillMm > 0 || "top".equals(layer);
        }
    }

    public record Rect(double centerX, double centerY, double width, double height) {
        public boolean within(double boardWidth, double boardHeight) {
            return centerX - width / 2 >= 0 && centerY - height / 2 >= 0
                    && centerX + width / 2 <= boardWidth && centerY + height / 2 <= boardHeight;
        }

        public boolean overlaps(Rect other) {
            return Math.abs(centerX - other.centerX) * 2 < width + other.width
                    && Math.abs(centerY - other.centerY) * 2 < height + other.height;
        }
    }

    public static List<PlacedPad> pads(Project project) {
        Map<UUID, UUID> netOfPin = new HashMap<>();
        for (Net net : project.nets()) {
            net.pinIds().forEach(pin -> netOfPin.put(pin, net.id()));
        }
        List<PlacedPad> pads = new ArrayList<>();
        for (Placement placement : project.board().placements()) {
            Component c = project.component(placement.componentId());
            for (var pad : c.definition().footprint().pads()) {
                var pin = c.pins().stream().filter(p -> p.number().equals(pad.number())).findFirst().orElseThrow();
                double[] offset = rotate(pad.xMm(), pad.yMm(), placement.rotation());
                boolean quarter = placement.rotation() % 180 != 0;
                pads.add(new PlacedPad(c.id(), c.reference(), pin.id(), pin.number(), netOfPin.get(pin.id()),
                        placement.xMm() + offset[0], placement.yMm() + offset[1],
                        quarter ? pad.heightMm() : pad.widthMm(), quarter ? pad.widthMm() : pad.heightMm(),
                        pad.drillMm()));
            }
        }
        return pads;
    }

    public static Rect courtyard(Component component, Placement placement) {
        var footprint = component.definition().footprint();
        boolean quarter = placement.rotation() % 180 != 0;
        return new Rect(placement.xMm(), placement.yMm(),
                quarter ? footprint.courtyardHeightMm() : footprint.courtyardWidthMm(),
                quarter ? footprint.courtyardWidthMm() : footprint.courtyardHeightMm());
    }

    public static double[] rotate(double x, double y, int rotation) {
        return switch (rotation) {
            case 90 -> new double[]{0.0 - y, x};
            case 180 -> new double[]{0.0 - x, 0.0 - y};
            case 270 -> new double[]{y, 0.0 - x};
            default -> new double[]{x, y};
        };
    }

    public static double pointToRect(double px, double py, Rect r) {
        double dx = Math.max(0, Math.abs(px - r.centerX()) - r.width() / 2);
        double dy = Math.max(0, Math.abs(py - r.centerY()) - r.height() / 2);
        return Math.hypot(dx, dy);
    }

    public static double rectToRect(Rect a, Rect b) {
        double dx = Math.max(0, Math.abs(a.centerX() - b.centerX()) - (a.width() + b.width()) / 2);
        double dy = Math.max(0, Math.abs(a.centerY() - b.centerY()) - (a.height() + b.height()) / 2);
        return Math.hypot(dx, dy);
    }

    public static double pointToSegment(double px, double py, Point a, Point b) {
        double dx = b.xMm() - a.xMm();
        double dy = b.yMm() - a.yMm();
        double length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - a.xMm()) * dx + (py - a.yMm()) * dy) / length));
        return Math.hypot(px - (a.xMm() + t * dx), py - (a.yMm() + t * dy));
    }

    public static double segmentToSegment(Point a, Point b, Point c, Point d) {
        if (intersects(a, b, c, d)) {
            return 0;
        }
        return Math.min(Math.min(pointToSegment(a.xMm(), a.yMm(), c, d), pointToSegment(b.xMm(), b.yMm(), c, d)),
                Math.min(pointToSegment(c.xMm(), c.yMm(), a, b), pointToSegment(d.xMm(), d.yMm(), a, b)));
    }

    public static double segmentToRect(Point a, Point b, Rect r) {
        double left = r.centerX() - r.width() / 2;
        double right = r.centerX() + r.width() / 2;
        double top = r.centerY() - r.height() / 2;
        double bottom = r.centerY() + r.height() / 2;
        if (pointToRect(a.xMm(), a.yMm(), r) == 0 || pointToRect(b.xMm(), b.yMm(), r) == 0) {
            return 0;
        }
        Point[] corners = {new Point(left, top), new Point(right, top), new Point(right, bottom), new Point(left, bottom)};
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            best = Math.min(best, segmentToSegment(a, b, corners[i], corners[(i + 1) % 4]));
        }
        return best;
    }

    private static boolean intersects(Point a, Point b, Point c, Point d) {
        double d1 = cross(c, d, a);
        double d2 = cross(c, d, b);
        double d3 = cross(a, b, c);
        double d4 = cross(a, b, d);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double cross(Point o, Point p, Point q) {
        return (p.xMm() - o.xMm()) * (q.yMm() - o.yMm()) - (p.yMm() - o.yMm()) * (q.xMm() - o.xMm());
    }
}
