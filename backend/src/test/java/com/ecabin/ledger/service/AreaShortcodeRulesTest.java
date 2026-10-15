package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AreaShortcodeRulesTest {
    @Test
    void trimsAndNormalizesShortcodesToUppercase() {
        assertEquals("GLY", AreaShortcodeRules.normalize(" gly "));
        assertEquals("A1", AreaShortcodeRules.normalize("a1"));
        assertEquals("CABINAREA1", AreaShortcodeRules.normalize("cabinarea1"));
    }

    @Test
    void requiresTwoToTenAsciiLettersOrDigits() {
        for (String value : new String[]{null, "", "A", "CABINAREA10", "A-B", "A_B", "ÅREA"})
            assertThrows(IllegalArgumentException.class, () -> AreaShortcodeRules.normalize(value));
    }
}
