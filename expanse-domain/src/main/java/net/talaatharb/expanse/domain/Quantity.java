package net.talaatharb.expanse.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Engineering-notation values such as {@code 4.7k}, {@code 10uF}, {@code 1meg} or {@code 2.2e-3}.
 * Prefixes are case-sensitive: {@code m} is milli and {@code M}/{@code meg} is mega.
 */
public final class Quantity {
    private static final Pattern FORMAT = Pattern.compile(
            "^([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(meg|MEG|Meg|[TGMkKmuµnpf])?\\s*([A-Za-zΩ/^0-9]*)$");

    private Quantity() {
    }

    public static double parse(String text, String unit) {
        Design.require(text != null && text.length() <= 40, "Value must be at most 40 characters");
        var matcher = FORMAT.matcher(text.trim());
        Design.require(matcher.matches(), "Invalid value '" + text + "'; use e.g. 4.7k, 10u or 2.2e-3");
        String suffix = matcher.group(3);
        Design.require(suffix.isEmpty() || unitMatches(suffix, unit),
                "Unexpected unit '" + suffix + "' for a value in " + (unit.isEmpty() ? "no unit" : unit));
        double value = Double.parseDouble(matcher.group(1)) * multiplier(matcher.group(2));
        Design.require(Double.isFinite(value), "Value must be finite");
        return value;
    }

    public static String format(double value, String unit) {
        if (value == 0) {
            return "0" + symbol(unit);
        }
        String[] prefixes = {"f", "p", "n", "u", "m", "", "k", "M", "G", "T"};
        int index = (int) Math.floor(Math.log10(Math.abs(value)) / 3) + 5;
        index = Math.max(0, Math.min(prefixes.length - 1, index));
        double scaled = value / Math.pow(1000, index - 5);
        String number = String.format(Locale.ROOT, "%.4g", scaled);
        if (number.contains(".") && !number.contains("e")) {
            number = number.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return number + prefixes[index] + symbol(unit);
    }

    private static String symbol(String unit) {
        return "ohm".equals(unit) ? "Ω" : unit;
    }

    private static boolean unitMatches(String suffix, String unit) {
        if (unit.equalsIgnoreCase(suffix)) {
            return true;
        }
        return "ohm".equals(unit) && ("Ω".equals(suffix) || "R".equals(suffix));
    }

    private static double multiplier(String prefix) {
        if (prefix == null) {
            return 1;
        }
        return switch (prefix) {
            case "T" -> 1e12;
            case "G" -> 1e9;
            case "M", "meg", "MEG", "Meg" -> 1e6;
            case "k", "K" -> 1e3;
            case "m" -> 1e-3;
            case "u", "µ" -> 1e-6;
            case "n" -> 1e-9;
            case "p" -> 1e-12;
            case "f" -> 1e-15;
            default -> throw new DesignException("INVALID_DESIGN", "Unknown prefix " + prefix);
        };
    }
}
