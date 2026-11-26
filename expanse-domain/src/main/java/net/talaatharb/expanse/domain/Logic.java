package net.talaatharb.expanse.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Digital behaviour of the built-in logic parts, shared by the logic simulator, the analog behavioural models and
 * the electrical rule checks. Levels are the characters {@code 0}, {@code 1}, {@code X} (unknown) and {@code Z}
 * (undriven). Pins are referenced by definition pin number.
 */
public final class Logic {
    public static final char LOW = '0';
    public static final char HIGH = '1';
    public static final char UNKNOWN = 'X';
    public static final char FLOATING = 'Z';

    public enum Function { AND, OR, NAND, NOR, XOR, NOT, DFF, CONSTANT, CLOCK }

    /**
     * One gate or flip-flop inside a part. A D flip-flop takes inputs {@code D, CLK, PRE, CLR} (active-low preset
     * and clear) and drives {@code Q, QN}.
     */
    public record Unit(Function function, List<String> inputs, List<String> outputs) {
        public Unit {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    /** Supply pins are {@code null} for virtual parts, which are implicitly powered at their highVoltage. */
    public record Part(String kind, List<Unit> units, String vccPin, String gndPin) {
        public Part {
            units = List.copyOf(units);
        }

        public boolean powered() {
            return vccPin != null;
        }

        public boolean source() {
            return units.stream().anyMatch(u -> u.function() == Function.CONSTANT || u.function() == Function.CLOCK);
        }

        public List<String> outputPins() {
            return units.stream().flatMap(u -> u.outputs().stream()).toList();
        }

        public List<String> inputPins() {
            return units.stream().flatMap(u -> u.inputs().stream()).toList();
        }
    }

    private static final Map<String, Part> PARTS;

    static {
        var parts = new java.util.LinkedHashMap<String, Part>();
        parts.put("logic-input", new Part("logic-input",
                List.of(new Unit(Function.CONSTANT, List.of(), List.of("1"))), null, null));
        parts.put("logic-clock", new Part("logic-clock",
                List.of(new Unit(Function.CLOCK, List.of(), List.of("1"))), null, null));
        for (var gate : List.of(Function.AND, Function.OR, Function.NAND, Function.NOR, Function.XOR)) {
            String kind = "logic-" + gate.name().toLowerCase(java.util.Locale.ROOT);
            parts.put(kind, new Part(kind, List.of(new Unit(gate, List.of("1", "2"), List.of("3"))), null, null));
        }
        parts.put("logic-not", new Part("logic-not", List.of(new Unit(Function.NOT, List.of("1"), List.of("2"))),
                null, null));
        parts.put("logic-74hc00", quad("logic-74hc00", Function.NAND));
        parts.put("logic-74hc08", quad("logic-74hc08", Function.AND));
        parts.put("logic-74hc32", quad("logic-74hc32", Function.OR));
        parts.put("logic-74hc86", quad("logic-74hc86", Function.XOR));
        parts.put("logic-74hc04", new Part("logic-74hc04", List.of(
                inverter("1", "2"), inverter("3", "4"), inverter("5", "6"),
                inverter("9", "8"), inverter("11", "10"), inverter("13", "12")), "14", "7"));
        parts.put("logic-74hc74", new Part("logic-74hc74", List.of(
                new Unit(Function.DFF, List.of("2", "3", "4", "1"), List.of("5", "6")),
                new Unit(Function.DFF, List.of("12", "11", "10", "13"), List.of("9", "8"))), "14", "7"));
        PARTS = java.util.Collections.unmodifiableMap(parts);
    }

    private Logic() {
    }

    /** Standard 74-series quad 2-input pinout: 1A 1B 1Y 2A 2B 2Y GND 3Y 3A 3B 4Y 4A 4B VCC. */
    private static Part quad(String kind, Function function) {
        return new Part(kind, List.of(
                new Unit(function, List.of("1", "2"), List.of("3")),
                new Unit(function, List.of("4", "5"), List.of("6")),
                new Unit(function, List.of("9", "10"), List.of("8")),
                new Unit(function, List.of("12", "13"), List.of("11"))), "14", "7");
    }

    private static Unit inverter(String input, String output) {
        return new Unit(Function.NOT, List.of(input), List.of(output));
    }

    public static Optional<Part> part(String kind) {
        return Optional.ofNullable(PARTS.get(kind));
    }

    public static boolean isLogic(String kind) {
        return PARTS.containsKey(kind);
    }

    public static List<String> kinds() {
        return List.copyOf(PARTS.keySet());
    }

    /** Inputs read as Z (undriven) are treated as unknown. */
    public static char read(char level) {
        return level == LOW || level == HIGH ? level : UNKNOWN;
    }

    public static char not(char level) {
        return switch (read(level)) {
            case LOW -> HIGH;
            case HIGH -> LOW;
            default -> UNKNOWN;
        };
    }

    /**
     * Evaluates a unit. {@code memory} holds per-unit state for flip-flops as {@code [Q, previous CLK]} and is
     * updated in place; flip-flops capture D only on a clean 0 → 1 clock edge.
     */
    public static char[] evaluate(Unit unit, char[] inputs, char[] memory) {
        char[] in = new char[inputs.length];
        for (int i = 0; i < in.length; i++) {
            in[i] = read(inputs[i]);
        }
        return switch (unit.function()) {
            case AND -> new char[]{and(in)};
            case NAND -> new char[]{not(and(in))};
            case OR -> new char[]{or(in)};
            case NOR -> new char[]{not(or(in))};
            case XOR -> new char[]{xor(in)};
            case NOT -> new char[]{not(in[0])};
            case DFF -> flipFlop(in, memory);
            case CONSTANT, CLOCK -> throw new IllegalArgumentException("Sources are driven by time, not inputs");
        };
    }

    private static char and(char[] in) {
        boolean unknown = false;
        for (char c : in) {
            if (c == LOW) {
                return LOW;
            }
            unknown |= c != HIGH;
        }
        return unknown ? UNKNOWN : HIGH;
    }

    private static char or(char[] in) {
        boolean unknown = false;
        for (char c : in) {
            if (c == HIGH) {
                return HIGH;
            }
            unknown |= c != LOW;
        }
        return unknown ? UNKNOWN : LOW;
    }

    private static char xor(char[] in) {
        int ones = 0;
        for (char c : in) {
            if (c == UNKNOWN) {
                return UNKNOWN;
            }
            ones += c == HIGH ? 1 : 0;
        }
        return ones % 2 == 1 ? HIGH : LOW;
    }

    private static char[] flipFlop(char[] in, char[] memory) {
        char d = in[0];
        char clk = in[1];
        char preset = in[2];
        char clear = in[3];
        char previousClock = memory[1];
        memory[1] = clk;
        if (preset == LOW && clear == LOW) {
            return new char[]{HIGH, HIGH};
        }
        if (preset == LOW) {
            memory[0] = HIGH;
        } else if (clear == LOW) {
            memory[0] = LOW;
        } else if (preset != HIGH || clear != HIGH) {
            memory[0] = UNKNOWN;
        } else if (previousClock == LOW && clk == HIGH) {
            memory[0] = d;
        }
        return new char[]{memory[0], not(memory[0])};
    }

    /** Clock level at time t: low for the first {@code (1 - duty)} of each period, then high. */
    public static char clock(double t, double frequencyHz, double duty) {
        double phase = t * frequencyHz - Math.floor(t * frequencyHz + 1e-9);
        if (phase < 0) {
            phase = 0;
        }
        return phase + 1e-9 >= 1 - duty ? HIGH : LOW;
    }

    /** Times of clock transitions in (from, to]. */
    public static List<Double> clockEdges(double from, double to, double frequencyHz, double duty) {
        List<Double> edges = new ArrayList<>();
        double period = 1 / frequencyHz;
        long first = (long) Math.floor(from * frequencyHz) - 1;
        for (long k = Math.max(0, first); ; k++) {
            double rise = (k + 1 - duty) * period;
            double fall = (k + 1) * period;
            if (rise > to + 1e-15) {
                break;
            }
            if (rise > from + 1e-15) {
                edges.add(rise);
            }
            if (fall > from + 1e-15 && fall <= to + 1e-15) {
                edges.add(fall);
            }
        }
        return edges;
    }
}
