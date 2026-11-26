package net.talaatharb.expanse.application;

import java.util.HashSet;
import java.util.List;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Body;
import net.talaatharb.expanse.domain.Design.Definition;
import net.talaatharb.expanse.domain.Design.Footprint;
import net.talaatharb.expanse.domain.Design.Pad;
import net.talaatharb.expanse.domain.Design.ParameterDefinition;
import net.talaatharb.expanse.domain.Design.PinDefinition;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.dto.ApiModels.PartSpec;
import net.talaatharb.expanse.library.ComponentLibrary;

/** Converts project-part specifications to validated domain definitions; {@link DesignViewMapper} maps them back. */
public final class PartSpecs {
    private PartSpecs() {
    }

    public static String id(String idOrName) {
        Design.require(idOrName != null && !idOrName.isBlank(), "A part ID is required");
        return idOrName.startsWith(Design.PROJECT_PART_PREFIX) ? idOrName : Design.PROJECT_PART_PREFIX + idOrName;
    }

    public static Definition toDefinition(PartSpec spec, ComponentLibrary library) {
        Design.require(spec != null, "define_part requires a part");
        String kind = spec.kind() == null || spec.kind().isBlank() ? Design.GENERIC_KIND : spec.kind();
        Design.require(Design.KINDS.contains(kind), "Unknown component kind: " + kind);
        Design.require(spec.pins() != null, "A part requires pins");
        var pins = spec.pins().stream().map(p -> new PinDefinition(p.number(), p.name(), p.required())).toList();
        var parameters = spec.parameters() == null ? List.<ParameterDefinition>of() : spec.parameters().stream()
                .map(p -> new ParameterDefinition(p.name(), p.unit() == null ? "" : p.unit(), p.defaultValue(),
                        p.allowedValues() == null ? List.of() : p.allowedValues())).toList();
        var pads = spec.pads() == null ? List.<Pad>of() : spec.pads().stream()
                .map(p -> new Pad(p.number(), p.xMm(), p.yMm(), p.widthMm(), p.heightMm(), p.drillMm())).toList();
        Body body = spec.body() == null
                ? pads.isEmpty() ? new Body("none", 0, 0, 0, "#808080")
                : new Body("box", spec.courtyardWidthMm(), spec.courtyardHeightMm(), 2, "#404040")
                : new Body(spec.body().shape(), spec.body().widthMm(), spec.body().depthMm(), spec.body().heightMm(),
                spec.body().color());
        String footprint = spec.footprintName() == null || spec.footprintName().isBlank() ? spec.name()
                : spec.footprintName();
        var definition = new Definition(id(spec.id()), spec.version() == null || spec.version().isBlank() ? "1"
                : spec.version(), spec.name(), kind, pins, parameters,
                new Footprint(footprint, pads, spec.courtyardWidthMm(), spec.courtyardHeightMm()), body);
        if (!Design.GENERIC_KIND.equals(kind)) {
            requireCompatible(definition, library);
        }
        return definition;
    }

    /** A custom part of a built-in kind must expose the pins and parameters that kind's simulation model reads. */
    private static void requireCompatible(Definition part, ComponentLibrary library) {
        var builtins = library.search("").stream().filter(d -> d.kind().equals(part.kind())).toList();
        var pins = new HashSet<>(part.pins().stream().map(PinDefinition::number).toList());
        for (var builtin : builtins) {
            boolean samePins = pins.equals(new HashSet<>(builtin.pins().stream().map(PinDefinition::number).toList()));
            boolean parameters = builtin.parameters().stream().allMatch(b -> part.parameters().stream().anyMatch(
                    p -> p.name().equals(b.name()) && p.unit().equals(b.unit())
                            && p.allowedValues().isEmpty() == b.allowedValues().isEmpty()));
            if (samePins && parameters) {
                return;
            }
        }
        var example = builtins.isEmpty() ? null : builtins.getFirst();
        throw new DesignException("INVALID_DESIGN", "A " + part.kind() + " part must have the pin numbers and "
                + "parameters of the built-in model" + (example == null ? "" : ", for example pins "
                + example.pins().stream().map(PinDefinition::number).toList() + " and parameters "
                + example.parameters().stream().map(p -> p.name() + " [" + p.unit() + "]").toList())
                + "; use kind generic otherwise");
    }
}
