package net.talaatharb.expanse.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.talaatharb.expanse.domain.Design;

/**
 * Reads the subset of SPICE3/ngspice netlists that maps onto the built-in parts: R, C, L, independent V sources (DC
 * value), D, Q and M devices and {@code .model} cards. The first line is the title; {@code *} starts a comment line,
 * {@code ;} and {@code $} start inline comments and {@code +} continues the previous line. Other elements and dot
 * commands are skipped with a warning. Names, types and suffixes are case-insensitive; node names are kept as written.
 */
public final class SpiceParser {
    public static final int MAX_ELEMENTS = 2_000;
    private static final Pattern NUMBER = Pattern.compile(
            "^([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)([A-Za-z]*)$");
    private static final Set<String> IGNORED_COMMANDS = Set.of(".op", ".tran", ".ac", ".dc", ".title", ".option",
            ".options", ".control", ".endc", ".print", ".plot", ".probe", ".save");
    private static final Pattern PARAM = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^\\s=()]+)");

    /** {@code type} is the upper-case element letter; {@code value} is set for R/C/L/V. */
    public record Element(String name, char type, List<String> nodes, Double value, String model) {
    }

    public record Model(String name, String type, Map<String, Double> parameters) {
        public Double parameter(String key) {
            return parameters.get(key.toUpperCase(Locale.ROOT));
        }
    }

    public record Netlist(String title, List<Element> elements, Map<String, Model> models, List<String> warnings) {
        public Model model(String name) {
            return name == null ? null : models.get(name.toUpperCase(Locale.ROOT));
        }
    }

    public Netlist parse(String text) {
        Design.require(text != null && !text.isBlank(), "The netlist is empty");
        List<String> lines = logicalLines(text);
        String title = lines.isEmpty() ? "" : lines.getFirst().replaceFirst("^\\*\\s*", "").trim();
        List<String> warnings = new ArrayList<>();
        Map<String, Model> models = new LinkedHashMap<>();
        List<String[]> cards = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(".")) {
                String command = line.split("\\s+")[0].toLowerCase(Locale.ROOT);
                if (command.equals(".end")) {
                    break;
                }
                if (command.equals(".model")) {
                    Model model = model(line);
                    models.put(model.name().toUpperCase(Locale.ROOT), model);
                } else if (!IGNORED_COMMANDS.contains(command)) {
                    warnings.add("Ignored " + command + " (not supported)");
                }
                continue;
            }
            cards.add(line.split("\\s+"));
        }
        List<Element> elements = new ArrayList<>();
        for (String[] t : cards) {
            Design.require(elements.size() < MAX_ELEMENTS, "A netlist supports at most " + MAX_ELEMENTS + " elements");
            char type = Character.toUpperCase(t[0].charAt(0));
            try {
                Element element = switch (type) {
                    case 'R', 'C', 'L' -> {
                        need(t, 4);
                        yield new Element(t[0], type, List.of(t[1], t[2]), number(t[3]), null);
                    }
                    case 'V' -> {
                        need(t, 3);
                        yield new Element(t[0], type, List.of(t[1], t[2]), sourceValue(t, warnings), null);
                    }
                    case 'D' -> {
                        need(t, 4);
                        yield new Element(t[0], type, List.of(t[1], t[2]), null, t[3]);
                    }
                    case 'Q' -> {
                        need(t, 5);
                        String model = t.length > 5 && models.containsKey(t[5].toUpperCase(Locale.ROOT))
                                && !models.containsKey(t[4].toUpperCase(Locale.ROOT)) ? t[5] : t[4];
                        yield new Element(t[0], type, List.of(t[1], t[2], t[3]), null, model);
                    }
                    case 'M' -> {
                        need(t, 6);
                        yield new Element(t[0], type, List.of(t[1], t[2], t[3]), null, t[5]);
                    }
                    default -> null;
                };
                if (element == null) {
                    warnings.add("Skipped " + t[0] + ": element type " + type + " is not supported");
                } else {
                    elements.add(element);
                }
            } catch (RuntimeException e) {
                warnings.add("Skipped " + t[0] + ": " + e.getMessage());
            }
        }
        for (Element e : elements) {
            if (e.model() != null && !models.containsKey(e.model().toUpperCase(Locale.ROOT))) {
                warnings.add(e.name() + " uses undefined model " + e.model() + "; default parameters are used");
            }
        }
        return new Netlist(title, List.copyOf(elements), Map.copyOf(models), List.copyOf(warnings));
    }

    /** A SPICE number: engineering suffix (T, G, MEG, K, M = milli, MIL, U, N, P, F) followed by any unit letters. */
    public static double number(String text) {
        var matcher = NUMBER.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a number");
        }
        var mantissa = new java.math.BigDecimal(matcher.group(1));
        String suffix = matcher.group(2).toUpperCase(Locale.ROOT);
        if (suffix.startsWith("MIL")) {
            return mantissa.multiply(new java.math.BigDecimal("25.4e-6")).doubleValue();
        }
        int exponent = suffix.startsWith("MEG") ? 6 : suffix.isEmpty() ? 0 : switch (suffix.charAt(0)) {
            case 'T' -> 12;
            case 'G' -> 9;
            case 'K' -> 3;
            case 'M' -> -3;
            case 'U' -> -6;
            case 'N' -> -9;
            case 'P' -> -12;
            case 'F' -> -15;
            default -> 0;
        };
        return mantissa.scaleByPowerOfTen(exponent).doubleValue();
    }

    private static Double sourceValue(String[] t, List<String> warnings) {
        for (int i = 3; i < t.length; i++) {
            if (t[i].equalsIgnoreCase("DC") && i + 1 < t.length) {
                return number(t[i + 1]);
            }
        }
        if (t.length > 3) {
            try {
                return number(t[3]);
            } catch (IllegalArgumentException e) {
                // A transient or AC-only specification; fall through to 0 V.
            }
        }
        warnings.add(t[0] + " has no DC value; 0 V is used");
        return 0.0;
    }

    private static Model model(String line) {
        String body = line.substring(line.indexOf(' ') + 1).trim();
        String[] head = body.split("[\\s(]+", 3);
        Design.require(head.length >= 2, "A .model card needs a name and a type");
        Map<String, Double> parameters = new LinkedHashMap<>();
        var matcher = PARAM.matcher(head.length > 2 ? head[2] : "");
        while (matcher.find()) {
            try {
                parameters.put(matcher.group(1).toUpperCase(Locale.ROOT), number(matcher.group(2)));
            } catch (IllegalArgumentException ignored) {
                // Non-numeric model parameters are irrelevant to the built-in parts.
            }
        }
        return new Model(head[0], head[1].toUpperCase(Locale.ROOT), Map.copyOf(parameters));
    }

    private static void need(String[] tokens, int count) {
        if (tokens.length < count) {
            throw new IllegalArgumentException("expected at least " + (count - 1) + " fields");
        }
    }

    private static String stripComment(String line) {
        if (line.startsWith("*")) {
            return "";
        }
        int cut = line.length();
        for (String marker : new String[]{";", " $", "\t$"}) {
            int at = line.indexOf(marker);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        return line.substring(0, cut);
    }

    private static List<String> logicalLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n|\\r")) {
            String stripped = raw.stripLeading();
            if (stripped.startsWith("+") && !lines.isEmpty()) {
                lines.set(lines.size() - 1, stripComment(lines.getLast()) + " " + stripped.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }
}
