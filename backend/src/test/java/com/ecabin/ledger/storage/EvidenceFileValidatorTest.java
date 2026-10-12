package com.ecabin.ledger.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FileRules;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EvidenceFileValidatorTest {
    private static final FieldDefinition IMAGE = new FieldDefinition("evidence", "Evidence image", FieldType.IMAGE_UPLOAD,
        null, true, 1, null, null, null, null, null,
        new FileRules(List.of("jpg", "jpeg", "png", "webp"), List.of("image/jpeg", "image/png", "image/webp"), 15L * 1024 * 1024, 1, 5));

    @Test void imageBelowAndExactlyFiveMebibytesAreAccepted() {
        assertDoesNotThrow(() -> EvidenceFileValidator.validateSize(IMAGE, "png", 5L * 1024 * 1024 - 1));
        assertDoesNotThrow(() -> EvidenceFileValidator.validateSize(IMAGE, "png", 5L * 1024 * 1024));
    }

    @Test void imageAboveFiveMebibytesReturns413WithActionableMessage() {
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            () -> EvidenceFileValidator.validateSize(IMAGE, "png", 5L * 1024 * 1024 + 1));
        assertEquals(413,error.getStatusCode().value());
        assertTrue(error.getReason().contains("5 MB per-file limit"));
    }

    @Test void imageSignatureMustMatchAllowedExtensionAndMime() {
        byte[] png={(byte)137,80,78,71,13,10,26,10,0};
        assertEquals("image/png",EvidenceFileValidator.validateContent(IMAGE,"png",png));
        assertThrows(IllegalArgumentException.class,()->EvidenceFileValidator.validateContent(IMAGE,"png","not an image".getBytes()));
        assertThrows(IllegalArgumentException.class,()->EvidenceFileValidator.validateContent(IMAGE,"gif",png));
    }
}
