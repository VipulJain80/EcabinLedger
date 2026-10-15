package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationImportServiceTest {
    private final ConfigurationImportService imports = new ConfigurationImportService(null, new ObjectMapper(), null);

    @Test
    void parsesQuotedCommasEscapedQuotesAndBlankLines() {
        assertEquals(List.of(List.of("name", "description"), List.of("Galley, forward", "Latch \"A\"")),
            imports.parse("name,description\r\n\"Galley, forward\",\"Latch \"\"A\"\"\"\r\n\r\n"));
    }

    @Test
    void parsesNewlinesInsideQuotedCellsAsOneCsvRecord() {
        assertEquals(List.of(List.of("name", "description"), List.of("Oven", "First line\nsecond line")),
            imports.parse("name,description\nOven,\"First line\nsecond line\"\n"));
    }

    @Test
    void rejectsMalformedQuoteSequences() {
        assertThrows(IllegalArgumentException.class, () -> imports.parse("name,description\nOven,\"unfinished"));
        assertThrows(IllegalArgumentException.class, () -> imports.parse("name,description\nOv\"en,test"));
        assertThrows(IllegalArgumentException.class, () -> imports.parse("name,description\n\"closed\"x,test"));
    }

    @Test
    void allMasterTemplatesAreBackendDefinedWithAHeaderAndSampleRow() {
        for (String key : List.of("fleets", "aircraft", "areas", "defect-categories", "defect-conditions", "components", "maintenance-teams", "users", "menu-items", "roles", "severities")) {
            var template = imports.template(key);
            assertEquals(key, template.resource());
            assertTrue(template.sample().startsWith("\"" + template.headers().get(0) + "\""));
            assertEquals(2, template.sample().lines().count());
        }
        assertThrows(IllegalArgumentException.class, () -> imports.template("cabin-zones"));
        assertEquals(List.of("area_code", "area_name", "shortcode", "description"), imports.template("areas").headers());
        assertTrue(imports.template("areas").sample().contains("GLY"));
    }

    @Test
    void organizationsCannotRequestAnImportTemplateBecauseOnboardingOwnsProvisioning() {
        var error = assertThrows(IllegalArgumentException.class, () -> imports.template("organizations"));
        assertTrue(error.getMessage().contains("operator onboarding"));
    }
}
