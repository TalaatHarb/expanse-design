package net.talaatharb.expanse.desktop;

import java.util.List;
import java.util.Map;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import net.talaatharb.expanse.dto.ApiModels.ComponentView;
import net.talaatharb.expanse.dto.ApiModels.PinView;

/**
 * Schematic symbol geometry and drawing. Coordinates are millimetres with Y down; a rotation of 90 maps a local
 * offset (x, y) to (-y, x), matching the server's convention. Pin offsets sit on the 2.54 mm grid so snapped
 * components have snapped pins.
 */
final class Symbols {
    static final double GRID = 2.54;
    private static final double LEAD = 2 * GRID;

    private static final Map<String, Map<String, double[]>> PINS = Map.ofEntries(
            Map.entry("resistor", twoPinHorizontal()),
            Map.entry("capacitor", twoPinHorizontal()),
            Map.entry("inductor", twoPinHorizontal()),
            Map.entry("switch", twoPinHorizontal()),
            Map.entry("voltage-source", Map.of("1", new double[]{0, -LEAD}, "2", new double[]{0, LEAD})),
            Map.entry("power-rail", Map.of("1", new double[]{0, GRID})),
            Map.entry("ground", Map.of("1", new double[]{0, -GRID})),
            Map.entry("npn", transistor()),
            Map.entry("pnp", transistor()),
            Map.entry("nmos", transistor()),
            Map.entry("pmos", transistor()),
            Map.entry("logic-and", gate()),
            Map.entry("logic-or", gate()),
            Map.entry("logic-nand", gate()),
            Map.entry("logic-nor", gate()),
            Map.entry("logic-xor", gate()),
            Map.entry("logic-not", Map.of("1", new double[]{-LEAD, 0}, "2", new double[]{LEAD, 0})),
            Map.entry("logic-input", Map.of("1", new double[]{LEAD, 0})),
            Map.entry("logic-clock", Map.of("1", new double[]{LEAD, 0})));
    private static final double DIP_PIN_X = 3 * GRID;
    private static final double DIP_HALF_HEIGHT = 4 * GRID;

    private Symbols() {
    }

    private static Map<String, double[]> twoPinHorizontal() {
        return Map.of("1", new double[]{-LEAD, 0}, "2", new double[]{LEAD, 0});
    }

    /** Two-input gate: A (1) upper left, B (2) lower left, Y (3) right. */
    private static Map<String, double[]> gate() {
        return Map.of("1", new double[]{-LEAD, -GRID}, "2", new double[]{-LEAD, GRID}, "3", new double[]{LEAD, 0});
    }

    static boolean isDip(String kind) {
        return kind.startsWith("logic-74");
    }

    /** DIP-14 schematic layout: pins 1-7 down the left side, 8-14 up the right side, like the package. */
    private static double[] dipPin(String number) {
        int n;
        try {
            n = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1 || n > 14) {
            return null;
        }
        return n <= 7 ? new double[]{-DIP_PIN_X, (n - 4) * GRID} : new double[]{DIP_PIN_X, (11 - n) * GRID};
    }

    /** Pin 1 = emitter/source (bottom), 2 = base/gate (left), 3 = collector/drain (top). */
    private static Map<String, double[]> transistor() {
        return Map.of("1", new double[]{GRID, LEAD}, "2", new double[]{-LEAD, 0}, "3", new double[]{GRID, -LEAD});
    }

    static double[] rotate(double x, double y, int rotation) {
        return switch (Math.floorMod(rotation, 360)) {
            case 90 -> new double[]{0.0 - y, x};
            case 180 -> new double[]{0.0 - x, 0.0 - y};
            case 270 -> new double[]{y, 0.0 - x};
            default -> new double[]{x, y};
        };
    }

    /** Local pin offset before rotation; unknown kinds spread pins along a horizontal line. */
    static double[] localPin(String kind, List<PinView> pins, PinView pin) {
        var known = PINS.get(kind);
        if (known != null && known.containsKey(pin.number())) {
            return known.get(pin.number());
        }
        if (isDip(kind) && dipPin(pin.number()) != null) {
            return dipPin(pin.number());
        }
        int index = pins.indexOf(pin);
        return new double[]{-LEAD + index * GRID * 2, LEAD};
    }

    /** Absolute schematic position of a pin in mm. */
    static double[] pin(ComponentView component, PinView pin) {
        double[] local = localPin(component.kind(), component.pins(), pin);
        double[] rotated = rotate(local[0], local[1], component.rotation());
        return new double[]{component.xMm() + rotated[0], component.yMm() + rotated[1]};
    }

    /** Half extents of the symbol body in mm after rotation, used for hit testing. */
    static double[] halfExtents(ComponentView component) {
        double[] local = switch (component.kind()) {
            case "resistor", "capacitor", "inductor", "switch" -> new double[]{LEAD, 2};
            case "voltage-source" -> new double[]{3.2, LEAD};
            case "power-rail", "ground" -> new double[]{2.5, 2.8};
            case "logic-input", "logic-clock" -> new double[]{LEAD, GRID};
            case "logic-and", "logic-or", "logic-nand", "logic-nor", "logic-xor", "logic-not" ->
                    new double[]{LEAD, GRID + 0.5};
            default -> isDip(component.kind()) ? new double[]{DIP_PIN_X, DIP_HALF_HEIGHT + 1}
                    : new double[]{LEAD, LEAD};
        };
        int rotation = Math.floorMod(component.rotation(), 180);
        return rotation == 90 ? new double[]{local[1], local[0]} : local;
    }

    static String referencePrefix(String kind) {
        return switch (kind) {
            case "resistor" -> "R";
            case "capacitor" -> "C";
            case "inductor" -> "L";
            case "voltage-source" -> "V";
            case "power-rail" -> "PWR";
            case "ground" -> "GND";
            case "switch" -> "SW";
            case "npn", "pnp" -> "Q";
            case "nmos", "pmos" -> "M";
            case "logic-input" -> "IN";
            case "logic-clock" -> "CLK";
            default -> "U";
        };
    }

    /** The headline value shown next to the symbol, e.g. {@code 4.7k} or {@code closed}. */
    static String value(ComponentView component) {
        if (isDip(component.kind())) {
            return "74HC" + component.kind().substring("logic-74hc".length()).toUpperCase(java.util.Locale.ROOT);
        }
        if (component.kind().startsWith("logic-") && !component.kind().equals("logic-input")
                && !component.kind().equals("logic-clock")) {
            return component.kind().substring("logic-".length()).toUpperCase(java.util.Locale.ROOT);
        }
        if (component.parameters().isEmpty()) {
            return "";
        }
        String first = component.parameters().getFirst().name();
        String value = component.properties().getOrDefault(first, "");
        String unit = component.parameters().getFirst().unit();
        return switch (unit) {
            case "ohm" -> value + "\u03a9";
            case "F", "H", "V", "Hz" -> value + unit;
            default -> value;
        };
    }

    /**
     * Draws the symbol body in local millimetre units; the caller has already translated, scaled and rotated the
     * graphics context. Pins are drawn by the caller.
     */
    static void drawBody(GraphicsContext g, ComponentView component) {
        g.setLineWidth(0.25);
        switch (component.kind()) {
            case "resistor" -> {
                lead(g, -LEAD, 0, -3, 0);
                lead(g, 3, 0, LEAD, 0);
                double[] xs = {-3, -2.5, -1.5, -0.5, 0.5, 1.5, 2.5, 3};
                double[] ys = {0, -1, 1, -1, 1, -1, 1, 0};
                g.strokePolyline(xs, ys, xs.length);
            }
            case "capacitor" -> {
                lead(g, -LEAD, 0, -0.6, 0);
                lead(g, 0.6, 0, LEAD, 0);
                g.setLineWidth(0.4);
                g.strokeLine(-0.6, -2, -0.6, 2);
                g.strokeLine(0.6, -2, 0.6, 2);
            }
            case "inductor" -> {
                lead(g, -LEAD, 0, -3, 0);
                lead(g, 3, 0, LEAD, 0);
                for (int i = 0; i < 4; i++) {
                    g.strokeArc(-3 + i * 1.5, -0.75, 1.5, 1.5, 0, 180, ArcType.OPEN);
                }
            }
            case "switch" -> {
                lead(g, -LEAD, 0, -2.2, 0);
                lead(g, 2.2, 0, LEAD, 0);
                g.strokeOval(-2.7, -0.5, 1, 1);
                g.strokeOval(1.7, -0.5, 1, 1);
                boolean closed = "closed".equals(component.properties().get("state"));
                g.strokeLine(-1.8, -0.2, 1.9, closed ? -0.4 : -2.2);
            }
            case "voltage-source" -> {
                lead(g, 0, -LEAD, 0, -3);
                lead(g, 0, 3, 0, LEAD);
                g.strokeOval(-3, -3, 6, 6);
                g.strokeLine(-0.7, -1.6, 0.7, -1.6);
                g.strokeLine(0, -2.3, 0, -0.9);
                g.strokeLine(-0.7, 1.6, 0.7, 1.6);
            }
            case "power-rail" -> {
                lead(g, 0, GRID, 0, 0);
                g.setLineWidth(0.4);
                g.strokeLine(-2, 0, 2, 0);
            }
            case "ground" -> {
                lead(g, 0, -GRID, 0, 0);
                g.setLineWidth(0.35);
                g.strokeLine(-2, 0, 2, 0);
                g.strokeLine(-1.3, 0.8, 1.3, 0.8);
                g.strokeLine(-0.6, 1.6, 0.6, 1.6);
            }
            case "npn", "pnp" -> {
                g.strokeOval(-2.4, -3.4, 6.8, 6.8);
                lead(g, -LEAD, 0, -1, 0);
                g.setLineWidth(0.4);
                g.strokeLine(-1, -2, -1, 2);
                g.setLineWidth(0.25);
                g.strokePolyline(new double[]{-1, GRID, GRID}, new double[]{-0.8, -2.6, -LEAD}, 3);
                g.strokePolyline(new double[]{-1, GRID, GRID}, new double[]{0.8, 2.6, LEAD}, 3);
                boolean npn = component.kind().equals("npn");
                double[] from = npn ? new double[]{-1, 0.8} : new double[]{GRID, 2.6};
                double[] to = npn ? new double[]{GRID, 2.6} : new double[]{-1, 0.8};
                arrowHead(g, from, to);
            }
            case "nmos", "pmos" -> {
                g.strokeOval(-2.4, -3.4, 6.8, 6.8);
                lead(g, -LEAD, 0, -1.6, 0);
                g.setLineWidth(0.4);
                g.strokeLine(-1.6, -2, -1.6, 2);
                for (double y : new double[]{-2.2, -0.5, 1.2}) {
                    g.strokeLine(-0.8, y, -0.8, y + 1);
                }
                g.setLineWidth(0.25);
                g.strokePolyline(new double[]{-0.8, GRID, GRID}, new double[]{-1.7, -1.7, -LEAD}, 3);
                g.strokePolyline(new double[]{-0.8, GRID, GRID}, new double[]{1.7, 1.7, LEAD}, 3);
                g.strokePolyline(new double[]{-0.8, GRID, GRID}, new double[]{0, 0, 1.7}, 3);
                boolean nmos = component.kind().equals("nmos");
                arrowHead(g, nmos ? new double[]{GRID, 0} : new double[]{-0.8, 0},
                        nmos ? new double[]{-0.8, 0} : new double[]{1.6, 0});
            }
            case "logic-and", "logic-nand", "logic-or", "logic-nor", "logic-xor" -> drawGate(g, component.kind());
            case "logic-not" -> {
                lead(g, -LEAD, 0, -1.8, 0);
                g.strokePolygon(new double[]{-1.8, -1.8, 1.4}, new double[]{-1.9, 1.9, 0}, 3);
                g.strokeOval(1.4, -0.4, 0.8, 0.8);
                lead(g, 2.2, 0, LEAD, 0);
            }
            case "logic-input" -> {
                g.strokeRect(-LEAD, -1.6, LEAD + 1.5, 3.2);
                lead(g, 1.5, 0, LEAD, 0);
                boolean high = "high".equals(component.properties().get("level"));
                g.setFont(Font.font(2.2));
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.CENTER);
                g.fillText(high ? "1" : "0", -1.8, 0);
            }
            case "logic-clock" -> {
                g.strokeRect(-LEAD, -1.6, LEAD + 1.5, 3.2);
                lead(g, 1.5, 0, LEAD, 0);
                g.strokePolyline(new double[]{-4.3, -3.4, -3.4, -2, -2, -0.6, -0.6, 0.6},
                        new double[]{0.8, 0.8, -0.8, -0.8, 0.8, 0.8, -0.8, -0.8}, 8);
            }
            default -> {
                if (isDip(component.kind())) {
                    drawDip(g, component);
                } else {
                    g.strokeRect(-LEAD, -GRID, 2 * LEAD, 2 * GRID);
                }
            }
        }
    }

    private static void drawGate(GraphicsContext g, String kind) {
        boolean inverted = kind.equals("logic-nand") || kind.equals("logic-nor");
        boolean orShape = !kind.equals("logic-and") && !kind.equals("logic-nand");
        double outX = inverted ? 1.6 : 2.4;
        lead(g, -LEAD, -GRID, -2.2, -GRID);
        lead(g, -LEAD, GRID, -2.2, GRID);
        if (orShape) {
            g.strokeArc(-3.6, -3, 2.4, 6, -90, 180, ArcType.OPEN);
            g.strokeArc(-4.6, -3, outX + 4.6, 6, 0, 90, ArcType.OPEN);
            g.strokeArc(-4.6, -3, outX + 4.6, 6, 270, 90, ArcType.OPEN);
            if (kind.equals("logic-xor")) {
                g.strokeArc(-4.4, -3, 2.4, 6, -90, 180, ArcType.OPEN);
            }
        } else {
            g.strokeLine(-2.4, -3, -2.4, 3);
            g.strokeLine(-2.4, -3, outX - 3, -3);
            g.strokeLine(-2.4, 3, outX - 3, 3);
            g.strokeArc(outX - 6, -3, 6, 6, -90, 180, ArcType.OPEN);
        }
        if (inverted) {
            g.strokeOval(outX, -0.4, 0.8, 0.8);
            lead(g, outX + 0.8, 0, LEAD, 0);
        } else {
            lead(g, outX, 0, LEAD, 0);
        }
    }

    private static void drawDip(GraphicsContext g, ComponentView component) {
        double body = 2 * GRID;
        g.strokeRect(-body, -DIP_HALF_HEIGHT, 2 * body, 2 * DIP_HALF_HEIGHT);
        g.strokeArc(-0.8, -DIP_HALF_HEIGHT - 0.8, 1.6, 1.6, 180, 180, ArcType.OPEN);
        g.setFont(Font.font(1.1));
        g.setTextBaseline(VPos.CENTER);
        for (PinView pin : component.pins()) {
            double[] at = dipPin(pin.number());
            if (at == null) {
                continue;
            }
            boolean left = at[0] < 0;
            lead(g, at[0], at[1], left ? -body : body, at[1]);
            g.setTextAlign(left ? TextAlignment.LEFT : TextAlignment.RIGHT);
            g.fillText(pin.name(), left ? -body + 0.4 : body - 0.4, at[1]);
        }
    }

    /** Draws reference and value text unrotated near the symbol at screen position (sx, sy). */
    static void drawLabels(GraphicsContext g, ComponentView component, double sx, double sy, double scale) {
        double[] half = halfExtents(component);
        g.setFont(Font.font(Math.max(9, Math.min(14, scale * 1.4))));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BOTTOM);
        double x = sx + half[0] * scale + 3;
        double y = sy - half[1] * scale + 2;
        if (component.kind().equals("ground") || component.kind().equals("power-rail")) {
            g.fillText(component.kind().equals("power-rail") ? value(component) : "", x, y + 4);
            return;
        }
        g.fillText(component.reference(), x, y + 4);
        g.setTextBaseline(VPos.TOP);
        g.fillText(value(component), x, y + 4);
    }

    private static void lead(GraphicsContext g, double x1, double y1, double x2, double y2) {
        g.strokeLine(x1, y1, x2, y2);
    }

    private static void arrowHead(GraphicsContext g, double[] from, double[] to) {
        double angle = Math.atan2(to[1] - from[1], to[0] - from[0]);
        double size = 1.1;
        double[] xs = {to[0], to[0] - size * Math.cos(angle - 0.45), to[0] - size * Math.cos(angle + 0.45)};
        double[] ys = {to[1], to[1] - size * Math.sin(angle - 0.45), to[1] - size * Math.sin(angle + 0.45)};
        g.fillPolygon(xs, ys, 3);
    }
}
