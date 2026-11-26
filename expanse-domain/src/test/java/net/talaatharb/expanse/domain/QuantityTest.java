package net.talaatharb.expanse.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QuantityTest {
    @Test
    void parsesEngineeringNotationAndMatchingUnits() {
        assertThat(Quantity.parse("4.7k", "ohm")).isCloseTo(4700, within(1e-9));
        assertThat(Quantity.parse("4.7kΩ", "ohm")).isCloseTo(4700, within(1e-9));
        assertThat(Quantity.parse("2M", "ohm")).isCloseTo(2e6, within(1e-6));
        assertThat(Quantity.parse("2meg", "ohm")).isCloseTo(2e6, within(1e-6));
        assertThat(Quantity.parse("10m", "ohm")).isCloseTo(0.01, within(1e-12));
        assertThat(Quantity.parse("100nF", "F")).isCloseTo(1e-7, within(1e-18));
        assertThat(Quantity.parse("10uH", "H")).isCloseTo(1e-5, within(1e-15));
        assertThat(Quantity.parse("-2", "V")).isEqualTo(-2);
        assertThat(Quantity.parse("1e-3", "")).isEqualTo(1e-3);
        assertThat(Quantity.parse("5V", "V")).isEqualTo(5);
    }

    @Test
    void rejectsMalformedOrMismatchedValues() {
        for (String bad : new String[]{"", "abc", "1x", "1kF", "NaN", "Infinity", "1..2", "1e999"}) {
            assertThatThrownBy(() -> Quantity.parse(bad, "ohm")).as(bad).isInstanceOf(DesignException.class);
        }
    }

    @Test
    void formatsWithEngineeringPrefixesThatParseBack() {
        assertThat(Quantity.format(4700, "ohm")).isEqualTo("4.7kΩ");
        assertThat(Quantity.format(1e-7, "F")).isEqualTo("100nF");
        assertThat(Quantity.format(0, "V")).isEqualTo("0V");
        assertThat(Quantity.parse(Quantity.format(0.0033, "H"), "H")).isCloseTo(0.0033, within(1e-12));
    }
}
