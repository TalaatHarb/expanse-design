package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.junit.jupiter.api.Test;

class SchematicClipboardTest {
    private static ComponentView component(String reference, String definition, double x, PinView... pins) {
        return new ComponentView(UUID.randomUUID(), null, reference, definition, "1", reference, "resistor", x, 10, 90,
                Map.of("resistance", "4k7"), List.of(), true, List.of(pins));
    }

    private static PinView pin(String number) {
        return new PinView(UUID.randomUUID(), number, "P" + number, true);
    }

    @Test
    void pasteRenumbersAndRebuildsOnlyTheInternalConnectivity() {
        PinView r1a = pin("1"), r1b = pin("2"), r2a = pin("1"), r2b = pin("2"), r3a = pin("1"), r3b = pin("2");
        ComponentView r1 = component("R1", "project.res", 10, r1a, r1b);
        ComponentView r2 = component("R2", "builtin.resistor", 20, r2a, r2b);
        ComponentView r3 = component("R3", "builtin.resistor", 30, r3a, r3b);
        var internal = new WireView(UUID.randomUUID(), r1b.id(), r2a.id(), List.of(new PointView(15, 10)));
        var outgoing = new WireView(UUID.randomUUID(), r2b.id(), r3a.id(), List.of());
        var tee = new WireView(UUID.randomUUID(), r1b.id(), null, List.of(new PointView(15, 20)));
        var label = new NetLabelView(UUID.randomUUID(), r1a.id(), null);
        var part = new PartSpec("project.res", "1", "Res", "resistor", List.of(new PinSpec("1", "A", true)), List.of(),
                null, List.of(), 0, 0, null);
        var snapshot = new Snapshot(UUID.randomUUID(), "p", 3, List.of(), List.of(r1, r2, r3), List.of(
                new NetView(UUID.randomUUID(), "MID", List.of(r1b.id(), r2a.id()), List.of(internal, tee)),
                new NetView(UUID.randomUUID(), "OUT", List.of(r2b.id(), r3a.id()), List.of(outgoing)),
                new NetView(UUID.randomUUID(), "IN", List.of(r1a.id()), List.of(), List.of(label))),
                null, List.of(part));

        var clip = SchematicClipboard.copy(snapshot, List.of(r1.id(), r2.id()));
        assertThat(clip.originX()).isEqualTo(10);
        var paste = SchematicClipboard.paste(clip, snapshot, 5, 0);

        assertThat(paste.references()).containsExactly("R4", "R5");
        assertThat(paste.operations()).containsExactly(
                new PlaceOp("project.res", "R4", 15, 10, 90, Map.of("resistance", "4k7"), null),
                new PlaceOp("builtin.resistor", "R5", 25, 10, 90, Map.of("resistance", "4k7"), null),
                new ConnectOp(null, List.of("R4.2", "R5.1")),
                new PlaceNetLabelOp("IN", "R4.1", null, null, null),
                new DrawWireOp("R4.2", "R5.1", null, List.of(new PointView(20, 10)), null),
                new DrawWireOp("R4.2", null, null, List.of(new PointView(20, 20)), null));

        var otherProject = new Snapshot(UUID.randomUUID(), "q", 0, List.of(), List.of(), List.of(), null, List.of());
        var into = SchematicClipboard.paste(clip, otherProject, 0, 0);
        assertThat(into.operations().getFirst()).isEqualTo(new DefinePartOp(part));
        assertThat(into.references()).containsExactly("R1", "R2");
    }

    @Test
    void nothingSelectedCopiesNothingAndReferencesKeepTheirPrefix() {
        var snapshot = new Snapshot(UUID.randomUUID(), "p", 0, List.of(), List.of(), List.of(), null, List.of());
        assertThat(SchematicClipboard.copy(snapshot, List.of(UUID.randomUUID()))).isNull();
        assertThat(SchematicClipboard.prefix("U12")).isEqualTo("U");
        assertThat(SchematicClipboard.prefix("42")).isEqualTo("X");
        assertThat(SchematicClipboard.nextReference("D", java.util.Set.of("D1", "D7", "DX3", "R9"))).isEqualTo("D8");
    }
}
