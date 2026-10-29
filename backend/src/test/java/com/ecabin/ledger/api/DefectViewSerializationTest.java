package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefectViewSerializationTest {
    @Test
    void defectDetailsResponseRetainsZoneAndHistoricalSeatReferenceProperties() throws Exception {
        ApiModels.DefectView view = new ApiModels.DefectView(
            UUID.randomUUID(), "DEF-1", "N123AB", "A320", "CABIN", "CABIN", "12A", 12,
            "12A-L", "Seat assembly", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Loose",
            "Seat issue", "Description", "MINOR", "Minor", "REPORTED", "reporter", null,
            null, null, Instant.parse("2026-10-29T10:00:00Z"), Instant.parse("2026-10-29T10:00:00Z"),
            null, null, null);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsBytes(view));

        assertEquals("12A", json.get("zone").asText());
        assertEquals("12A-L", json.get("seatReference").asText());
    }
}
