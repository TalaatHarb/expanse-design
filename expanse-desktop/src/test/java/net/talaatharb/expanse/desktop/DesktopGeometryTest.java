package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.dto.ApiModels.ComponentView;
import net.talaatharb.expanse.dto.ApiModels.NetView;
import net.talaatharb.expanse.dto.ApiModels.PinView;
import net.talaatharb.expanse.dto.ApiModels.PointView;
import net.talaatharb.expanse.dto.ApiModels.WireView;
import org.junit.jupiter.api.Test;

class DesktopGeometryTest {
    @Test
    void orthographicViewFitsTheBoardAndKeepsTheSceneInsideTheClipVolumeAtAnyZoom() {
        double scale = AssemblyPane.fitScale(1400, 700, 80);
        assertThat(80 * scale).as("board spans most of the shorter side").isBetween(400.0, 700.0);

        for (double zoom : new double[] {scale, scale * 10, scale * 100}) {
            double depth = AssemblyPane.depthCompression(1400, 700, zoom, 60);
            assertThat(60 * zoom * depth).as("eye depth of the farthest point at zoom %s", zoom)
                    .isLessThanOrEqualTo(1400 / 2.0);
            assertThat(depth).isPositive().isLessThanOrEqualTo(1);
        }
        assertThat(AssemblyPane.depthCompression(1400, 700, 0.1, 60)).as("no compression when it already fits")
                .isEqualTo(1);
    }

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

    @Test
    void wiresFollowTheirPinsWithOrthogonalElbowsAndTeesProduceJunctionsAndGroups() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        var pins = Map.of(a, new double[] {0, 0}, b, new double[] {10, 5}, c, new double[] {5, 20},
                d, new double[] {40, 40});
        var main = new WireView(UUID.randomUUID(), a, b, List.of());
        var mainPath = WireGeometry.path(main, pins);
        assertThat(mainPath).as("diagonal end gets a horizontal-then-vertical elbow")
                .containsExactly(new double[] {0, 0}, new double[] {10, 0}, new double[] {10, 5});

        var tee = new WireView(UUID.randomUUID(), null, c, List.of(new PointView(5, 0)));
        var teePath = WireGeometry.path(tee, pins);
        assertThat(teePath).containsExactly(new double[] {5, 0}, new double[] {5, 20});
        assertThat(WireGeometry.path(new WireView(UUID.randomUUID(), a, UUID.randomUUID(), List.of()), pins))
                .as("unknown pin").isNull();

        assertThat(WireGeometry.junctions(List.of(mainPath, teePath))).as("free end on a segment is a T junction")
                .containsExactly(new double[] {5, 0});
        assertThat(WireGeometry.junctions(List.of(mainPath))).as("a lone elbow is not a junction").isEmpty();
        assertThat(WireGeometry.distance(mainPath, 7, 1)).isCloseTo(1, within(1e-9));
        assertThat(WireGeometry.closest(mainPath, 12, 3)).containsExactly(10, 3);

        var net = new NetView(UUID.randomUUID(), "N", List.of(a, b, c, d), List.of(main, tee));
        var paths = Map.of(main.id(), mainPath, tee.id(), teePath);
        assertThat(WireGeometry.groups(net, pins, paths)).as("d is not reached by any wire")
                .containsExactly(List.of(a, b, c), List.of(d));
        assertThat(WireGeometry.groups(new NetView(UUID.randomUUID(), "M", List.of(a, b), List.of()), pins, Map.of()))
                .containsExactly(List.of(a), List.of(b));
    }

    @Test
    void partFormsRoundTripAndGeneratePadsThatMatchThePins() {
        var pins = PartForms.pins("1, VIN\n\n2, GND\n3, VOUT, optional");
        assertThat(pins).extracting(p -> p.number() + ":" + p.name() + ":" + p.required())
                .containsExactly("1:VIN:true", "2:GND:true", "3:VOUT:false");
        assertThat(PartForms.pins(PartForms.pinsText(pins))).isEqualTo(pins);
        assertThatThrownBy(() -> PartForms.pins("1, A\n2, B, maybe")).hasMessageStartingWith("Line 2:");

        var parameters = PartForms.parameters("gain, , 10\nmode, , fast, fast|slow");
        assertThat(parameters.get(1).allowedValues()).containsExactly("fast", "slow");
        assertThat(PartForms.parameters(PartForms.parametersText(parameters))).isEqualTo(parameters);
        assertThatThrownBy(() -> PartForms.parameters("mode, , turbo, fast|slow")).hasMessageContaining("allowed");

        var dip = PartForms.dip(PartForms.pins("1\n2\n3\n4\n5\n6\n7\n8"), 2.54, 7.62, 1.6, 0.8);
        assertThat(dip).extracting(p -> p.number() + "@" + p.xMm() + "," + p.yMm()).containsExactly(
                "1@-3.81,-3.81", "2@-3.81,-1.27", "3@-3.81,1.27", "4@-3.81,3.81",
                "5@3.81,3.81", "6@3.81,1.27", "7@3.81,-1.27", "8@3.81,-3.81");
        assertThat(PartForms.courtyard(dip)).containsExactly(10.22, 10.22);
        assertThat(PartForms.pads(PartForms.padsText(dip))).isEqualTo(dip);
        assertThat(PartForms.sip(PartForms.pins("1\n2\n3"), 2.54, 1.6, 0.8))
                .extracting(p -> p.xMm()).containsExactly(-2.54, 0.0, 2.54);
        assertThatThrownBy(() -> PartForms.pads("1, 0, 0, x, 1, 0")).hasMessageContaining("not a number");
    }

    @Test
    void genericPartsGetABoxWithPinsOnBothSidesOnTheGrid() {
        var part = component("generic", 0, "1", "2", "3", "4", "5");
        var at = part.pins().stream().map(p -> Symbols.pin(part, p)).toList();
        assertThat(at).allSatisfy(p -> {
            assertThat(Math.abs(Math.round((p[0] - 10) / Symbols.GRID) * Symbols.GRID - (p[0] - 10))).isLessThan(1e-9);
            assertThat(Math.abs(Math.round((p[1] - 20) / Symbols.GRID) * Symbols.GRID - (p[1] - 20))).isLessThan(1e-9);
        });
        assertThat(at.subList(0, 3)).allSatisfy(p -> assertThat(p[0]).isLessThan(10));
        assertThat(at.subList(3, 5)).allSatisfy(p -> assertThat(p[0]).isGreaterThan(10));
        assertThat(at.stream().map(p -> p[0] + "," + p[1]).distinct()).hasSize(5);
        double[] half = Symbols.halfExtents(part);
        assertThat(at).allSatisfy(p -> assertThat(Math.abs(p[1] - 20)).isLessThan(half[1]));
    }
}