package net.talaatharb.expanse.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.domain.Logic;

/**
 * A small modified-nodal-analysis simulator for the built-in parts: DC operating point and fixed-step
 * backward-Euler transient analysis. Transistors use simplified large-signal models (Ebers-Moll transport
 * for BJTs, Shichman-Hodges level 1 for MOSFETs) without junction capacitances.
 * Pin currents are reported as current flowing into the component at that pin.
 */
public final class CircuitSimulator {
    public static final String ENGINE = "expanse-mna";
    public static final String VERSION = "0.1";
    public static final int MAX_UNKNOWNS = 250;
    public static final int MAX_STEPS = 20_000;
    public static final int MAX_POINTS = 2_000;
    private static final double GMIN = 1e-12;
    static final double VT = 0.025852;
    private static final double OFF_CONDUCTANCE = 1e-12;
    /** LEDs are specified by their forward voltage at this current. */
    static final double LED_REFERENCE_CURRENT = 0.02;
    private static final double OPAMP_MIN_SLOPE = 1e-3;
    /** Output resistance of behavioural logic outputs. */
    static final double LOGIC_OUTPUT_OHMS = 50;
    private static final int LOGIC_SETTLE_PASSES = 50;

    public record SwitchEvent(String reference, double timeS, String state) {
    }

    public record Options(String analysis, double stopTimeS, double timeStepS, boolean zeroInitialConditions,
                          List<SwitchEvent> switchEvents) {
        public Options {
            switchEvents = List.copyOf(switchEvents);
        }

        public static Options operatingPoint() {
            return new Options("op", 0, 0, false, List.of());
        }
    }

    public record Result(String analysis, String engine, String models, Map<String, Double> nodeVoltages,
                         Map<String, Map<String, Double>> pinCurrents, Map<String, Double> componentPower,
                         List<Double> time, Map<String, List<Double>> voltageSeries,
                         Map<String, List<Double>> currentSeries, List<String> warnings) {
    }

    public Result simulate(Project project, Options options) {
        Circuit circuit = new Circuit(project);
        for (SwitchEvent event : options.switchEvents()) {
            if (circuit.switches.stream().noneMatch(s -> s.component().reference().equals(event.reference()))
                    || !Set.of("open", "closed").contains(event.state()) || !(event.timeS() >= 0)) {
                throw new DesignException("SIM_INVALID_REQUEST",
                        "Switch events need an existing switch reference, time >= 0 and state open/closed");
            }
        }
        if ("op".equals(options.analysis())) {
            boolean[] states = switchStates(circuit, options, 0);
            double[] x = circuit.settledOperatingPoint(states);
            var pins = circuit.pinCurrents(x, null, 0, states);
            return new Result("op", ENGINE + " " + VERSION, circuit.models(), circuit.voltages(x), pins,
                    power(circuit, x, pins), List.of(), Map.of(), Map.of(), List.copyOf(circuit.warnings));
        }
        if (!"transient".equals(options.analysis())) {
            throw new DesignException("SIM_INVALID_REQUEST", "Analysis must be op, transient or logic");
        }
        double h = options.timeStepS();
        double stop = options.stopTimeS();
        if (!Double.isFinite(h) || !Double.isFinite(stop) || !(h > 0) || !(stop > 0) || h > stop) {
            throw new DesignException("SIM_INVALID_REQUEST", "Use 0 < timeStep <= stopTime");
        }
        long steps = (long) Math.ceil(stop / h - 1e-9);
        if (steps > MAX_STEPS) {
            throw new DesignException("SIM_TOO_LARGE", "Transient needs " + steps + " steps; the limit is " + MAX_STEPS);
        }
        boolean[] initialStates = switchStates(circuit, options, 0);
        double[] x;
        if (options.zeroInitialConditions()) {
            x = new double[circuit.size()];
            circuit.updateLogic(x, 0);
        } else {
            x = circuit.settledOperatingPoint(initialStates);
        }
        int stride = (int) Math.max(1, Math.ceil(steps / (double) MAX_POINTS));
        List<Double> time = new ArrayList<>();
        Map<String, List<Double>> voltages = new LinkedHashMap<>();
        Map<String, List<Double>> currents = new LinkedHashMap<>();
        Map<String, Map<String, Double>> lastPins = circuit.pinCurrents(x, null, 0, initialStates);
        record(time, voltages, currents, 0, circuit.voltages(x), lastPins);
        for (long step = 1; step <= steps; step++) {
            double t = Math.min(stop, step * h);
            boolean[] states = switchStates(circuit, options, t);
            double[] previous = x;
            circuit.updateLogic(previous, t);
            x = circuit.solve(previous, previous, h, states, 1.0);
            lastPins = circuit.pinCurrents(x, previous, h, states);
            if (step % stride == 0 || step == steps) {
                record(time, voltages, currents, t, circuit.voltages(x), lastPins);
            }
        }
        return new Result("transient", ENGINE + " " + VERSION, circuit.models(), circuit.voltages(x), lastPins,
                power(circuit, x, lastPins), time, voltages, currents, List.copyOf(circuit.warnings));
    }

    private static void record(List<Double> time, Map<String, List<Double>> voltages,
                               Map<String, List<Double>> currents, double t, Map<String, Double> v,
                               Map<String, Map<String, Double>> pins) {
        time.add(t);
        v.forEach((node, value) -> voltages.computeIfAbsent(node, k -> new ArrayList<>()).add(value));
        pins.forEach((ref, map) -> map.forEach((pin, value) ->
                currents.computeIfAbsent(ref + "." + pin, k -> new ArrayList<>()).add(value)));
    }

    private static Map<String, Double> power(Circuit circuit, double[] x, Map<String, Map<String, Double>> pins) {
        Map<String, Double> power = new LinkedHashMap<>();
        pins.forEach((ref, map) -> {
            double total = 0;
            for (var entry : map.entrySet()) {
                total += circuit.pinVoltage(x, ref, entry.getKey()) * entry.getValue();
            }
            power.put(ref, Circuit.clean(total));
        });
        return power;
    }

    private static boolean[] switchStates(Circuit circuit, Options options, double t) {
        boolean[] states = new boolean[circuit.switches.size()];
        for (int i = 0; i < states.length; i++) {
            Element.Switch sw = circuit.switches.get(i);
            boolean closed = "closed".equals(sw.component().properties().get("state"));
            double latest = Double.NEGATIVE_INFINITY;
            for (SwitchEvent event : options.switchEvents()) {
                if (event.reference().equals(sw.component().reference()) && event.timeS() <= t
                        && event.timeS() >= latest) {
                    latest = event.timeS();
                    closed = "closed".equals(event.state());
                }
            }
            states[i] = closed;
        }
        return states;
    }

    sealed interface Element {
        Component component();

        record Resistor(Component component, int a, int b, double g) implements Element {
        }

        record Switch(Component component, int a, int b, double gOn, int index) implements Element {
        }

        record Capacitor(Component component, int a, int b, double c) implements Element {
        }

        record Inductor(Component component, int a, int b, double l, int branch) implements Element {
        }

        record Source(Component component, int plus, int minus, double v, int branch) implements Element {
        }

        record Bjt(Component component, int c, int b, int e, double polarity, double beta, double is)
                implements Element {
        }

        record Mos(Component component, int d, int g, int s, double polarity, double vth, double k)
                implements Element {
        }

        /**
         * Shockley junction written relative to a reference point so steep LEDs stay within the exponent limit:
         * {@code i = iRef * (exp((v - vRef) / nVt) - exp(-vRef / nVt))}. A plain diode has vRef = 0 and iRef = Is.
         */
        record Diode(Component component, int a, int k, double iRef, double vRef, double nVt) implements Element {
        }

        /**
         * Op-amp: output {@code rOut} behind {@code mid + half * tanh((gain * (in+ - in-) - mid) / half)}, a smooth
         * clamp of the open-loop gain to the supply rails. Inputs draw no current; output current is drawn from V+
         * when sourcing and from V- when sinking.
         */
        record OpAmp(Component component, int inP, int inN, int out, int vPlus, int vMinus, double gain,
                     double rOut) implements Element {
        }

        /**
         * Behavioural logic part: each output is a {@link #LOGIC_OUTPUT_OHMS} switch to the supply (VCC or, for
         * virtual parts, an implicit highVoltage) or to GND; inputs draw no current and switch at half supply.
         */
        record LogicPart(Component component, Logic.Part part, int index, Map<String, Integer> pins, int vcc,
                         int gnd, double vHigh) implements Element {
        }
    }

    static final class Circuit {
        final List<Element> elements = new ArrayList<>();
        final List<Element.Switch> switches = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        final Map<String, Map<String, Integer>> componentPins = new HashMap<>();
        final List<String> groundNames = new ArrayList<>();
        final List<Element.LogicPart> logicParts = new ArrayList<>();
        char[][] logicLevels = new char[0][];
        char[][][] logicMemory = new char[0][][];
        int nodes;
        int branches;
        boolean nonlinear;

        Circuit(Project project) {
            Map<UUID, Net> netOfPin = new HashMap<>();
            project.nets().forEach(n -> n.pinIds().forEach(p -> netOfPin.put(p, n)));
            boolean referenced = project.components().stream()
                    .anyMatch(c -> Set.of("ground", "power-rail").contains(c.definition().kind())
                            || Logic.isLogic(c.definition().kind()));
            if (!referenced) {
                throw new DesignException("SIM_NO_GROUND", "Add a ground or power-rail symbol as the 0 V reference");
            }
            Set<UUID> groundNets = new HashSet<>();
            for (Component c : project.components()) {
                Net net = netOfPin.get(c.pins().getFirst().id());
                if (c.definition().kind().equals("ground") && net != null) {
                    groundNets.add(net.id());
                }
            }
            Map<UUID, Integer> netNode = new HashMap<>();
            for (Net net : project.nets()) {
                if (groundNets.contains(net.id())) {
                    netNode.put(net.id(), -1);
                    groundNames.add(net.name());
                    continue;
                }
                String name = net.name();
                if (nodeIndex.containsKey(name) || groundNames.contains(name)) {
                    name = name + "#" + net.id().toString().substring(0, 8);
                }
                nodeIndex.put(name, nodes);
                netNode.put(net.id(), nodes++);
            }
            for (Component c : project.components()) {
                Map<String, Integer> map = new LinkedHashMap<>();
                for (var pin : c.pins()) {
                    Net net = netOfPin.get(pin.id());
                    int node;
                    if (net != null) {
                        node = netNode.get(net.id());
                    } else if (c.definition().kind().equals("ground")) {
                        node = -1;
                    } else {
                        String name = c.reference() + "." + pin.number();
                        nodeIndex.put(name, nodes);
                        node = nodes++;
                        boolean required = c.definition().pins().stream()
                                .anyMatch(d -> d.number().equals(pin.number()) && d.required());
                        if (required) {
                            warnings.add(name + " is unconnected; a 1 pS leakage holds it near 0 V.");
                        }
                    }
                    map.put(pin.number(), node);
                }
                componentPins.put(c.reference(), map);
            }
            Map<Integer, Double> railLevels = new HashMap<>();
            List<Element> pending = new ArrayList<>();
            for (Component c : project.components()) {
                Map<String, Integer> p = componentPins.get(c.reference());
                switch (c.definition().kind()) {
                    case "resistor" -> elements.add(new Element.Resistor(c, p.get("1"), p.get("2"),
                            1 / positive(c, "resistance")));
                    case "capacitor" -> elements.add(new Element.Capacitor(c, p.get("1"), p.get("2"),
                            positive(c, "capacitance")));
                    case "inductor" -> pending.add(new Element.Inductor(c, p.get("1"), p.get("2"),
                            positive(c, "inductance"), -1));
                    case "voltage-source" -> pending.add(new Element.Source(c, p.get("1"), p.get("2"),
                            c.value("voltage"), -1));
                    case "power-rail" -> {
                        int node = p.get("1");
                        double level = c.value("voltage");
                        Double existing = railLevels.get(node);
                        if (node == -1 && level != 0) {
                            throw new DesignException("SIM_INVALID_CIRCUIT", c.reference() + " ties ground to "
                                    + level + " V");
                        }
                        if (existing != null && existing != level) {
                            throw new DesignException("SIM_INVALID_CIRCUIT", c.reference()
                                    + " conflicts with another power rail on the same net");
                        }
                        if (existing == null && node != -1) {
                            railLevels.put(node, level);
                            pending.add(new Element.Source(c, node, -1, level, -1));
                        }
                    }
                    case "switch" -> {
                        var sw = new Element.Switch(c, p.get("1"), p.get("2"), 1 / positive(c, "onResistance"),
                                switches.size());
                        switches.add(sw);
                        elements.add(sw);
                    }
                    case "npn", "pnp" -> {
                        nonlinear = true;
                        elements.add(new Element.Bjt(c, p.get("3"), p.get("2"), p.get("1"),
                                c.definition().kind().equals("npn") ? 1 : -1, positive(c, "beta"),
                                positive(c, "saturationCurrent")));
                    }
                    case "nmos", "pmos" -> {
                        nonlinear = true;
                        elements.add(new Element.Mos(c, p.get("3"), p.get("2"), p.get("1"),
                                c.definition().kind().equals("nmos") ? 1 : -1, c.value("thresholdVoltage"),
                                positive(c, "transconductance")));
                    }
                    case "diode" -> {
                        nonlinear = true;
                        elements.add(new Element.Diode(c, p.get("1"), p.get("2"), positive(c, "saturationCurrent"),
                                0, positive(c, "emissionCoefficient") * VT));
                    }
                    case "led" -> {
                        nonlinear = true;
                        elements.add(new Element.Diode(c, p.get("1"), p.get("2"), LED_REFERENCE_CURRENT,
                                positive(c, "forwardVoltage"), positive(c, "emissionCoefficient") * VT));
                    }
                    case "opamp" -> {
                        nonlinear = true;
                        elements.add(new Element.OpAmp(c, p.get("3"), p.get("2"), p.get("6"), p.get("7"), p.get("4"),
                                positive(c, "openLoopGain"), positive(c, "outputResistance")));
                    }
                    case "ground" -> {
                    }
                    case Design.GENERIC_KIND -> warnings.add(c.reference() + " (" + c.definition().name()
                            + ") is a custom part without a simulation model; it is left open.");
                    default -> {
                        var part = Logic.part(c.definition().kind()).orElseThrow(() -> new DesignException(
                                "SIM_UNSUPPORTED", "No simulation model for " + c.definition().kind()));
                        double vHigh = part.powered() ? 0 : c.value("highVoltage");
                        if (part.units().getFirst().function() == Logic.Function.CLOCK) {
                            double duty = c.value("dutyCycle");
                            if (!(c.value("frequency") > 0) || !(duty > 0 && duty < 1)) {
                                throw new DesignException("SIM_INVALID_CIRCUIT", c.reference()
                                        + " needs frequency > 0 and 0 < dutyCycle < 1");
                            }
                        }
                        var element = new Element.LogicPart(c, part, logicParts.size(), p,
                                part.powered() ? p.get(part.vccPin()) : -1,
                                part.powered() ? p.get(part.gndPin()) : -1, vHigh);
                        logicParts.add(element);
                        elements.add(element);
                    }
                }
            }
            for (Element element : pending) {
                int branch = nodes + branches++;
                elements.add(switch (element) {
                    case Element.Inductor l -> new Element.Inductor(l.component(), l.a(), l.b(), l.l(), branch);
                    case Element.Source s -> new Element.Source(s.component(), s.plus(), s.minus(), s.v(), branch);
                    default -> throw new IllegalStateException("Unexpected branch element");
                });
            }
            logicLevels = new char[logicParts.size()][];
            logicMemory = new char[logicParts.size()][][];
            for (var part : logicParts) {
                logicLevels[part.index()] = new char[part.part().outputPins().size()];
                java.util.Arrays.fill(logicLevels[part.index()], Logic.LOW);
                logicMemory[part.index()] = new char[part.part().units().size()][];
                for (int u = 0; u < part.part().units().size(); u++) {
                    logicMemory[part.index()][u] = new char[]{Logic.LOW, Logic.UNKNOWN};
                }
            }
            if (size() > MAX_UNKNOWNS) {
                throw new DesignException("SIM_TOO_LARGE", "Circuit has " + size() + " unknowns; the limit is "
                        + MAX_UNKNOWNS);
            }
        }

        int size() {
            return nodes + branches;
        }

        String models() {
            return "R, C, L and DC sources ideal; switch = onResistance or 1 pS; BJT Ebers-Moll transport "
                    + "(betaR=1, no capacitances); MOSFET Shichman-Hodges level 1 (lambda=0); "
                    + "diode/LED Shockley (no series resistance, breakdown or capacitance; LED Is from forwardVoltage "
                    + "at 20 mA); op-amp tanh-limited open-loop gain to the supply rails behind outputResistance; "
                    + "logic outputs " + (int) LOGIC_OUTPUT_OHMS + " ohm to supply/GND, inputs switch at half "
                    + "supply and are sampled once per time step; 1 pS node leakage; backward-Euler integration";
        }

        /** DC operating point with logic outputs iterated until they agree with their inputs. */
        double[] settledOperatingPoint(boolean[] states) {
            if (logicParts.isEmpty()) {
                return operatingPoint(states);
            }
            double[] x = new double[size()];
            updateLogic(x, 0);
            for (int pass = 0; pass < LOGIC_SETTLE_PASSES; pass++) {
                x = operatingPoint(states);
                if (!updateLogic(x, 0)) {
                    return x;
                }
            }
            warnings.add("Logic outputs did not settle at the operating point (feedback oscillation).");
            return x;
        }

        /** Re-evaluates logic outputs from node voltages at time t; returns true when any output changed. */
        boolean updateLogic(double[] x, double t) {
            boolean changed = false;
            for (var element : logicParts) {
                Component c = element.component();
                char[] next = new char[logicLevels[element.index()].length];
                Logic.Unit first = element.part().units().getFirst();
                if (first.function() == Logic.Function.CONSTANT) {
                    next[0] = "high".equals(c.properties().get("level")) ? Logic.HIGH : Logic.LOW;
                } else if (first.function() == Logic.Function.CLOCK) {
                    next[0] = Logic.clock(t, c.value("frequency"), c.value("dutyCycle"));
                } else {
                    double low = v(x, element.gnd());
                    double supply = element.part().powered() ? v(x, element.vcc()) - low : element.vHigh();
                    int o = 0;
                    for (int u = 0; u < element.part().units().size(); u++) {
                        Logic.Unit unit = element.part().units().get(u);
                        char[] inputs = new char[unit.inputs().size()];
                        for (int i = 0; i < inputs.length; i++) {
                            inputs[i] = v(x, element.pins().get(unit.inputs().get(i))) - low > supply / 2
                                    ? Logic.HIGH : Logic.LOW;
                        }
                        char[] out = Logic.evaluate(unit, inputs, logicMemory[element.index()][u]);
                        for (char level : out) {
                            next[o++] = supply < 1 ? Logic.FLOATING : level;
                        }
                    }
                }
                if (!java.util.Arrays.equals(next, logicLevels[element.index()])) {
                    logicLevels[element.index()] = next;
                    changed = true;
                }
            }
            return changed;
        }

        double[] operatingPoint(boolean[] states) {
            double[] guess = new double[size()];
            try {
                return solve(guess, null, 0, states, 1.0);
            } catch (DesignException e) {
                if (!nonlinear || !"SIM_NO_CONVERGENCE".equals(e.code())) {
                    throw e;
                }
            }
            warnings.add("Operating point required source stepping.");
            guess = new double[size()];
            for (int step = 1; step <= 20; step++) {
                guess = solve(guess, null, 0, states, step / 20.0);
            }
            return guess;
        }

        double[] solve(double[] start, double[] previous, double h, boolean[] states, double scale) {
            double[] x = start.clone();
            int limit = nonlinear ? 300 : 1;
            for (int iteration = 0; iteration < limit; iteration++) {
                int n = size();
                double[][] a = new double[n][n];
                double[] rhs = new double[n];
                for (int i = 0; i < nodes; i++) {
                    a[i][i] += GMIN;
                }
                for (Element element : elements) {
                    stamp(element, a, rhs, x, previous, h, states, scale);
                }
                double[] next = DenseSolver.solve(a, rhs);
                if (!nonlinear) {
                    return next;
                }
                boolean converged = true;
                for (int i = 0; i < n; i++) {
                    double delta = next[i] - x[i];
                    if (i < nodes) {
                        delta = Math.max(-1, Math.min(1, delta));
                    }
                    double tolerance = (i < nodes ? 1e-6 : 1e-9) + 1e-6 * Math.abs(next[i]);
                    converged &= Math.abs(next[i] - x[i]) < tolerance;
                    x[i] += delta;
                }
                if (converged) {
                    return x;
                }
            }
            throw new DesignException("SIM_NO_CONVERGENCE", "Newton iteration did not converge; check transistor bias");
        }

        private void stamp(Element element, double[][] a, double[] rhs, double[] x, double[] previous, double h,
                           boolean[] states, double scale) {
            switch (element) {
                case Element.Resistor r -> conductance(a, r.a(), r.b(), r.g());
                case Element.Switch s -> conductance(a, s.a(), s.b(), states[s.index()] ? s.gOn() : OFF_CONDUCTANCE);
                case Element.Capacitor c -> {
                    if (previous != null && h > 0) {
                        double g = c.c() / h;
                        double vPrevious = v(previous, c.a()) - v(previous, c.b());
                        conductance(a, c.a(), c.b(), g);
                        inject(rhs, c.a(), g * vPrevious);
                        inject(rhs, c.b(), -g * vPrevious);
                    }
                }
                case Element.Inductor l -> {
                    branch(a, l.a(), l.b(), l.branch());
                    if (previous != null && h > 0) {
                        a[l.branch()][l.branch()] -= l.l() / h;
                        rhs[l.branch()] = -l.l() / h * previous[l.branch()];
                    }
                }
                case Element.Source s -> {
                    branch(a, s.plus(), s.minus(), s.branch());
                    rhs[s.branch()] = s.v() * scale;
                }
                case Element.Bjt q -> {
                    conductance(a, q.b(), q.e(), GMIN);
                    conductance(a, q.b(), q.c(), GMIN);
                    nonlinear(a, rhs, x, new int[]{q.c(), q.b(), q.e()}, voltages -> bjt(q, voltages));
                }
                case Element.Mos m -> {
                    conductance(a, m.d(), m.s(), GMIN);
                    nonlinear(a, rhs, x, new int[]{m.d(), m.g(), m.s()}, voltages -> mos(m, voltages));
                }
                case Element.Diode d -> {
                    conductance(a, d.a(), d.k(), GMIN);
                    nonlinear(a, rhs, x, new int[]{d.a(), d.k()}, voltages -> diode(d, voltages));
                }
                case Element.OpAmp o -> nonlinear(a, rhs, x, new int[]{o.inP(), o.inN(), o.out(), o.vPlus(),
                        o.vMinus()}, voltages -> opAmp(o, voltages), (v, i, jacobian) -> opAmpSlope(o, v, i, jacobian));
                case Element.LogicPart l -> {
                    double g = 1 / LOGIC_OUTPUT_OHMS;
                    List<String> outputs = l.part().outputPins();
                    for (int o = 0; o < outputs.size(); o++) {
                        int out = l.pins().get(outputs.get(o));
                        char level = logicLevels[l.index()][o];
                        if (level == Logic.HIGH && l.part().powered()) {
                            conductance(a, out, l.vcc(), g);
                        } else if (level == Logic.HIGH) {
                            conductance(a, out, -1, g);
                            inject(rhs, out, g * l.vHigh() * scale);
                        } else if (level == Logic.LOW) {
                            conductance(a, out, l.gnd(), g);
                        }
                    }
                }
            }
        }

        private static void nonlinear(double[][] a, double[] rhs, double[] x, int[] terminals,
                                      Function<double[], double[]> model) {
            nonlinear(a, rhs, x, terminals, model, null);
        }

        /**
         * Stamps the Newton linearisation of {@code model} around {@code x} using a finite-difference Jacobian,
         * optionally adjusted by {@code tweak}. The residual is exact, so a tweaked Jacobian only changes the
         * convergence path, never the converged solution.
         */
        private static void nonlinear(double[][] a, double[] rhs, double[] x, int[] terminals,
                                      Function<double[], double[]> model, JacobianTweak tweak) {
            double[] v0 = new double[terminals.length];
            for (int i = 0; i < terminals.length; i++) {
                v0[i] = v(x, terminals[i]);
            }
            double[] i0 = model.apply(v0);
            double[][] jacobian = new double[terminals.length][terminals.length];
            double step = 1e-6;
            for (int j = 0; j < terminals.length; j++) {
                double[] up = v0.clone();
                double[] down = v0.clone();
                up[j] += step;
                down[j] -= step;
                double[] iu = model.apply(up);
                double[] id = model.apply(down);
                for (int t = 0; t < terminals.length; t++) {
                    jacobian[t][j] = (iu[t] - id[t]) / (2 * step);
                }
            }
            if (tweak != null) {
                tweak.apply(v0, i0, jacobian);
            }
            for (int t = 0; t < terminals.length; t++) {
                if (terminals[t] < 0) {
                    continue;
                }
                double constant = -i0[t];
                for (int j = 0; j < terminals.length; j++) {
                    constant += jacobian[t][j] * v0[j];
                    if (terminals[j] >= 0) {
                        a[terminals[t]][terminals[j]] += jacobian[t][j];
                    }
                }
                rhs[terminals[t]] += constant;
            }
        }

        @FunctionalInterface
        private interface JacobianTweak {
            void apply(double[] voltages, double[] currents, double[][] jacobian);
        }

        /**
         * A saturated tanh has a vanishing slope, which leaves Newton bouncing between the rails. Keep at least
         * {@link #OPAMP_MIN_SLOPE} of the open-loop gain in the input columns so every step pulls toward the
         * linear region.
         */
        private static void opAmpSlope(Element.OpAmp o, double[] v, double[] i, double[][] jacobian) {
            double mid = (v[3] + v[4]) / 2;
            double half = Math.max((v[3] - v[4]) / 2, 1e-3);
            double t = Math.tanh((o.gain() * (v[0] - v[1]) - mid) / half);
            double slope = o.gain() * Math.max(1 - t * t, OPAMP_MIN_SLOPE) / o.rOut();
            double[] rows = {0, 0, -slope, i[2] < 0 ? slope : 0, i[2] > 0 ? slope : 0};
            for (int r = 2; r < 5; r++) {
                jacobian[r][0] = rows[r];
                jacobian[r][1] = -rows[r];
            }
        }

        /** Currents flowing into collector, base and emitter. */
        static double[] bjt(Element.Bjt q, double[] v) {
            double p = q.polarity();
            double vbe = p * (v[1] - v[2]);
            double vbc = p * (v[1] - v[0]);
            double forward = q.is() * (limitedExp(vbe / VT) - 1);
            double reverse = q.is() * (limitedExp(vbc / VT) - 1);
            double ic = forward - 2 * reverse;
            double ib = forward / q.beta() + reverse;
            return new double[]{p * ic, p * ib, -p * (ic + ib)};
        }

        /** Currents flowing into drain, gate and source. */
        static double[] mos(Element.Mos m, double[] v) {
            double p = m.polarity();
            double vd = p * v[0];
            double vg = p * v[1];
            double vs = p * v[2];
            boolean reversed = vd < vs;
            double drain = reversed ? vs : vd;
            double source = reversed ? vd : vs;
            double overdrive = vg - source - p * m.vth();
            double vds = drain - source;
            double id;
            if (overdrive <= 0) {
                id = 0;
            } else if (vds < overdrive) {
                id = m.k() * (overdrive * vds - vds * vds / 2);
            } else {
                id = m.k() / 2 * overdrive * overdrive;
            }
            double intoDrain = p * (reversed ? -id : id);
            return new double[]{intoDrain, 0, -intoDrain};
        }

        private static double limitedExp(double x) {
            double max = 40;
            return x <= max ? Math.exp(x) : Math.exp(max) * (1 + x - max);
        }

        /** Currents flowing into anode and cathode. */
        static double[] diode(Element.Diode d, double[] v) {
            double i = d.iRef() * (limitedExp((v[0] - v[1] - d.vRef()) / d.nVt()) - Math.exp(-d.vRef() / d.nVt()));
            return new double[]{i, -i};
        }

        /** Currents flowing into IN+, IN-, OUT, V+ and V-. */
        static double[] opAmp(Element.OpAmp o, double[] v) {
            double mid = (v[3] + v[4]) / 2;
            double half = Math.max((v[3] - v[4]) / 2, 1e-3);
            double target = mid + half * Math.tanh((o.gain() * (v[0] - v[1]) - mid) / half);
            double intoOut = (v[2] - target) / o.rOut();
            return new double[]{0, 0, intoOut, intoOut < 0 ? -intoOut : 0, intoOut > 0 ? -intoOut : 0};
        }

        Map<String, Double> voltages(double[] x) {
            Map<String, Double> result = new LinkedHashMap<>();
            groundNames.forEach(name -> result.put(name, 0.0));
            nodeIndex.forEach((name, index) -> result.put(name, clean(x[index])));
            return result;
        }

        double pinVoltage(double[] x, String reference, String pin) {
            return v(x, componentPins.get(reference).get(pin));
        }

        Map<String, Map<String, Double>> pinCurrents(double[] x, double[] previous, double h, boolean[] states) {
            Map<String, Map<String, Double>> result = new LinkedHashMap<>();
            for (Element element : elements) {
                double[] currents = switch (element) {
                    case Element.Resistor r -> twoTerminal(r.g() * (v(x, r.a()) - v(x, r.b())));
                    case Element.Switch s -> twoTerminal((states[s.index()] ? s.gOn() : OFF_CONDUCTANCE)
                            * (v(x, s.a()) - v(x, s.b())));
                    case Element.Capacitor c -> twoTerminal(previous == null || h <= 0 ? 0
                            : c.c() / h * ((v(x, c.a()) - v(x, c.b())) - (v(previous, c.a()) - v(previous, c.b()))));
                    case Element.Inductor l -> twoTerminal(x[l.branch()]);
                    case Element.Source s -> s.component().definition().kind().equals("power-rail")
                            ? new double[]{x[s.branch()]} : twoTerminal(x[s.branch()]);
                    case Element.Bjt q -> {
                        double[] i = bjt(q, new double[]{v(x, q.c()), v(x, q.b()), v(x, q.e())});
                        yield new double[]{i[2], i[1], i[0]};
                    }
                    case Element.Mos m -> {
                        double[] i = mos(m, new double[]{v(x, m.d()), v(x, m.g()), v(x, m.s())});
                        yield new double[]{i[2], i[1], i[0]};
                    }
                    case Element.Diode d -> diode(d, new double[]{v(x, d.a()), v(x, d.k())});
                    case Element.OpAmp o -> {
                        double[] i = opAmp(o, new double[]{v(x, o.inP()), v(x, o.inN()), v(x, o.out()),
                                v(x, o.vPlus()), v(x, o.vMinus())});
                        Map<String, Double> byPin = Map.of("3", i[0], "2", i[1], "6", i[2], "7", i[3], "4", i[4]);
                        var definitionPins = o.component().definition().pins();
                        double[] all = new double[definitionPins.size()];
                        for (int k = 0; k < all.length; k++) {
                            all[k] = byPin.getOrDefault(definitionPins.get(k).number(), 0.0);
                        }
                        yield all;
                    }
                    case Element.LogicPart l -> logicCurrents(l, x);
                };
                Map<String, Double> pins = new LinkedHashMap<>();
                var definitionPins = element.component().definition().pins();
                for (int i = 0; i < currents.length; i++) {
                    pins.put(definitionPins.get(i).number(), clean(currents[i]));
                }
                result.put(element.component().reference(), pins);
            }
            return result;
        }

        /** Currents into each definition pin, in definition order. */
        private double[] logicCurrents(Element.LogicPart l, double[] x) {
            var definitionPins = l.component().definition().pins();
            Map<String, Double> into = new HashMap<>();
            double g = 1 / LOGIC_OUTPUT_OHMS;
            List<String> outputs = l.part().outputPins();
            for (int o = 0; o < outputs.size(); o++) {
                int out = l.pins().get(outputs.get(o));
                char level = logicLevels[l.index()][o];
                double current;
                if (level == Logic.HIGH) {
                    double supply = l.part().powered() ? v(x, l.vcc()) : l.vHigh();
                    current = g * (v(x, out) - supply);
                    if (l.part().powered()) {
                        into.merge(l.part().vccPin(), -current, Double::sum);
                    }
                } else if (level == Logic.LOW) {
                    current = g * (v(x, out) - v(x, l.gnd()));
                    if (l.part().powered()) {
                        into.merge(l.part().gndPin(), -current, Double::sum);
                    }
                } else {
                    current = 0;
                }
                into.merge(outputs.get(o), current, Double::sum);
            }
            double[] result = new double[definitionPins.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = into.getOrDefault(definitionPins.get(i).number(), 0.0);
            }
            return result;
        }

        private static double[] twoTerminal(double current) {
            return new double[]{current, -current};
        }

        static double clean(double value) {
            return Math.abs(value) < 1e-15 ? 0 : value;
        }

        private static double v(double[] x, int node) {
            return node < 0 ? 0 : x[node];
        }

        private static void conductance(double[][] a, int p, int q, double g) {
            if (p >= 0) {
                a[p][p] += g;
            }
            if (q >= 0) {
                a[q][q] += g;
            }
            if (p >= 0 && q >= 0) {
                a[p][q] -= g;
                a[q][p] -= g;
            }
        }

        private static void branch(double[][] a, int p, int q, int k) {
            if (p >= 0) {
                a[p][k] += 1;
                a[k][p] += 1;
            }
            if (q >= 0) {
                a[q][k] -= 1;
                a[k][q] -= 1;
            }
        }

        private static void inject(double[] rhs, int node, double current) {
            if (node >= 0) {
                rhs[node] += current;
            }
        }

        private static double positive(Component c, String parameter) {
            double value = c.value(parameter);
            if (!(value > 0)) {
                throw new DesignException("SIM_INVALID_CIRCUIT", c.reference() + " " + parameter + " must be positive");
            }
            return value;
        }
    }
}
