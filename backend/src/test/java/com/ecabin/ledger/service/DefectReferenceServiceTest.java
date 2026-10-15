package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DefectReferenceServiceTest {
    @Test
    void formatsReferencesWithAStableDateShortcodeAndAtLeastTwoSequenceDigits() {
        LocalDate date = LocalDate.of(2026, 10, 15);
        assertEquals("20261015-CBN-01", DefectReferenceService.format(date, "CBN", 1));
        assertEquals("20261015-GLY-02", DefectReferenceService.format(date, "GLY", 2));
        assertEquals("20261015-CBN-100", DefectReferenceService.format(date, "CBN", 100));
        assertEquals("20261016-CBN-01", DefectReferenceService.format(date.plusDays(1), "CBN", 1));
    }

    @Test
    void refusesInvalidShortcodesAndSequences() {
        LocalDate date = LocalDate.of(2026, 10, 15);
        assertThrows(IllegalArgumentException.class, () -> DefectReferenceService.format(date, "galley", 1));
        assertThrows(IllegalArgumentException.class, () -> DefectReferenceService.format(date, "G", 1));
        assertThrows(IllegalArgumentException.class, () -> DefectReferenceService.format(date, "GLY", 0));
    }
}
