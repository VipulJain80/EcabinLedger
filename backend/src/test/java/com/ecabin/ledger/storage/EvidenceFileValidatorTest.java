package com.ecabin.ledger.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FileRules;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EvidenceFileValidatorTest {
    private static final FieldDefinition IMAGE = field(FieldType.IMAGE_UPLOAD,
        List.of("jpg", "jpeg", "png", "webp"), List.of("image/jpeg", "image/png", "image/webp"));
    private static final FieldDefinition FILE = field(FieldType.FILE_UPLOAD,
        List.of("jpg", "jpeg", "png", "webp", "pdf"), List.of("image/jpeg", "image/png", "image/webp", "application/pdf"));
    private static final byte[] EXE = {'M', 'Z', 0, 0, 1, 2, 3, 4, 5};

    @Test void imageBelowAndExactlyFiveMebibytesAreAcceptedBySizePolicy() {
        assertDoesNotThrow(() -> EvidenceFileValidator.validateSize(IMAGE, "png", 5L * 1024 * 1024 - 1));
        assertDoesNotThrow(() -> EvidenceFileValidator.validateSize(IMAGE, "png", 5L * 1024 * 1024));
    }

    @Test void imageAboveFiveMebibytesReturns413WithActionableMessage() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> EvidenceFileValidator.validateSize(IMAGE, "png", 5L * 1024 * 1024 + 1));
        assertEquals(413, error.getStatusCode().value());
        assertTrue(error.getReason().contains("5 MB per-file limit"));
    }

    @Test void decodesEverySupportedEvidenceFormatAsItsClaimedFormat() throws Exception {
        assertEquals("image/png", EvidenceFileValidator.validateContent(IMAGE, "png", image("png")));
        assertEquals("image/jpeg", EvidenceFileValidator.validateContent(IMAGE, "jpg", image("jpg")));
        assertEquals("image/webp", EvidenceFileValidator.validateContent(IMAGE, "webp", webpFixture()));
        assertEquals("application/pdf", EvidenceFileValidator.validateContent(FILE, "pdf", pdfFixture()));
    }

    @Test void rejectsExecutableBytesRenamedToEveryAllowedEvidenceExtension() {
        for (String extension : List.of("jpg", "png", "webp", "pdf")) {
            FieldDefinition field = extension.equals("pdf") ? FILE : IMAGE;
            assertThrows(IllegalArgumentException.class,
                () -> EvidenceFileValidator.validateContent(field, extension, EXE), extension);
        }
    }

    @Test void rejectsMismatchedImageAndPdfExtensions() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(FILE, "pdf", image("jpg")));
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(IMAGE, "jpg", image("png")));
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(FILE, "csv", pdfFixture()));
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(FILE, "exe", EXE));
    }

    @Test void rejectsMalformedTruncatedAndOversizedDimensionImages() throws Exception {
        byte[] valid = image("png");
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(IMAGE, "png", "not an image".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(IMAGE, "png", java.util.Arrays.copyOf(valid, 12)));
        byte[] hugeHeader = valid.clone();
        hugeHeader[16] = 0x7f; hugeHeader[17] = (byte) 0xff; hugeHeader[18] = (byte) 0xff; hugeHeader[19] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(IMAGE, "png", hugeHeader));
    }

    @Test void rejectsMalformedAndTruncatedPdfContent() throws Exception {
        byte[] valid = pdfFixture();
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(FILE, "pdf", "%PDF-1.7\n%%EOF".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(FILE, "pdf", java.util.Arrays.copyOf(valid, valid.length - 10)));
    }

    @Test void enforcesMIMEAndExtensionAllowlistWithoutUsingClientMime() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(IMAGE, "gif", image("png")));
        assertThrows(IllegalArgumentException.class, () -> EvidenceFileValidator.validateContent(field(FieldType.IMAGE_UPLOAD,
            List.of("png"), List.of("application/pdf")), "png", image("png")));
        // Multipart Content-Type is not an argument to the validator; actual bytes and field rules determine acceptance.
        assertEquals("image/png", EvidenceFileValidator.validateContent(IMAGE, "png", image("png")));
    }

    private static FieldDefinition field(FieldType type, List<String> extensions, List<String> mimes) {
        return new FieldDefinition("evidence", "Evidence", type, null, true, 1, null, null, null, null, null,
            new FileRules(extensions, mimes, 15L * 1024 * 1024, 0, 5));
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }

    private static byte[] webpFixture() {
        return Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    }

    private static byte[] pdfFixture() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
