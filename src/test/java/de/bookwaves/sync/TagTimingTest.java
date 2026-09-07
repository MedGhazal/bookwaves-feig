package de.bookwaves.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagTimingTest {

    @Test
    @DisplayName("an unset value keeps the one second default")
    void unsetKeepsDefault() {
        assertEquals(10, TagTiming.TRANSPONDER_VALID_TIME.steps(null));
        assertEquals(200, TagTiming.PERSISTENCE_RESET_TIME.steps(null));
        assertEquals(10, TagTiming.TRANSPONDER_VALID_TIME.steps("  "));
    }

    @Test
    @DisplayName("milliseconds become the step each parameter is counted in")
    void convertsMillisecondsToSteps() {
        assertEquals(10, TagTiming.TRANSPONDER_VALID_TIME.steps("1000"));
        assertEquals(55, TagTiming.TRANSPONDER_VALID_TIME.steps("5500"));
        assertEquals(200, TagTiming.PERSISTENCE_RESET_TIME.steps("1000"));
        assertEquals(40, TagTiming.PERSISTENCE_RESET_TIME.steps("200"));
        assertEquals(0, TagTiming.TRANSPONDER_VALID_TIME.steps("0"));
    }

    @Test
    @DisplayName("never is stored as the value the reader reads as never")
    void neverIsStoredAsItsOwnValue() {
        assertEquals(65535, TagTiming.TRANSPONDER_VALID_TIME.steps("never"));
        assertEquals(65535, TagTiming.PERSISTENCE_RESET_TIME.steps("NEVER"));
    }

    @Test
    @DisplayName("a duration the reader cannot count in whole steps is refused")
    void refusesPartialSteps() {
        assertFalse(TagTiming.TRANSPONDER_VALID_TIME.isSupported("150"));
        assertFalse(TagTiming.PERSISTENCE_RESET_TIME.isSupported("7"));
        assertThrows(IllegalArgumentException.class,
            () -> TagTiming.PERSISTENCE_RESET_TIME.steps("7"));
    }

    @Test
    @DisplayName("a duration past what the reader can store is refused")
    void refusesOutOfRange() {
        assertTrue(TagTiming.TRANSPONDER_VALID_TIME.isSupported("6553400"));
        assertFalse(TagTiming.TRANSPONDER_VALID_TIME.isSupported("6553500"));
        assertTrue(TagTiming.PERSISTENCE_RESET_TIME.isSupported("327670"));
        assertFalse(TagTiming.PERSISTENCE_RESET_TIME.isSupported("327675"));
    }

    @Test
    @DisplayName("anything that is not a millisecond count or never is refused")
    void refusesNonsense() {
        assertFalse(TagTiming.TRANSPONDER_VALID_TIME.isSupported("-100"));
        assertFalse(TagTiming.TRANSPONDER_VALID_TIME.isSupported("1s"));
        assertFalse(TagTiming.TRANSPONDER_VALID_TIME.isSupported("forever"));
    }

    @Test
    @DisplayName("the refusal names the configuration key and what it accepts")
    void refusalNamesKeyAndRange() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> TagTiming.TRANSPONDER_VALID_TIME.steps("150"));

        assertTrue(e.getMessage().contains("transponderValidTime"), e.getMessage());
        assertTrue(e.getMessage().contains("100"), e.getMessage());
    }

    @Test
    @DisplayName("each parameter names its own key and range")
    void describesItself() {
        assertEquals("transponderValidTime", TagTiming.TRANSPONDER_VALID_TIME.key());
        assertEquals("persistenceResetTime", TagTiming.PERSISTENCE_RESET_TIME.key());
        assertTrue(TagTiming.PERSISTENCE_RESET_TIME.supportedValues().contains("327670"));
    }
}
