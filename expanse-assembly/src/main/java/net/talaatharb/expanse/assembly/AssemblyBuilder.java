package net.talaatharb.expanse.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Placement;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Trace;
import net.talaatharb.expanse.pcb.PcbGeometry;

/**
 * Derives a renderer-neutral 3D assembly from the board: Z points up from the top copper surface (z = 0),
 * the board occupies {@code -thickness..0}. Rotation is about Z in degrees, matching board placement rotation.
 */
public final class AssemblyBuilder {
    public static final double COPPER_MM = 0.035;

    public record Solid(String id, UUID ownerId, String label, String role, String shape, double xMm, double yMm,
                        double zMm, double sizeXMm, double sizeYMm, double sizeZMm, double rotationDeg, String color) {
    }

    public record Finding(String code, String severity, String message, List<UUID> affectedIds) {
    }

    public record Scene(double boardWidthMm, double boardHeightMm, double boardThicknessMm, double maxHeightMm,
                        List<Solid> solids, List<Finding> findings) {
    }

    /** LEDs tint their body by the {@code color} property; other parts use the definition colour. */
    static String bodyColor(Component c) {
        if (!c.definition().kind().equals("led")) {
            return c.definition().body().color();
        }
        return switch (c.properties().getOrDefault("color", "red")) {
            case "yellow" -> "#fdd835";
            case "green" -> "#43a047";
            case "blue" -> "#1e88e5";
            case "white" -> "#f5f5f5";
            default -> c.definition().body().color();
        };
    }

    public Scene build(Project project) {
        var board = project.board();
        double t = board.thicknessMm();
        List<Solid> solids = new ArrayList<>();
        solids.add(new Solid("board", project.id(), "Board", "board", "box", board.widthMm() / 2,
                board.heightMm() / 2, -t / 2, board.widthMm(), board.heightMm(), t, 0, "#1b5e20"));
        List<Solid> bodies = new ArrayList<>();
        for (Placement placement : board.placements()) {
            Component c = project.component(placement.componentId());
            var body = c.definition().body();
            if (body.shape().equals("none")) {
                continue;
            }
            double z = body.shape().equals("axial") ? body.depthMm() / 2 + 0.5 : body.heightMm() / 2;
            Solid solid = new Solid("body:" + c.id(), c.id(), c.reference(), "body", body.shape(), placement.xMm(),
                    placement.yMm(), z, body.widthMm(), body.depthMm(), body.heightMm(), placement.rotation(),
                    bodyColor(c));
            bodies.add(solid);
        }
        solids.addAll(bodies);
        for (var pad : PcbGeometry.pads(project)) {
            solids.add(new Solid("pad:" + pad.pinId(), pad.componentId(), pad.reference() + "." + pad.pinNumber(),
                    "pad", "box", pad.xMm(), pad.yMm(), COPPER_MM / 2, pad.widthMm(), pad.heightMm(), COPPER_MM, 0,
                    "#c88a3c"));
            if (pad.drillMm() > 0) {
                solids.add(new Solid("pad-bottom:" + pad.pinId(), pad.componentId(), pad.reference() + "."
                        + pad.pinNumber(), "pad", "box", pad.xMm(), pad.yMm(), -t - COPPER_MM / 2, pad.widthMm(),
                        pad.heightMm(), COPPER_MM, 0, "#c88a3c"));
            }
        }
        for (Trace trace : board.traces()) {
            double z = trace.layer().equals("top") ? COPPER_MM / 2 : -t - COPPER_MM / 2;
            for (int i = 0; i + 1 < trace.points().size(); i++) {
                var a = trace.points().get(i);
                var b = trace.points().get(i + 1);
                double length = Math.hypot(b.xMm() - a.xMm(), b.yMm() - a.yMm()) + trace.widthMm();
                double angle = Math.toDegrees(Math.atan2(b.yMm() - a.yMm(), b.xMm() - a.xMm()));
                solids.add(new Solid("trace:" + trace.id() + ":" + i, trace.id(), "Trace", "trace", "box",
                        (a.xMm() + b.xMm()) / 2, (a.yMm() + b.yMm()) / 2, z, length, trace.widthMm(), COPPER_MM,
                        angle, trace.layer().equals("top") ? "#d4a017" : "#4f83cc"));
            }
        }
        List<Finding> findings = new ArrayList<>();
        double maxHeight = 0;
        for (int i = 0; i < bodies.size(); i++) {
            Solid a = bodies.get(i);
            maxHeight = Math.max(maxHeight, top(a));
            double[] ea = extent(a);
            if (a.xMm() - ea[0] < 0 || a.yMm() - ea[1] < 0 || a.xMm() + ea[0] > board.widthMm()
                    || a.yMm() + ea[1] > board.heightMm()) {
                findings.add(new Finding("ASM_BODY_OVERHANG", "WARNING", a.label() + " body overhangs the board edge.",
                        List.of(a.ownerId())));
            }
            for (int j = i + 1; j < bodies.size(); j++) {
                Solid b = bodies.get(j);
                double[] eb = extent(b);
                if (Math.abs(a.xMm() - b.xMm()) < ea[0] + eb[0] && Math.abs(a.yMm() - b.yMm()) < ea[1] + eb[1]) {
                    findings.add(new Finding("ASM_BODY_COLLISION", "ERROR", a.label() + " and " + b.label()
                            + " bodies intersect.", List.of(a.ownerId(), b.ownerId())));
                }
            }
        }
        return new Scene(board.widthMm(), board.heightMm(), t, maxHeight, solids, findings);
    }

    private static double top(Solid solid) {
        return solid.zMm() + (solid.shape().equals("axial") ? solid.sizeYMm() : solid.sizeZMm()) / 2;
    }

    /** Half extents in X and Y after rotation. */
    private static double[] extent(Solid solid) {
        boolean quarter = Math.round(solid.rotationDeg()) % 180 != 0;
        return new double[]{(quarter ? solid.sizeYMm() : solid.sizeXMm()) / 2,
                (quarter ? solid.sizeXMm() : solid.sizeYMm()) / 2};
    }
}
