package net.talaatharb.expanse.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.domain.Logic;

/**
 * Event-driven four-state (0, 1, X, Z) logic simulation with inertial propagation delays. Strong drivers are logic
 * outputs, logic sources, power rails (high at 1.5 V or more) and ground; resistors pass a weak level to undriven
 * nets (pull-ups and pull-downs); closed switches and inductors join nets; capacitors are open. Analog-only parts
 * such as transistors are ignored with a warning. Gates and flip-flops power up low and settle without delay at
 * t = 0 before events run.
 */
public final class LogicSimulator {
    public static final String ENGINE = "expanse-logic";
    public static final String VERSION = "0.1";
    public static final int MAX_EVENTS = 200_000;
    private static final double HIGH_RAIL_V = 1.5;
    private static final Set<String> IGNORED = Set.of("npn", "pnp", "nmos", "pmos");

    public record Transition(double timeS, String level) {
    }

    public record Result(String engine, String models, Map<String, String> levels,
                         Map<String, List<Transition>> series, List<String> warnings) {
    }

    public Result simulate(Project project, double stopTimeS, List<CircuitSimulator.SwitchEvent> switchEvents) {
        if (!Double.isFinite(stopTimeS) || !(stopTimeS > 0)) {
            throw new DesignException("SIM_INVALID_REQUEST", "Logic analysis needs stopTimeS > 0");
        }
        return new Run(project, stopTimeS, switchEvents).execute();
    }

    private record Gate(Component component, Logic.Part part, Logic.Unit unit, int[] inputs, int[] outputs,
                        int vcc, int gnd, double delay, char[] memory) {
    }

    private record Source(Component component, Logic.Unit unit, int node, char level, double frequency,
                          double duty) {
    }

    private record Link(Component component, int a, int b) {
    }

    private record Event(double time, long sequence, Runnable action) {
    }

    private static final class Run {
        final double stop;
        final List<String> warnings = new ArrayList<>();
        final List<String> netNames = new ArrayList<>();
        final List<Gate> gates = new ArrayList<>();
        final List<Source> sources = new ArrayList<>();
        final List<Link> resistors = new ArrayList<>();
        final List<Link> shorts = new ArrayList<>();
        final List<Link> switches = new ArrayList<>();
        final List<Boolean> switchClosed = new ArrayList<>();
        final List<Link> voltageSources = new ArrayList<>();
        final List<Double> sourceVoltages = new ArrayList<>();
        final Map<Integer, Character> fixed = new HashMap<>();
        final List<Integer> fixedConflicts = new ArrayList<>();
        final PriorityQueue<Event> queue = new PriorityQueue<>((a, b) -> a.time() != b.time()
                ? Double.compare(a.time(), b.time()) : Long.compare(a.sequence(), b.sequence()));
        final Map<Integer, Character> driven = new HashMap<>();
        char[][] outputs;
        char[][] scheduled;
        long[][] versions;
        char[] sourceLevels;
        char[] levels;
        int nodes;
        long sequence;
        long events;

        Run(Project project, double stop, List<CircuitSimulator.SwitchEvent> switchEvents) {
            this.stop = stop;
            Map<UUID, Integer> netNode = new HashMap<>();
            Map<UUID, Integer> pinNode = new HashMap<>();
            for (Net net : project.nets()) {
                String name = net.name();
                if (netNames.contains(name)) {
                    name = name + "#" + net.id().toString().substring(0, 8);
                }
                netNames.add(name);
                netNode.put(net.id(), nodes++);
                for (UUID pin : net.pinIds()) {
                    pinNode.put(pin, netNode.get(net.id()));
                }
            }
            for (Component c : project.components()) {
                Map<String, Integer> pins = new HashMap<>();
                for (var pin : c.pins()) {
                    Integer node = pinNode.get(pin.id());
                    pins.put(pin.number(), node == null ? nodes++ : node);
                }
                String kind = c.definition().kind();
                var part = Logic.part(kind);
                if (part.isPresent()) {
                    addLogic(c, part.get(), pins);
                    continue;
                }
                switch (kind) {
                    case "resistor" -> resistors.add(new Link(c, pins.get("1"), pins.get("2")));
                    case "inductor" -> shorts.add(new Link(c, pins.get("1"), pins.get("2")));
                    case "switch" -> {
                        switches.add(new Link(c, pins.get("1"), pins.get("2")));
                        switchClosed.add("closed".equals(c.properties().get("state")));
                    }
                    case "voltage-source" -> {
                        voltageSources.add(new Link(c, pins.get("1"), pins.get("2")));
                        sourceVoltages.add(c.value("voltage"));
                    }
                    case "power-rail" -> fix(pins.get("1"), c.value("voltage") >= HIGH_RAIL_V ? Logic.HIGH : Logic.LOW);
                    case "ground" -> fix(pins.get("1"), Logic.LOW);
                    case "capacitor" -> {
                    }
                    default -> {
                        if (IGNORED.contains(kind)) {
                            warnings.add(c.reference() + " (" + kind + ") has no logic model and is ignored.");
                        } else {
                            throw new DesignException("SIM_UNSUPPORTED", "No logic model for " + kind);
                        }
                    }
                }
            }
            for (var event : switchEvents) {
                int index = -1;
                for (int i = 0; i < switches.size(); i++) {
                    if (switches.get(i).component().reference().equals(event.reference())) {
                        index = i;
                    }
                }
                if (index < 0 || !Set.of("open", "closed").contains(event.state()) || !(event.timeS() >= 0)) {
                    throw new DesignException("SIM_INVALID_REQUEST",
                            "Switch events need an existing switch reference, time >= 0 and state open/closed");
                }
                int target = index;
                boolean closed = "closed".equals(event.state());
                if (event.timeS() == 0) {
                    switchClosed.set(target, closed);
                } else if (event.timeS() <= stop) {
                    schedule(event.timeS(), () -> switchClosed.set(target, closed));
                }
            }
        }

        private void fix(int node, char level) {
            Character existing = fixed.put(node, level);
            if (existing != null && existing != level) {
                fixedConflicts.add(node);
            }
        }

        private void addLogic(Component c, Logic.Part part, Map<String, Integer> pins) {
            if (part.source()) {
                Logic.Unit unit = part.units().getFirst();
                int node = pins.get(unit.outputs().getFirst());
                if (unit.function() == Logic.Function.CONSTANT) {
                    sources.add(new Source(c, unit, node, "high".equals(c.properties().get("level"))
                            ? Logic.HIGH : Logic.LOW, 0, 0));
                } else {
                    double frequency = c.value("frequency");
                    double duty = c.value("dutyCycle");
                    if (!(frequency > 0) || !(duty > 0 && duty < 1)) {
                        throw new DesignException("SIM_INVALID_CIRCUIT", c.reference()
                                + " needs frequency > 0 and 0 < dutyCycle < 1");
                    }
                    sources.add(new Source(c, unit, node, Logic.LOW, frequency, duty));
                }
                return;
            }
            double delay = c.value("propagationDelay");
            if (!(delay > 0)) {
                throw new DesignException("SIM_INVALID_CIRCUIT", c.reference() + " propagationDelay must be positive");
            }
            int vcc = part.powered() ? pins.get(part.vccPin()) : -1;
            int gnd = part.powered() ? pins.get(part.gndPin()) : -1;
            for (Logic.Unit unit : part.units()) {
                int[] in = unit.inputs().stream().mapToInt(pins::get).toArray();
                int[] out = unit.outputs().stream().mapToInt(pins::get).toArray();
                gates.add(new Gate(c, part, unit, in, out, vcc, gnd, delay,
                        new char[]{Logic.LOW, Logic.UNKNOWN}));
            }
        }

        private void schedule(double time, Runnable action) {
            if (++events > MAX_EVENTS) {
                throw new DesignException("SIM_TOO_LARGE", "Logic analysis exceeded " + MAX_EVENTS
                        + " events; shorten stopTimeS or slow the clocks");
            }
            queue.add(new Event(time, sequence++, action));
        }

        Result execute() {
            outputs = new char[gates.size()][];
            scheduled = new char[gates.size()][];
            versions = new long[gates.size()][];
            for (int i = 0; i < gates.size(); i++) {
                outputs[i] = new char[gates.get(i).outputs().length];
                Arrays.fill(outputs[i], Logic.LOW);
                scheduled[i] = outputs[i].clone();
                versions[i] = new long[outputs[i].length];
            }
            sourceLevels = new char[sources.size()];
            for (int i = 0; i < sources.size(); i++) {
                Source source = sources.get(i);
                sourceLevels[i] = source.unit().function() == Logic.Function.CLOCK
                        ? Logic.clock(0, source.frequency(), source.duty()) : source.level();
                if (source.unit().function() == Logic.Function.CLOCK) {
                    scheduleClock(i, 0);
                }
            }
            settle();
            propagate(0);
            Map<String, List<Transition>> series = new LinkedHashMap<>();
            for (int n = 0; n < netNames.size(); n++) {
                series.put(netNames.get(n), new ArrayList<>(List.of(new Transition(0, String.valueOf(levels[n])))));
            }
            while (!queue.isEmpty() && queue.peek().time() <= stop + 1e-15) {
                double t = queue.peek().time();
                while (!queue.isEmpty() && queue.peek().time() <= t) {
                    queue.poll().action().run();
                }
                char[] before = levels;
                resolve();
                for (int n = 0; n < netNames.size(); n++) {
                    if (before[n] != levels[n]) {
                        series.get(netNames.get(n)).add(new Transition(t, String.valueOf(levels[n])));
                    }
                }
                propagate(t);
            }
            Map<String, String> finalLevels = new LinkedHashMap<>();
            for (int n = 0; n < netNames.size(); n++) {
                finalLevels.put(netNames.get(n), String.valueOf(levels[n]));
            }
            for (int node : fixedConflicts) {
                if (node < netNames.size()) {
                    warnings.add("Net " + netNames.get(node) + " is tied to conflicting supply levels.");
                }
            }
            return new Result(ENGINE + " " + VERSION, "four-state 0/1/X/Z, inertial delays; gates power up low; "
                    + "rails >= " + HIGH_RAIL_V + " V are high; resistors pull weakly; closed switches and inductors "
                    + "join nets; capacitors open; transistors ignored", finalLevels, series, List.copyOf(warnings));
        }

        /** Schedules output changes, replacing pending ones (inertial delay). */
        private void propagate(double t) {
            for (int g = 0; g < gates.size(); g++) {
                char[] target = evaluate(g);
                for (int o = 0; o < target.length; o++) {
                    if (target[o] != scheduled[g][o]) {
                        scheduled[g][o] = target[o];
                        long version = ++versions[g][o];
                        int gate = g;
                        int output = o;
                        char level = target[o];
                        schedule(t + gates.get(g).delay(), () -> {
                            if (versions[gate][output] == version) {
                                outputs[gate][output] = level;
                            }
                        });
                    }
                }
            }
        }

        private void scheduleClock(int index, double after) {
            Source source = sources.get(index);
            double period = 1 / source.frequency();
            List<Double> edges = Logic.clockEdges(after, after + period, source.frequency(), source.duty());
            if (edges.isEmpty() || edges.getFirst() > stop) {
                return;
            }
            double edge = edges.getFirst();
            schedule(edge, () -> {
                sourceLevels[index] = Logic.clock(edge, source.frequency(), source.duty());
                scheduleClock(index, edge);
            });
        }

        /**
         * Zero-delay settling at t = 0 so combinational outputs start consistent with their inputs. Gates update one
         * at a time so feedback loops do not lock into a symmetric all-gates-toggle mode.
         */
        private void settle() {
            for (int pass = 0; pass < 2 * gates.size() + 4; pass++) {
                resolve();
                boolean changed = false;
                for (int g = 0; g < gates.size(); g++) {
                    char[] target = evaluate(g);
                    if (!Arrays.equals(target, outputs[g])) {
                        outputs[g] = target;
                        scheduled[g] = target.clone();
                        changed = true;
                        resolve();
                    }
                }
                if (!changed) {
                    resolve();
                    return;
                }
            }
            warnings.add("Logic did not settle at t = 0 (combinational feedback); starting from the last pass.");
            resolve();
        }

        private char[] evaluate(int index) {
            Gate gate = gates.get(index);
            char[] inputs = new char[gate.inputs().length];
            for (int i = 0; i < inputs.length; i++) {
                inputs[i] = levels[gate.inputs()[i]];
            }
            char[] memory = gate.memory().clone();
            char[] result = Logic.evaluate(gate.unit(), inputs, memory);
            if (gate.part().powered() && (levels[gate.vcc()] != Logic.HIGH || levels[gate.gnd()] != Logic.LOW)) {
                Arrays.fill(result, Logic.UNKNOWN);
            }
            System.arraycopy(memory, 0, gate.memory(), 0, memory.length);
            return result;
        }

        /** Resolves every node: strong drivers first, then weak levels through resistors. */
        private void resolve() {
            int[] parent = new int[nodes];
            for (int i = 0; i < nodes; i++) {
                parent[i] = i;
            }
            shorts.forEach(l -> union(parent, l.a(), l.b()));
            for (int i = 0; i < switches.size(); i++) {
                if (switchClosed.get(i)) {
                    union(parent, switches.get(i).a(), switches.get(i).b());
                }
            }
            char[] strong = new char[nodes];
            Arrays.fill(strong, Logic.FLOATING);
            fixed.forEach((node, level) -> drive(strong, find(parent, node), level));
            for (int node : fixedConflicts) {
                strong[find(parent, node)] = Logic.UNKNOWN;
            }
            for (int i = 0; i < sources.size(); i++) {
                drive(strong, find(parent, sources.get(i).node()), sourceLevels[i]);
            }
            for (int g = 0; g < gates.size(); g++) {
                for (int o = 0; o < gates.get(g).outputs().length; o++) {
                    drive(strong, find(parent, gates.get(g).outputs()[o]), outputs[g][o]);
                }
            }
            for (int pass = 0; pass < voltageSources.size(); pass++) {
                for (int i = 0; i < voltageSources.size(); i++) {
                    Link s = voltageSources.get(i);
                    int plus = find(parent, s.a());
                    int minus = find(parent, s.b());
                    if (strong[minus] == Logic.LOW && strong[plus] == Logic.FLOATING) {
                        strong[plus] = sourceVoltages.get(i) >= HIGH_RAIL_V ? Logic.HIGH : Logic.LOW;
                    }
                }
            }
            char[] weak = strong.clone();
            for (int pass = 0; pass <= resistors.size(); pass++) {
                boolean changed = false;
                char[] next = weak.clone();
                for (int root = 0; root < nodes; root++) {
                    if (find(parent, root) != root || strong[root] != Logic.FLOATING) {
                        continue;
                    }
                    char value = Logic.FLOATING;
                    for (Link r : resistors) {
                        int a = find(parent, r.a());
                        int b = find(parent, r.b());
                        int other = a == root ? b : b == root ? a : -1;
                        if (other < 0 || other == root || weak[other] == Logic.FLOATING) {
                            continue;
                        }
                        value = value == Logic.FLOATING || value == weak[other] ? weak[other] : Logic.UNKNOWN;
                    }
                    if (value != next[root]) {
                        next[root] = value;
                        changed = true;
                    }
                }
                weak = next;
                if (!changed) {
                    break;
                }
            }
            char[] result = new char[nodes];
            for (int i = 0; i < nodes; i++) {
                result[i] = weak[find(parent, i)];
            }
            levels = result;
        }

        private static void drive(char[] strong, int root, char level) {
            if (level == Logic.FLOATING) {
                return;
            }
            strong[root] = strong[root] == Logic.FLOATING || strong[root] == level ? level : Logic.UNKNOWN;
        }

        private static int find(int[] parent, int node) {
            while (parent[node] != node) {
                parent[node] = parent[parent[node]];
                node = parent[node];
            }
            return node;
        }

        private static void union(int[] parent, int a, int b) {
            parent[find(parent, a)] = find(parent, b);
        }
    }
}
