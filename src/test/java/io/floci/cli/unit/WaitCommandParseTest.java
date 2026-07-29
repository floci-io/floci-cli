package io.floci.cli.unit;

import io.floci.cli.util.Durations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WaitCommandParseTest {

    @ParameterizedTest
    @CsvSource({
            "30s,  30000",
            "2m,   120000",
            "1h,   3600000",
            "500ms, 500",
            "10,   10000"
    })
    void testDurationParsing(String input, long expectedMillis) {
        assertEquals(expectedMillis, Durations.parseDuration(input.trim()));
    }

    @Test
    void testNullDuration() {
        assertEquals(30_000, Durations.parseDuration(null));
    }
}
