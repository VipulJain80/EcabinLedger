package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
    void configurationCsvRequiresStrictUtf8AndRejectsExecutableBinaryBytes() {
        MockMultipartFile valid = new MockMultipartFile("file", "areas.csv", "application/octet-stream",
            "area_code,area_name,shortcode,description\nCABIN,Cabin,CAB,Main cabin\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(imports.decode(valid).startsWith("area_code"));

        MockMultipartFile executable = new MockMultipartFile("file", "renamed.exe.csv", "text/csv",
            new byte[]{'M', 'Z', 0, 0, (byte) 0xff, 1, 2});
        assertThrows(IllegalArgumentException.class, () -> imports.decode(executable));

        MockMultipartFile invalidUtf8 = new MockMultipartFile("file", "areas.csv", "text/csv",
            new byte[]{'a', ',', (byte) 0xc3, 0x28});
        assertThrows(IllegalArgumentException.class, () -> imports.decode(invalidUtf8));
    }

    @Test
    void importCsvRowLimitIsAppliedWhileParsing() {
        var error = assertThrows(ResponseStatusException.class,
            () -> imports.parse("header\none\ntwo\nthree\n", 2));
        assertEquals(413, error.getStatusCode().value());
        assertEquals(3, imports.parse("header\none\ntwo\n", 2).size());
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
