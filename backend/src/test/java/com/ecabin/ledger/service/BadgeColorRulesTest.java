package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BadgeColorRulesTest {
    @Test
    void acceptsTheSeededSolidBadgePalettes() {
        assertDoesNotThrow(() -> BadgeColorRules.requireAccessible("#B91C1C", "#FFFFFF", "#991B1B", "SOLID"));
        assertDoesNotThrow(() -> BadgeColorRules.requireAccessible("#FDE68A", "#713F12", "#D97706", "SOLID"));
        assertDoesNotThrow(() -> BadgeColorRules.requireAccessible("#2563EB", "#0F172A", "#1D4ED8", "SOFT"));
    }

    @Test
    void rejectsLowContrastTextAndOutlineBorder() {
        assertThrows(IllegalArgumentException.class,
            () -> BadgeColorRules.requireAccessible("#FFFFFF", "#EEEEEE", "#DDDDDD", "SOLID"));
        assertThrows(IllegalArgumentException.class,
            () -> BadgeColorRules.requireAccessible("#FFFFFF", "#111111", "#EEEEEE", "OUTLINE"));
    }
}
