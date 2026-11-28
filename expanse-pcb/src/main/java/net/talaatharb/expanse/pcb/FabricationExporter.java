package net.talaatharb.expanse.pcb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.talaatharb.expanse.domain.Design.Board;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Placement;
import net.talaatharb.expanse.domain.Design.Point;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Design.Trace;
import net.talaatharb.expanse.domain.Design.Via;
import net.talaatharb.expanse.pcb.PcbGeometry.PlacedPad;
import net.talaatharb.expanse.pcb.PcbGeometry.Rect;
import net.talaatharb.expanse.pcb.ZoneFiller.FillRect;
import net.talaatharb.expanse.pcb.ZoneFiller.ZoneFill;

/**
 * Manufacturing outputs: Gerber RS-274X (X2 attributes) copper, solder mask, silkscreen and outline layers, an
 * Excellon drill file, a bill of materials and a pick-and-place file. Coordinates are board millimetres with the
 * origin at the lower-left corner of the outline and Y up; rotations are counter-clockwise degrees.
 */
public final class FabricationExporter {
    public static final double MASK_EXPANSION_MM = 0.05;
    public static final double SILK_WIDTH_MM = 0.15;
    public static final double EDGE_WIDTH_MM = 0.1;

    public record FabricationFile(String name, String mediaType, String content) {
    }

    public List<FabricationFile> export(Project project) {
        String base = baseName(project.name());
        List<PlacedPad> pads = PcbGeometry.pads(project);
        List<ZoneFill> fills = new ZoneFiller().fill(project);
        List<FabricationFile> files = new ArrayList<>();
        files.add(gerber(base + "-F_Cu.gbr", copper(project, pads, fills, "top", "Copper,L1,Top")));
        files.add(gerber(base + "-B_Cu.gbr", copper(project, pads, fills, "bottom", "Copper,L2,Bot")));
        files.add(gerber(base + "-F_Mask.gbr", mask(project, pads, "top", "Soldermask,Top")));
        files.add(gerber(base + "-B_Mask.gbr", mask(project, pads, "bottom", "Soldermask,Bot")));
        files.add(gerber(base + "-F_SilkS.gbr", silk(project)));
        files.add(gerber(base + "-Edge_Cuts.gbr", outline(project)));
        files.add(new FabricationFile(base + ".drl", "text/plain", drill(project, pads)));
        files.add(new FabricationFile(base + "-BOM.csv", "text/csv", bom(project)));
        files.add(new FabricationFile(base + "-positions.csv", "text/csv", positions(project)));
        return files;
    }

    static String baseName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+|_+$", "");
        return cleaned.isEmpty() ? "board" : cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private static FabricationFile gerber(String name, String content) {
        return new FabricationFile(name, "application/vnd.gerber", content);
    }

    private static String copper(Project project, List<PlacedPad> pads, List<ZoneFill> fills, String layer,
                                 String function) {
        Gerber g = new Gerber(project, function, "Positive");
        for (ZoneFill fill : fills) {
            if (fill.layer().equals(layer)) {
                for (FillRect r : fill.rects()) {
                    g.region(r.xMm(), r.yMm(), r.xMm() + r.widthMm(), r.yMm() + r.heightMm());
                }
            }
        }
        for (Trace trace : project.board().traces()) {
            if (trace.layer().equals(layer)) {
                g.select(g.circle(trace.widthMm()));
                List<Point> points = trace.points();
                g.move(points.getFirst().xMm(), points.getFirst().yMm());
                for (int i = 1; i < points.size(); i++) {
                    g.line(points.get(i).xMm(), points.get(i).yMm());
                }
            }
        }
        for (PlacedPad pad : pads) {
            if (pad.onLayer(layer)) {
                g.select(g.rect(pad.widthMm(), pad.heightMm()));
                g.flash(pad.xMm(), pad.yMm());
            }
        }
        for (Via via : project.board().vias()) {
            g.select(g.circle(via.diameterMm()));
            g.flash(via.xMm(), via.yMm());
        }
        return g.finish();
    }

    /** Mask openings for pads; vias are tented. */
    private static String mask(Project project, List<PlacedPad> pads, String layer, String function) {
        Gerber g = new Gerber(project, function, "Negative");
        for (PlacedPad pad : pads) {
            if (pad.onLayer(layer)) {
                g.select(g.rect(pad.widthMm() + 2 * MASK_EXPANSION_MM, pad.heightMm() + 2 * MASK_EXPANSION_MM));
                g.flash(pad.xMm(), pad.yMm());
            }
        }
        return g.finish();
    }

    /** Courtyard outlines of the placed footprints. */
    private static String silk(Project project) {
        Gerber g = new Gerber(project, "Legend,Top", "Positive");
        g.select(g.circle(SILK_WIDTH_MM));
        for (Placement placement : project.board().placements()) {
            Rect r = PcbGeometry.courtyard(project.component(placement.componentId()), placement);
            if (r.width() > 0 && r.height() > 0) {
                g.box(r.centerX() - r.width() / 2, r.centerY() - r.height() / 2, r.centerX() + r.width() / 2,
                        r.centerY() + r.height() / 2);
            }
        }
        return g.finish();
    }

    private static String outline(Project project) {
        Board board = project.board();
        Gerber g = new Gerber(project, "Profile,NP", "Positive");
        g.select(g.circle(EDGE_WIDTH_MM));
        g.box(0, 0, board.widthMm(), board.heightMm());
        return g.finish();
    }

    /** Excellon drill file with plated holes of pads and vias, one tool per diameter. */
    private static String drill(Project project, List<PlacedPad> pads) {
        Map<Double, List<double[]>> holes = new TreeMap<>();
        for (PlacedPad pad : pads) {
            if (pad.drillMm() > 0) {
                holes.computeIfAbsent(round3(pad.drillMm()), k -> new ArrayList<>())
                        .add(new double[]{pad.xMm(), pad.yMm()});
            }
        }
        for (Via via : project.board().vias()) {
            holes.computeIfAbsent(round3(via.drillMm()), k -> new ArrayList<>()).add(new double[]{via.xMm(), via.yMm()});
        }
        StringBuilder out = new StringBuilder();
        out.append("M48\n; DRILL file {Expanse Design} for ").append(project.name().replaceAll("[\\r\\n]", " "))
                .append("\n; FORMAT={-:-/ absolute / metric / decimal}\n; #@! TF.FileFunction,Plated,1,2,PTH\n");
        out.append("FMAT,2\nMETRIC\n");
        int tool = 1;
        for (double diameter : holes.keySet()) {
            out.append(String.format(Locale.ROOT, "T%dC%.3f%n", tool++, diameter));
        }
        out.append("%\nG90\nG05\n");
        tool = 1;
        for (var entry : holes.entrySet()) {
            out.append("T").append(tool++).append('\n');
            for (double[] hole : entry.getValue()) {
                out.append(String.format(Locale.ROOT, "X%.3fY%.3f%n", hole[0], hole[1]));
            }
        }
        out.append("T0\nM30\n");
        return out.toString().replace("\r\n", "\n");
    }

    /** One line per definition and value, physical parts only. */
    private static String bom(Project project) {
        Map<String, List<Component>> groups = new LinkedHashMap<>();
        project.components().stream().filter(c -> c.definition().footprint().physical())
                .sorted(Comparator.comparing(Component::reference, FabricationExporter::naturalOrder))
                .forEach(c -> groups.computeIfAbsent(c.definition().id() + "\u0000" + value(c),
                        k -> new ArrayList<>()).add(c));
        StringBuilder out = new StringBuilder("Item,Quantity,References,Value,Part,Footprint\n");
        int item = 1;
        for (List<Component> group : groups.values()) {
            Component first = group.getFirst();
            out.append(item++).append(',').append(group.size()).append(',')
                    .append(csv(String.join(" ", group.stream().map(Component::reference).toList()))).append(',')
                    .append(csv(value(first))).append(',').append(csv(first.definition().name())).append(',')
                    .append(csv(first.definition().footprint().name())).append('\n');
        }
        return out.toString();
    }

    private static String positions(Project project) {
        StringBuilder out = new StringBuilder("Designator,Value,Package,PosX,PosY,Rotation,Side\n");
        project.board().placements().stream()
                .map(p -> Map.entry(project.component(p.componentId()), p))
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Component::reference,
                        FabricationExporter::naturalOrder)))
                .forEach(e -> out.append(csv(e.getKey().reference())).append(',').append(csv(value(e.getKey())))
                        .append(',').append(csv(e.getKey().definition().footprint().name())).append(',')
                        .append(String.format(Locale.ROOT, "%.3f,%.3f,%d,top", e.getValue().xMm(), e.getValue().yMm(),
                                e.getValue().rotation())).append('\n'));
        return out.toString();
    }

    /** The defining parameter of passives and sources; the part name otherwise. */
    static String value(Component c) {
        String parameter = switch (c.definition().kind()) {
            case "resistor" -> "resistance";
            case "capacitor" -> "capacitance";
            case "inductor" -> "inductance";
            case "voltage-source", "power-rail" -> "voltage";
            case "led" -> "color";
            default -> null;
        };
        String value = parameter == null ? null : c.properties().get(parameter);
        return value == null ? c.definition().name() : value;
    }

    private static String csv(String text) {
        return text.matches(".*[,\"\\r\\n].*") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    /** Orders R2 before R10. */
    static int naturalOrder(String a, String b) {
        String pa = a.replaceAll("\\d{1,18}$", "");
        String pb = b.replaceAll("\\d{1,18}$", "");
        int prefix = pa.compareTo(pb);
        if (prefix != 0 || pa.length() == a.length() || pb.length() == b.length()) {
            return prefix != 0 ? prefix : a.compareTo(b);
        }
        return Long.compare(Long.parseLong(a.substring(pa.length())), Long.parseLong(b.substring(pb.length())));
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    /** Minimal RS-274X writer: format 4.6 in millimetres, apertures defined on first use. */
    private static final class Gerber {
        private final Map<String, Integer> apertures = new LinkedHashMap<>();
        private final StringBuilder body = new StringBuilder();
        private final String header;
        private int current = -1;

        Gerber(Project project, String function, String polarity) {
            header = "%TF.GenerationSoftware,Expanse Design,expanse*%\n"
                    + "%TF.ProjectId," + baseName(project.name()) + "," + project.id() + "," + project.revision() + "*%\n"
                    + "%TF.FileFunction," + function + "*%\n"
                    + "%TF.FilePolarity," + polarity + "*%\n"
                    + "%FSLAX46Y46*%\n%MOMM*%\n%LPD*%\n";
        }

        int circle(double diameter) {
            return aperture(String.format(Locale.ROOT, "C,%.6f", diameter));
        }

        int rect(double width, double height) {
            return aperture(String.format(Locale.ROOT, "R,%.6fX%.6f", width, height));
        }

        private int aperture(String template) {
            return apertures.computeIfAbsent(template, k -> 10 + apertures.size());
        }

        void select(int aperture) {
            if (aperture != current) {
                body.append('D').append(aperture).append("*\n");
                current = aperture;
            }
        }

        void move(double x, double y) {
            body.append(xy(x, y)).append("D02*\n");
        }

        void line(double x, double y) {
            body.append(xy(x, y)).append("D01*\n");
        }

        void flash(double x, double y) {
            body.append(xy(x, y)).append("D03*\n");
        }

        void box(double x0, double y0, double x1, double y1) {
            move(x0, y0);
            line(x1, y0);
            line(x1, y1);
            line(x0, y1);
            line(x0, y0);
        }

        void region(double x0, double y0, double x1, double y1) {
            body.append("G36*\n");
            box(x0, y0, x1, y1);
            body.append("G37*\n");
        }

        String finish() {
            StringBuilder out = new StringBuilder(header);
            apertures.forEach((template, number) -> out.append("%ADD").append(number).append(template).append("*%\n"));
            out.append("G01*\n").append(body).append("M02*\n");
            return out.toString();
        }

        private static String xy(double x, double y) {
            return "X" + Math.round(x * 1e6) + "Y" + Math.round(y * 1e6);
        }
    }
}
