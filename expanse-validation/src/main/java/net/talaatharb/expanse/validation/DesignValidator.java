package net.talaatharb.expanse.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Component;
import net.talaatharb.expanse.domain.Design.Net;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.Logic;

/** Read-only electrical rule checks. Never mutates the project. */
public final class DesignValidator {
    public record Finding(String code, String severity, String message, List<UUID> affectedIds) {
    }

    public List<Finding> validate(Project project) {
        List<Finding> findings = new ArrayList<>();
        Map<UUID, Net> netOfPin = new HashMap<>();
        project.nets().forEach(n -> n.pinIds().forEach(p -> netOfPin.put(p, n)));
        for (Component c : project.components()) {
            for (var pin : c.pins()) {
                boolean required = c.definition().pins().stream()
                        .anyMatch(d -> d.number().equals(pin.number()) && d.required());
                if (required && !netOfPin.containsKey(pin.id())) {
                    findings.add(new Finding("SCH_REQUIRED_PIN_UNCONNECTED", "WARNING",
                            "Connect " + c.reference() + " pin " + pin.number() + " to a net.",
                            List.of(c.id(), pin.id())));
                }
            }
            if (c.pins().size() >= 2) {
                Set<Net> nets = new HashSet<>();
                boolean allConnected = c.pins().stream().allMatch(p -> netOfPin.containsKey(p.id()));
                c.pins().forEach(p -> nets.add(netOfPin.get(p.id())));
                if (allConnected && nets.size() == 1) {
                    boolean source = c.definition().kind().equals("voltage-source");
                    findings.add(new Finding(source ? "ERC_SOURCE_SHORTED" : "ERC_COMPONENT_SHORTED",
                            source ? "ERROR" : "WARNING",
                            "All pins of " + c.reference() + " are on net " + nets.iterator().next().name() + ".",
                            List.of(c.id())));
                }
            }
        }
        boolean electrical = project.components().stream()
                .anyMatch(c -> !c.definition().kind().equals("ground"));
        boolean grounded = project.components().stream().anyMatch(c -> c.definition().kind().equals("ground"));
        if (electrical && !project.components().isEmpty() && !grounded) {
            findings.add(new Finding("ERC_NO_GROUND", "WARNING",
                    "Add a ground symbol; simulation needs a 0 V reference.", List.of()));
        }
        for (Net net : project.nets()) {
            Map<String, List<Component>> fixed = new HashMap<>();
            for (Component c : project.components()) {
                String kind = c.definition().kind();
                if ((kind.equals("ground") || kind.equals("power-rail"))
                        && c.pins().stream().anyMatch(p -> net.pinIds().contains(p.id()))) {
                    String level = kind.equals("ground") ? "0" : String.valueOf(c.value("voltage"));
                    fixed.computeIfAbsent(level, k -> new ArrayList<>()).add(c);
                }
            }
            if (fixed.size() > 1) {
                findings.add(new Finding("ERC_POWER_CONFLICT", "ERROR",
                        "Net " + net.name() + " is tied to different supply levels.",
                        fixed.values().stream().flatMap(List::stream).map(Component::id).toList()));
            }
            var owners = project.components().stream()
                    .filter(c -> c.pins().stream().anyMatch(p -> net.pinIds().contains(p.id()))).toList();
            // Feedback inside one package (logic QN to D, op-amp follower OUT to IN-) is intentional.
            if (owners.size() == 1
                    && !owners.getFirst().definition().kind().equals("opamp")
                    && !Logic.part(owners.getFirst().definition().kind()).map(Logic.Part::powered).orElse(false)) {
                findings.add(new Finding("ERC_SINGLE_COMPONENT_NET", "WARNING",
                        "Net " + net.name() + " only connects pins of one component.", List.of(net.id())));
            }
        }
        logic(project, netOfPin, findings);
        Set<UUID> placed = new HashSet<>();
        project.board().placements().forEach(p -> placed.add(p.componentId()));
        for (Component c : project.components()) {
            if (c.definition().footprint().physical() && !placed.contains(c.id())) {
                findings.add(new Finding("PCB_COMPONENT_UNPLACED", "INFO",
                        c.reference() + " is not placed on the board yet.", List.of(c.id())));
            }
        }
        return List.copyOf(findings);
    }

    private static void logic(Project project, Map<UUID, Net> netOfPin, List<Finding> findings) {
        Map<UUID, List<Component>> outputsOnNet = new HashMap<>();
        Map<UUID, List<Component>> suppliesOnNet = new HashMap<>();
        Map<UUID, Set<String>> kindsOnNet = new HashMap<>();
        Map<UUID, Integer> nonInputPins = new HashMap<>();
        for (Component c : project.components()) {
            String kind = c.definition().kind();
            var part = Logic.part(kind);
            for (var pin : c.pins()) {
                Net net = netOfPin.get(pin.id());
                if (net == null) {
                    continue;
                }
                kindsOnNet.computeIfAbsent(net.id(), k -> new HashSet<>()).add(kind);
                boolean input = part.isPresent() && part.get().inputPins().contains(pin.number());
                if (!input) {
                    nonInputPins.merge(net.id(), 1, Integer::sum);
                }
                if (part.isPresent() && part.get().outputPins().contains(pin.number())) {
                    outputsOnNet.computeIfAbsent(net.id(), k -> new ArrayList<>()).add(c);
                }
                if (kind.equals("power-rail") || kind.equals("ground")) {
                    suppliesOnNet.computeIfAbsent(net.id(), k -> new ArrayList<>()).add(c);
                }
            }
        }
        for (Net net : project.nets()) {
            List<Component> outputs = outputsOnNet.getOrDefault(net.id(), List.of());
            List<Component> supplies = suppliesOnNet.getOrDefault(net.id(), List.of());
            if (outputs.size() > 1 || (!outputs.isEmpty() && !supplies.isEmpty())) {
                List<UUID> ids = new ArrayList<>();
                outputs.forEach(c -> ids.add(c.id()));
                supplies.forEach(c -> ids.add(c.id()));
                findings.add(new Finding("LOGIC_OUTPUT_CONFLICT", "ERROR", "Net " + net.name()
                        + " is driven by more than one logic output or supply.", List.copyOf(ids)));
            }
        }
        for (Component c : project.components()) {
            var found = Logic.part(c.definition().kind());
            if (found.isEmpty()) {
                continue;
            }
            Logic.Part part = found.get();
            for (Logic.Unit unit : part.units()) {
                boolean used = !part.powered() || unit.outputs().stream()
                        .anyMatch(o -> netOfPin.containsKey(c.pin(o).id()));
                if (!used) {
                    continue;
                }
                for (String input : unit.inputs()) {
                    var pin = c.pin(input);
                    Net net = netOfPin.get(pin.id());
                    String label = c.reference() + " pin " + input + " (" + pinName(c, input) + ")";
                    if (net == null && part.powered()) {
                        findings.add(new Finding("LOGIC_INPUT_FLOATING", "WARNING", label
                                + " drives a used gate but is unconnected; tie it high or low.",
                                List.of(c.id(), pin.id())));
                    } else if (net != null && nonInputPins.getOrDefault(net.id(), 0) == 0) {
                        findings.add(new Finding("LOGIC_INPUT_UNDRIVEN", "WARNING", label + " is on net "
                                + net.name() + " which has no driver.", List.of(c.id(), net.id())));
                    }
                }
            }
            if (part.powered()) {
                boolean active = c.pins().stream().anyMatch(p -> !p.number().equals(part.vccPin())
                        && !p.number().equals(part.gndPin()) && netOfPin.containsKey(p.id()));
                Net vcc = netOfPin.get(c.pin(part.vccPin()).id());
                Net gnd = netOfPin.get(c.pin(part.gndPin()).id());
                boolean supplied = vcc != null && kindsOnNet.get(vcc.id()).stream()
                        .anyMatch(k -> k.equals("power-rail") || k.equals("voltage-source"));
                boolean grounded = gnd != null && kindsOnNet.get(gnd.id()).stream()
                        .anyMatch(k -> k.equals("ground") || k.equals("voltage-source"));
                if (active && (!supplied || !grounded)) {
                    findings.add(new Finding("LOGIC_IC_UNPOWERED", "WARNING", c.reference()
                            + " needs VCC on a supply and GND on ground; its outputs are unknown.", List.of(c.id())));
                }
            }
        }
    }

    private static String pinName(Component c, String number) {
        return c.definition().pins().stream().filter(p -> p.number().equals(number)).findFirst()
                .map(p -> p.name()).orElse(number);
    }
}
