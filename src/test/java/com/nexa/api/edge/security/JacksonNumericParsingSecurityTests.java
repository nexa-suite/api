package com.nexa.api.edge.security;

import org.junit.jupiter.api.Test;

import javax.xml.datatype.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonNumericParsingSecurityTests {
    private static final String OVERSIZED_DURATION = "\"P" + "9".repeat(1001) + "Y\"";

    @Test
    void legacyJacksonRejectsOversizedNumbersInsideXmlDurationStrings() throws Exception {
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        assertEquals("P1Y", mapper.readValue("\"P1Y\"", Duration.class).toString());
        var failure = assertThrows(Exception.class,
                () -> mapper.readValue(OVERSIZED_DURATION, Duration.class));
        assertTrue(hasCause(failure, com.fasterxml.jackson.core.exc.StreamConstraintsException.class));
    }

    @Test
    void currentJacksonRejectsOversizedNumbersInsideXmlDurationStrings() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals("P1Y", mapper.readValue("\"P1Y\"", Duration.class).toString());
        var failure = assertThrows(Exception.class,
                () -> mapper.readValue(OVERSIZED_DURATION, Duration.class));
        assertTrue(hasCause(failure, tools.jackson.core.exc.StreamConstraintsException.class));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> expected) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (expected.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
