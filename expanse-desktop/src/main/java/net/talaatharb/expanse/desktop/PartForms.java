package net.talaatharb.expanse.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.dto.ApiModels.DefinitionView;
import net.talaatharb.expanse.dto.ApiModels.PadSpec;
import net.talaatharb.expanse.dto.ApiModels.ParameterView;
import net.talaatharb.expanse.dto.ApiModels.PartSpec;
import net.talaatharb.expanse.dto.ApiModels.PinSpec;
import net.talaatharb.expanse.dto.ApiModels.PinView;

/**
 * Text forms used by the part editor: one item per line with comma-separated fields, plus pad generators. Parsing
 * errors carry the 1-based line number. The server performs the authoritative validation.
 */
final class PartForms {
    private PartForms() {
    }

    /** {@code number, name[, optional]} per line; the name defaults to the number. */
    static List<PinSpec> pins(String text) {
        var result = new ArrayList<PinSpec>();
        each(text, (line, f) -> {
            require(f.length <= 3, line, "expected: number, name[, optional]");
            boolean optional = f.length == 3 && f[2].equalsIgnoreCase("optional");
            require(f.length < 3 || optional || f[2].equalsIgnoreCase("required"), line,
                    "third field must be optional or required");
            result.add(new PinSpec(f[0], f.length > 1 && !f[1].isEmpty() ? f[1] : f[0], !optional));
        });
        return result;
    }

    static String pinsText(List<PinSpec> pins) {
        var lines = new ArrayList<String>();
        pins.forEach(p -> lines.add(p.number() + ", " + p.name() + (p.required() ? "" : ", optional")));
        return String.join("\n", lines);
    }

    /** {@code name, unit, default[, a|b|c]} per line; the unit may be empty. */
    static List<ParameterView> parameters(String text) {
        var result = new ArrayList<ParameterView>();
        each(text, (line, f) -> {
            require(f.length == 3 || f.length == 4, line, "expected: name, unit, default[, a|b|c]");
            var allowed = f.length == 4 && !f[3].isEmpty()
                    ? Arrays.stream(f[3].split("\\|")).map(String::strip).filter(s -> !s.isEmpty()).toList()
                    : List.<String>of();
            require(allowed.isEmpty() || allowed.contains(f[2]), line, "default must be one of the allowed values");
            result.add(new ParameterView(f[0], f[1], f[2], allowed));
        });
        return result;
    }

    static String parametersText(List<ParameterView> parameters) {
        var lines = new ArrayList<String>();
        parameters.forEach(p -> lines.add(p.name() + ", " + p.unit() + ", " + p.defaultValue()
                + (p.allowedValues().isEmpty() ? "" : ", " + String.join("|", p.allowedValues()))));
        return String.join("\n", lines);
    }

    /** {@code number, x, y, width, height, drill} per line in mm; drill 0 means a surface-mount pad. */
    static List<PadSpec> pads(String text) {
        var result = new ArrayList<PadSpec>();
        each(text, (line, f) -> {
            require(f.length == 6, line, "expected: number, x, y, width, height, drill");
            double[] v = new double[5];
            for (int i = 0; i < 5; i++) {
                try {
                    v[i] = Double.parseDouble(f[i + 1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Line " + line + ": '" + f[i + 1] + "' is not a number");
                }
            }
            result.add(new PadSpec(f[0], v[0], v[1], v[2], v[3], v[4]));
        });
        return result;
    }

    static String padsText(List<PadSpec> pads) {
        var lines = new ArrayList<String>();
        pads.forEach(p -> lines.add(p.number() + ", " + number(p.xMm()) + ", " + number(p.yMm()) + ", "
                + number(p.widthMm()) + ", " + number(p.heightMm()) + ", " + number(p.drillMm())));
        return String.join("\n", lines);
    }

    /**
     * Dual in-line pads: the first half of the pins runs down the left column, the rest up the right column, like a
     * DIP package seen from above. Odd counts put the extra pin on the left.
     */
    static List<PadSpec> dip(List<PinSpec> pins, double pitch, double rowSpacing, double size, double drill) {
        int left = (pins.size() + 1) / 2;
        var result = new ArrayList<PadSpec>();
        for (int i = 0; i < pins.size(); i++) {
            boolean onLeft = i < left;
            int row = onLeft ? i : pins.size() - 1 - i;
            double y = round((row - (left - 1) / 2.0) * pitch);
            result.add(new PadSpec(pins.get(i).number(), round((onLeft ? -1 : 1) * rowSpacing / 2), y, size, size,
                    drill));
        }
        return result;
    }

    /** Single in-line pads along X, centred on the origin. */
    static List<PadSpec> sip(List<PinSpec> pins, double pitch, double size, double drill) {
        var result = new ArrayList<PadSpec>();
        for (int i = 0; i < pins.size(); i++) {
            result.add(new PadSpec(pins.get(i).number(), round((i - (pins.size() - 1) / 2.0) * pitch), 0, size, size,
                    drill));
        }
        return result;
    }

    /** Courtyard width and height: pad bounding box plus a 0.5 mm margin on each side, centred on the origin. */
    static double[] courtyard(List<PadSpec> pads) {
        double x = 0;
        double y = 0;
        for (PadSpec p : pads) {
            x = Math.max(x, Math.abs(p.xMm()) + p.widthMm() / 2);
            y = Math.max(y, Math.abs(p.yMm()) + p.heightMm() / 2);
        }
        return pads.isEmpty() ? new double[] {0, 0} : new double[] {round(2 * x + 1), round(2 * y + 1)};
    }

    /** Palette entry for a project part; pin IDs are placeholders because definitions have no instances. */
    static DefinitionView definition(PartSpec part) {
        boolean physical = part.pads() != null && !part.pads().isEmpty();
        return new DefinitionView(part.id(), part.version(), part.name(), part.kind(), part.pins().stream()
                .map(p -> new PinView(UUID.nameUUIDFromBytes((part.id() + "/" + p.number()).getBytes()), p.number(),
                        p.name(), p.required())).toList(), part.parameters(), true, physical,
                !"generic".equals(part.kind()));
    }

    static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : java.math.BigDecimal.valueOf(round(value)).stripTrailingZeros().toPlainString();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private interface LineHandler {
        void accept(int line, String[] fields);
    }

    private static void each(String text, LineHandler handler) {
        String[] lines = text == null ? new String[0] : text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] fields = Arrays.stream(lines[i].split(",", -1)).map(String::strip).toArray(String[]::new);
            require(!fields[0].isEmpty(), i + 1, "the first field is required");
            handler.accept(i + 1, fields);
        }
    }

    private static void require(boolean condition, int line, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Line " + line + ": " + message);
        }
    }
}
