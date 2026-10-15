package com.ecabin.ledger.service;

import java.util.Locale;

final class AreaShortcodeRules {
    private AreaShortcodeRules() {}

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9]{2,10}"))
            throw new IllegalArgumentException("Shortcode is required and must contain 2–10 letters or digits (A–Z, 0–9).");
        return value;
    }
}
