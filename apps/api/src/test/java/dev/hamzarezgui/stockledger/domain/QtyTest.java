package dev.hamzarezgui.stockledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contract of {@link Qty}, centred on the failures it exists to prevent.
 *
 * <p>Mirrors {@code packages/qty/src/qty.test.ts}, so the browser-side input
 * validation and the authoritative server-side domain are held to one contract.
 */
class QtyTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void convertsWholeUnitsToMilliUnits() {
            assertThat(Qty.of(1L).milli()).isEqualTo(1_000L);
            assertThat(Qty.of(0L).milli()).isZero();
            assertThat(Qty.of(-3L).milli()).isEqualTo(-3_000L);
        }

        @Test
        void acceptsTheSmallestRepresentableUnit() {
            assertThat(Qty.of(new BigDecimal("0.001")).milli()).isEqualTo(1L);
            assertThat(Qty.of(new BigDecimal("1.234")).milli()).isEqualTo(1_234L);
            assertThat(Qty.of(new BigDecimal("-0.001")).milli()).isEqualTo(-1L);
        }

        @Test
        void refusesExcessPrecisionRatherThanRoundingItAway() {
            // Rounding here would silently destroy stock. Refuse loudly instead.
            assertThatExceptionOfType(QtyException.class)
                    .isThrownBy(() -> Qty.of(new BigDecimal("0.0001")))
                    .withMessageContaining("more than 3 decimal places");
        }

        @Test
        void acceptsTrailingZerosWhichCarryNoInformation() {
            assertThat(Qty.of(new BigDecimal("1.5000"))).isEqualTo(Qty.of(new BigDecimal("1.5")));
            assertThat(Qty.of(new BigDecimal("2.000"))).isEqualTo(Qty.of(2L));
        }

        @Test
        void rejectsNull() {
            assertThatExceptionOfType(QtyException.class)
                    .isThrownBy(() -> Qty.of((BigDecimal) null));
        }

        @Test
        void rejectsMagnitudesTheDatabaseColumnCouldNotHold() {
            // The ceiling matches numeric(14,3) exactly, so validation fails
            // here with a clear message instead of at INSERT time.
            assertThatNoException().isThrownBy(() -> Qty.ofMilli(Qty.MAX_MILLI));
            assertThatExceptionOfType(QtyException.class)
                    .isThrownBy(() -> Qty.ofMilli(Qty.MAX_MILLI + 1))
                    .withMessageContaining("out of range");
            assertThatExceptionOfType(QtyException.class)
                    .isThrownBy(() -> Qty.ofMilli(-Qty.MAX_MILLI - 1));
        }
    }

    @Nested
    @DisplayName("float-safety (the reason this type exists)")
    class FloatSafety {

        @Test
        void addsFractionsExactlyWhereDoublesDoNot() {
            // The bug being designed out:
            assertThat(0.1 + 0.2).isNotEqualTo(0.3);

            Qty result = Qty.of(new BigDecimal("0.1")).plus(Qty.of(new BigDecimal("0.2")));
            assertThat(result).isEqualTo(Qty.of(new BigDecimal("0.3")));
            assertThat(result.toDisplayString()).isEqualTo("0.3");
        }

        @Test
        void returnsToExactlyZeroAfterManyFractionalMovements() {
            // Ten receipts of 1 gram then ten issues of 1 gram must land on a
            // true zero, or a physically empty bin reports residual stock
            // forever and never shows as out of stock.
            Qty balance = Qty.ZERO;
            Qty gram = Qty.of(new BigDecimal("0.001"));
            for (int i = 0; i < 10; i++) {
                balance = balance.plus(gram);
            }
            for (int i = 0; i < 10; i++) {
                balance = balance.minus(gram);
            }
            assertThat(balance).isEqualTo(Qty.ZERO);
            assertThat(balance.isZero()).isTrue();
            assertThat(balance.toDisplayString()).isEqualTo("0");
        }

        @Test
        void sumsThreeThousandGramsToExactlyThreeKilograms() {
            List<Qty> movements = IntStream.range(0, 3_000)
                    .mapToObj(i -> Qty.of(new BigDecimal("0.001")))
                    .collect(Collectors.toList());
            assertThat(Qty.sum(movements)).isEqualTo(Qty.of(3L));
        }

        @Test
        void isOrderIndependentSoAReplayedLedgerGivesOneAnswer() {
            List<Qty> forward = List.of(
                    Qty.parse("1.111"), Qty.parse("2.222"), Qty.parse("-0.333"), Qty.parse("0.001"));
            List<Qty> reversed = List.of(
                    Qty.parse("0.001"), Qty.parse("-0.333"), Qty.parse("2.222"), Qty.parse("1.111"));
            assertThat(Qty.sum(forward)).isEqualTo(Qty.sum(reversed));
        }
    }

    @Nested
    @DisplayName("equality — the BigDecimal trap this type removes")
    class Equality {

        @Test
        void equalsAgreesWithCompareToRegardlessOfHowTheValueWasBuilt() {
            // BigDecimal.equals compares scale, so these are NOT equal as
            // BigDecimals even though they are the same quantity:
            assertThat(new BigDecimal("1.5")).isNotEqualTo(new BigDecimal("1.500"));
            assertThat(new BigDecimal("1.5")).isEqualByComparingTo(new BigDecimal("1.500"));

            // Qty has one canonical representation, so equals and compareTo agree.
            Qty a = Qty.parse("1.5");
            Qty b = Qty.parse("1.500");
            Qty c = Qty.of(new BigDecimal("1.5000"));
            assertThat(a).isEqualTo(b).isEqualTo(c);
            assertThat(a.compareTo(b)).isZero();
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void worksAsAMapKey() {
            // The practical consequence: a quantity can safely key a map or
            // join a set, which BigDecimal cannot do reliably.
            var seen = new java.util.HashSet<Qty>();
            seen.add(Qty.parse("1.5"));
            assertThat(seen).contains(Qty.parse("1.500"));
            assertThat(seen.add(Qty.parse("1.5000"))).isFalse();
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        void parsesWhatPostgresNumericReturns() {
            assertThat(Qty.parse("0.000")).isEqualTo(Qty.ZERO);
            assertThat(Qty.parse("1.000")).isEqualTo(Qty.of(1L));
            assertThat(Qty.parse("12.500").milli()).isEqualTo(12_500L);
            assertThat(Qty.parse("-3.250").milli()).isEqualTo(-3_250L);
        }

        @Test
        void parsesShorthandHumansType() {
            assertThat(Qty.parse("7")).isEqualTo(Qty.of(7L));
            assertThat(Qty.parse("7.5").milli()).isEqualTo(7_500L);
            assertThat(Qty.parse("+7")).isEqualTo(Qty.of(7L));
            assertThat(Qty.parse("  7.5  ").milli()).isEqualTo(7_500L);
        }

        @Test
        void allowsInsignificantTrailingZerosBeyondThreePlaces() {
            assertThat(Qty.parse("1.5000").milli()).isEqualTo(1_500L);
            assertThat(Qty.parse("1.500000").milli()).isEqualTo(1_500L);
        }

        @Test
        void rejectsSignificantDigitsBeyondThreePlaces() {
            assertThatExceptionOfType(QtyException.class)
                    .isThrownBy(() -> Qty.parse("1.5001"))
                    .withMessageContaining("more than 3 decimal places");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "", "   ", "abc", "1.2.3", "1,5", "--1", "1e3", "Infinity", "NaN",
            "0x10", "1 5", "+-1", ".", ".5", "5.", "١٢٣"
        })
        @DisplayName("rejects malformed input instead of coercing it")
        void rejectsMalformedInput(String bad) {
            assertThatExceptionOfType(QtyException.class).isThrownBy(() -> Qty.parse(bad));
        }

        @Test
        void rejectsNull() {
            assertThatExceptionOfType(QtyException.class).isThrownBy(() -> Qty.parse(null));
        }

        @Test
        void normalisesNegativeZero() {
            assertThat(Qty.parse("-0.000")).isEqualTo(Qty.ZERO);
            assertThat(Qty.parse("-0.000").milli()).isZero();
        }
    }

    @Nested
    @DisplayName("string round-trips")
    class RoundTrips {

        @ParameterizedTest
        @ValueSource(strings = {"0", "1", "0.001", "12.5", "-3.25", "999.999", "-0.001", "1.5"})
        void survivesParseThenSerialise(String input) {
            Qty original = Qty.parse(input);
            assertThat(Qty.parse(original.toPlainString())).isEqualTo(original);
            assertThat(Qty.of(original.toBigDecimal())).isEqualTo(original);
        }

        @Test
        void toPlainStringAlwaysEmitsThreeDecimals() {
            assertThat(Qty.of(1L).toPlainString()).isEqualTo("1.000");
            assertThat(Qty.parse("1.5").toPlainString()).isEqualTo("1.500");
            assertThat(Qty.parse("0.001").toPlainString()).isEqualTo("0.001");
            assertThat(Qty.parse("-2.25").toPlainString()).isEqualTo("-2.250");
            assertThat(Qty.ZERO.toPlainString()).isEqualTo("0.000");
        }

        @Test
        void toDisplayStringTrimsNoiseForHumans() {
            assertThat(Qty.of(2L).toDisplayString()).isEqualTo("2");
            assertThat(Qty.parse("2.5").toDisplayString()).isEqualTo("2.5");
            assertThat(Qty.parse("2.05").toDisplayString()).isEqualTo("2.05");
            assertThat(Qty.ZERO.toDisplayString()).isEqualTo("0");
            assertThat(Qty.parse("-1.5").toDisplayString()).isEqualTo("-1.5");
            assertThat(Qty.parse("1000").toDisplayString()).isEqualTo("1000");
        }

        @Test
        void toBigDecimalKeepsTheScaleTheDatabaseColumnUses() {
            assertThat(Qty.parse("1.5").toBigDecimal())
                    .isEqualTo(new BigDecimal("1.500"))
                    .hasScaleOf(Qty.DECIMALS);
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void addsAndSubtractsExactly() {
            assertThat(Qty.of(2L).plus(Qty.of(3L))).isEqualTo(Qty.of(5L));
            assertThat(Qty.of(5L).minus(Qty.of(3L))).isEqualTo(Qty.of(2L));
            assertThat(Qty.of(3L).minus(Qty.of(5L))).isEqualTo(Qty.of(-2L));
        }

        @Test
        void negatesAndTakesAbsoluteValue() {
            assertThat(Qty.of(5L).negated()).isEqualTo(Qty.of(-5L));
            assertThat(Qty.of(-5L).negated()).isEqualTo(Qty.of(5L));
            assertThat(Qty.ZERO.negated()).isEqualTo(Qty.ZERO);
            assertThat(Qty.of(-5L).abs()).isEqualTo(Qty.of(5L));
            assertThat(Qty.of(5L).abs()).isEqualTo(Qty.of(5L));
        }

        @Test
        void multipliesByWholeFactors() {
            assertThat(Qty.of(6L).times(12L)).isEqualTo(Qty.of(72L));
            assertThat(Qty.parse("0.5").times(3L)).isEqualTo(Qty.parse("1.5"));
            assertThat(Qty.of(5L).times(0L)).isEqualTo(Qty.ZERO);
        }

        @Test
        void sumsAnEmptyLedgerToZero() {
            assertThat(Qty.sum(List.of())).isEqualTo(Qty.ZERO);
        }

        @Test
        void throwsOnOverflowRatherThanWrappingAround() {
            // Silent wraparound would turn a huge receipt into a huge negative
            // balance. Fail instead.
            Qty huge = Qty.ofMilli(Qty.MAX_MILLI);
            assertThatExceptionOfType(QtyException.class)
                    .isThrownBy(() -> huge.plus(Qty.of(1L)));
            assertThatExceptionOfType(QtyException.class)
                    .isThrownBy(() -> huge.times(1_000_000L));
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        void ordersCorrectly() {
            assertThat(Qty.of(2L).isGreaterThan(Qty.of(1L))).isTrue();
            assertThat(Qty.of(1L).isLessThan(Qty.of(2L))).isTrue();
            assertThat(Qty.of(1L).isGreaterThanOrEqual(Qty.of(1L))).isTrue();
            assertThat(Qty.of(1L).isLessThanOrEqual(Qty.of(1L))).isTrue();
            assertThat(Qty.of(1L)).isLessThan(Qty.of(2L));
        }

        @Test
        void comparesFractionsThatDoublesGetWrong() {
            Qty sum = Qty.parse("0.1").plus(Qty.parse("0.2"));
            assertThat(sum.isGreaterThan(Qty.parse("0.3"))).isFalse();
            assertThat(sum.compareTo(Qty.parse("0.3"))).isZero();
        }

        @Test
        void picksMinAndMax() {
            assertThat(Qty.min(Qty.of(1L), Qty.of(2L))).isEqualTo(Qty.of(1L));
            assertThat(Qty.max(Qty.of(1L), Qty.of(2L))).isEqualTo(Qty.of(2L));
        }

        @Test
        void sortsNaturally() {
            List<String> sorted = Stream.of(
                            Qty.of(3L), Qty.parse("1.5"), Qty.of(-2L), Qty.ZERO)
                    .sorted()
                    .map(Qty::toDisplayString)
                    .toList();
            assertThat(sorted).containsExactly("-2", "0", "1.5", "3");
        }

        @Test
        void predicatesAgreeWithSign() {
            assertThat(Qty.ZERO.isZero()).isTrue();
            assertThat(Qty.ZERO.isPositive()).isFalse();
            assertThat(Qty.ZERO.isNegative()).isFalse();
            assertThat(Qty.of(1L).isPositive()).isTrue();
            assertThat(Qty.of(-1L).isNegative()).isTrue();
        }
    }

    @Test
    @DisplayName("DECIMALS and SCALE agree")
    void scaleInvariant() {
        assertThat(Qty.SCALE).isEqualTo((long) Math.pow(10, Qty.DECIMALS));
        assertThat(Qty.of(1L).milli()).isEqualTo(Qty.SCALE);
    }
}
