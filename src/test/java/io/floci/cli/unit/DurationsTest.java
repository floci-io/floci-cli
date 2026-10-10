package io.floci.cli.unit;

import io.floci.cli.util.Durations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** BL-040: durations parse the documented forms and reject everything else with a usable message. */
class DurationsTest {

    @Test
    void parsesEveryDocumentedUnit() {
        assertEquals(500, Durations.parseDuration("500ms"));
        assertEquals(90_000, Durations.parseDuration("90s"));
        assertEquals(120_000, Durations.parseDuration("2m"));
        assertEquals(3_600_000, Durations.parseDuration("1h"));
        assertEquals(45_000, Durations.parseDuration("45"));
        assertEquals(30_000, Durations.parseDuration(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "10x", "s", "1.5s"})
    void rejectsWhatItCannotRead(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Durations.parseDuration(value));
        assertTrue(e.getMessage().contains("Invalid duration"), e.getMessage());
    }

    @ParameterizedTest
    // The last one overflows to +1000 ms when multiplied before the check.
    @ValueSource(strings = {"0s", "0", "-1s", "-5", "-9223372036854775807s"})
    void rejectsDurationsThatAreNotPositive(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Durations.parseDuration(value));
        assertTrue(e.getMessage().contains("greater than zero"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775807s", "9223372036854775807", "9223372036854775m", "2562047788016h"})
    void rejectsDurationsTooLongToRepresent(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Durations.parseDuration(value));
        assertTrue(e.getMessage().contains("too long"), e.getMessage());
    }

    @Test
    void theLargestMillisecondValueStillParses() {
        assertEquals(Long.MAX_VALUE, Durations.parseDuration("9223372036854775807ms"));
    }
}
