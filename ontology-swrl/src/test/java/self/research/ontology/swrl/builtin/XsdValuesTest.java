package self.research.ontology.swrl.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Date, time and duration values used by the corrected functions")
class XsdValuesTest {

    @ParameterizedTest
    @CsvSource({"0,P0M", "18,P1Y6M", "12,P1Y", "8,P8M", "-10,-P10M", "26,P2Y2M"})
    void yearMonthDurationsAreWrittenInTheShortestForm(long months, String expected) {
        assertEquals(expected, XsdValues.formatYearMonth(months));
    }

    @ParameterizedTest
    @CsvSource({"0,PT0S", "86400,P1D", "43200,PT12H", "-43200,-PT12H", "131400,P1DT12H30M", "90.5,PT1M30.5S", "172800,P2D", "3661,PT1H1M1S"})
    void dayTimeDurationsAreWrittenInTheShortestForm(String seconds, String expected) {
        assertEquals(expected, XsdValues.formatDayTime(new BigDecimal(seconds)));
    }

    @ParameterizedTest
    @CsvSource({"P1Y6M,18,0", "-P1Y,-12,0", "P1DT12H,0,129600", "PT0.5S,0,0.5", "P2D,0,172800", "-PT30M,0,-1800"})
    void durationsAreReadBack(String text, long months, String seconds) {
        XsdValues.Duration d = XsdValues.parseDuration(text);
        assertEquals(months, d.months());
        assertEquals(0, new BigDecimal(seconds).compareTo(d.seconds()), text);
    }

    @ParameterizedTest
    @CsvSource({"P", "PT", "1Y", "P1H", "P1Y2", "hello", "''"})
    void textThatIsNotADurationIsRefused(String text) {
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseDuration(text));
    }

    @Test
    void datesKeepTheirZoneAndRefuseDaysThatDoNotExist() {
        assertEquals("2024-03-15", XsdValues.parseDate("2024-03-15").format());
        assertEquals("2024-03-15+05:30", XsdValues.parseDate("2024-03-15+05:30").format());
        assertEquals("2024-03-15Z", XsdValues.parseDate("2024-03-15+00:00").format());
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseDate("2023-02-29"));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseDate("2024-13-01"));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseDate("15/03/2024"));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseDate("2024-03-15+15:00"));
    }

    @Test
    void timesKeepFractionsAndRefuseValuesOutOfRange() {
        assertEquals("10:30:00", XsdValues.parseTime("10:30:00").format());
        assertEquals("10:30:00.25Z", XsdValues.parseTime("10:30:00.250Z").format());
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseTime("25:00:00"));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseTime("10:60:00"));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.parseTime("10:30"));
    }

    @Test
    void timeOfDayWrapsAroundMidnight() {
        assertEquals("01:15:00", XsdValues.formatTime(XsdValues.wrap(new BigDecimal(23 * 3600 + 15 * 60 + 2 * 3600))));
        assertEquals("21:15:00", XsdValues.formatTime(XsdValues.wrap(new BigDecimal(23 * 3600 + 15 * 60 - 2 * 3600))));
        assertEquals("23:00:00", XsdValues.formatTime(XsdValues.wrap(new BigDecimal(-3600))));
        assertEquals("00:00:00", XsdValues.formatTime(XsdValues.wrap(new BigDecimal(86400))));
    }

    @Test
    void zonesAreComparedOnTheSameClock() {
        assertEquals(0, XsdValues.parseTime("11:30:00+02:00").utcSeconds().compareTo(XsdValues.parseTime("09:30:00Z").utcSeconds()));
        assertEquals(XsdValues.parseDateTime("2024-03-15T10:30:00Z").utc(), XsdValues.parseDateTime("2024-03-15T12:30:00+02:00").utc());
    }

    @Test
    void dateTimesKeepTheirTimeOfDay() {
        assertEquals("2024-12-25T08:05:03Z", XsdValues.parseDateTime("2024-12-25T08:05:03Z").format());
        assertEquals("2024-12-25T08:05:03.5", XsdValues.parseDateTime("2024-12-25T08:05:03.50").format());
        assertEquals("2024-12-25T00:00:00", XsdValues.parseDateOrDateTime("2024-12-25").format());
    }

    @Test
    void constructorsRefuseValuesThatAreNotRealDatesAndTimes() {
        assertEquals("2024-02-29", XsdValues.buildDate(2024, 2, 29, "").format());
        assertThrows(IllegalArgumentException.class, () -> XsdValues.buildDate(2023, 2, 29, ""));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.buildDate(2024, 0, 1, ""));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.buildDate(2024, 1, 1, "somewhere"));
        assertEquals("23:59:59.5+05:30", XsdValues.buildTime(23, 59, new BigDecimal("59.5"), "+05:30").format());
        assertThrows(IllegalArgumentException.class, () -> XsdValues.buildTime(24, 0, BigDecimal.ZERO, ""));
        assertThrows(IllegalArgumentException.class, () -> XsdValues.buildTime(1, 0, new BigDecimal("60"), ""));
    }

    @Test
    void halfwayValuesRoundTowardsPositiveInfinity() {
        assertEquals(new BigDecimal("3"), XsdValues.roundHalfUp(new BigDecimal("2.5")));
        assertEquals(new BigDecimal("-3"), XsdValues.roundHalfUp(new BigDecimal("-3.5")));
        assertEquals(new BigDecimal("-2"), XsdValues.roundHalfUp(new BigDecimal("-2.5")));
        assertEquals(new BigDecimal("3"), XsdValues.roundHalfUp(new BigDecimal("2.6")));
        assertEquals(new BigDecimal("2"), XsdValues.roundHalfUp(new BigDecimal("2.4")));
    }
}
