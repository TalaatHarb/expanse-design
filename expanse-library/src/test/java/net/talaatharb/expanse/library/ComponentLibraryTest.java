package net.talaatharb.expanse.library;

import static org.assertj.core.api.Assertions.*;

import java.util.stream.Stream;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.PinDefinition;
import net.talaatharb.expanse.domain.Logic;
import org.junit.jupiter.api.Test;

class ComponentLibraryTest {
    private final ComponentLibrary library = new ComponentLibrary();

    @Test
    void everyLogicKindHasADefinitionWhosePinsCoverTheLogicCatalog() {
        assertThat(Design.KINDS).containsAll(Logic.kinds());
        for (String kind : Logic.kinds()) {
            var definitions = library.all().stream().filter(d -> d.kind().equals(kind)).toList();
            assertThat(definitions).as(kind).hasSize(1);
            var definition = definitions.getFirst();
            var part = Logic.part(kind).orElseThrow();
            var numbers = definition.pins().stream().map(PinDefinition::number).toList();
            Stream.concat(part.inputPins().stream(), part.outputPins().stream()).forEach(pin ->
                    assertThat(numbers).as(kind + " pin " + pin).contains(pin));
            if (part.powered()) {
                assertThat(definition.pins().get(Integer.parseInt(part.vccPin()) - 1).name()).isEqualTo("VCC");
                assertThat(definition.pins().get(Integer.parseInt(part.gndPin()) - 1).name()).isEqualTo("GND");
                assertThat(definition.footprint().pads()).hasSize(14);
            }
        }
    }

    @Test
    void dip14PadsFollowTheCounterClockwiseTopViewNumbering() {
        var pads = library.get("builtin.74hc00").footprint().pads();
        double[][] expected = {{-3.81, 7.62}, {-3.81, -7.62}, {3.81, -7.62}, {3.81, 7.62}};
        int[] indices = {0, 6, 7, 13};
        for (int i = 0; i < indices.length; i++) {
            assertThat(pads.get(indices[i]).xMm()).isCloseTo(expected[i][0], within(1e-9));
            assertThat(pads.get(indices[i]).yMm()).isCloseTo(expected[i][1], within(1e-9));
        }
    }
}
