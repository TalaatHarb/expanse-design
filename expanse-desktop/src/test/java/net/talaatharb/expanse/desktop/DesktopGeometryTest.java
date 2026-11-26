package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.dto.ApiModels.ComponentView;
import net.talaatharb.expanse.dto.ApiModels.PinView;
import org.junit.jupiter.api.Test;

class DesktopGeometryTest {
    private static ComponentView component(String kind, int rotation, String... pins) {
        var pinViews = java.util.Arrays.stream(pins).map(n -> new PinView(UUID.randomUUID(), n, n, true)).toList();
        return new ComponentView(UUID.randomUUID(), UUID.randomUUID(), "X1", "d", "1", kind, kind, 10, 20, rotation,
                Map.of(), List.of(), true, pinViews);
    }

    @Test
    void pinsSitOnTheGridAndFollowTheServerRotationConvention() {
        var resistor = component("resistor", 0, "1", "2");
        assertThat(Symbols.pin(resistor, resistor.pins().get(0))).containsExactly(10 - 5.08, 20);
        assertThat(Symbols.pin(resistor, resistor.pins().get(1))).containsExactly(10 + 5.08, 20);

        var rotated = component("resistor", 90, "1", "2");
        assertThat(Symbols.pin(rotated, rotated.pins().get(0))).containsExactly(10, 20 - 5.08);

        var npn = component("npn", 0, "1", "2", "3");
        assertThat(Symbols.pin(npn, npn.pins().get(1))).as("base on the left").containsExactly(10 - 5.08, 20);
        assertThat(Symbols.pin(npn, npn.pins().get(2))[1]).as("collector above (Y down)").isLessThan(20);
        assertThat(Symbols.halfExtents(component("voltage-source", 90, "1", "2"))).containsExactly(5.08, 3.2);
    }

    @Test
    void logicSymbolsPlacePinsOnTheGridLikeTheirPackages() {
        var nand = component("logic-nand", 0, "1", "2", "3");
        assertThat(Symbols.pin(nand, nand.pins().get(0))).containsExactly(10 - 5.08, 20 - 2.54);
        assertThat(Symbols.pin(nand, nand.pins().get(2))).containsExactly(10 + 5.08, 20);

        String[] numbers = java.util.stream.IntStream.rangeClosed(1, 14).mapToObj(String::valueOf)
                .toArray(String[]::new);
        var dip = component("logic-74hc00", 0, numbers);
        assertThat(Symbols.pin(dip, dip.pins().get(0))).as("pin 1 top left").containsExactly(10 - 7.62, 20 - 7.62);
        assertThat(Symbols.pin(dip, dip.pins().get(6))).as("pin 7 bottom left").containsExactly(10 - 7.62, 20 + 7.62);
        assertThat(Symbols.pin(dip, dip.pins().get(7))).as("pin 8 bottom right").containsExactly(10 + 7.62, 20 + 7.62);
        assertThat(Symbols.pin(dip, dip.pins().get(13))).as("pin 14 top right").containsExactly(10 + 7.62, 20 - 7.62);
        assertThat(dip.pins().stream().map(p -> java.util.Arrays.toString(Symbols.pin(dip, p))).distinct())
                .hasSize(14);
        assertThat(Symbols.referencePrefix("logic-clock")).isEqualTo("CLK");
        assertThat(Symbols.value(dip)).isEqualTo("74HC00");
    }

    @Test
    void timingSegmentsRunUntilTheNextTransitionOrStopTime() {
        var segments = TimingDiagram.segments(List.of(
                new net.talaatharb.expanse.dto.ApiModels.LogicTransitionView(0, "0"),
                new net.talaatharb.expanse.dto.ApiModels.LogicTransitionView(1e-3, "1"),
                new net.talaatharb.expanse.dto.ApiModels.LogicTransitionView(3e-3, "X")), 2e-3);
        assertThat(segments).containsExactly(new TimingDiagram.Segment(0, 1e-3, '0'),
                new TimingDiagram.Segment(1e-3, 2e-3, '1'));
    }

    @Test
    void siValuesParseAndFormat() {
        assertThat(SimulationPane.parseSi("10m", "t")).isCloseTo(0.01, within(1e-15));
        assertThat(SimulationPane.parseSi("2.2u", "t")).isCloseTo(2.2e-6, within(1e-18));
        assertThat(SimulationPane.parseSi("1meg", "t")).isEqualTo(1e6);
        assertThat(SimulationPane.parseSi("5ms", "t")).isCloseTo(0.005, within(1e-15));
        assertThat(SimulationPane.parseSi("3", "t")).isEqualTo(3);
        assertThatThrownBy(() -> SimulationPane.parseSi("abc", "stop time")).hasMessageContaining("stop time");
        assertThat(SimulationPane.format(0.004545, "A")).isEqualTo("4.545 mA");
        assertThat(SimulationPane.format(4700, "\u03a9")).isEqualTo("4.700 k\u03a9");
    }
}
