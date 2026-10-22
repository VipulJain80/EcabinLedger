package com.ecabin.ledger.storage;

import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Server-side evidence limits and full format parsing for supported evidence types. */
public final class EvidenceFileValidator {
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_NON_IMAGE_BYTES = 15L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 12_000_000L;
    private static final int MAX_IMAGE_DIMENSION = 12_000;
    private static final long MAX_ANIMATED_IMAGE_PIXELS = 40_000_000L;
    private static final int MAX_IMAGE_FRAMES = 100;
    private static final int MAX_PDF_PAGES = 500;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    private EvidenceFileValidator() {}

    public static boolean isImageUpload(FieldDefinition field, String extension) {
        return field.type() == FieldType.IMAGE_UPLOAD || (extension != null && IMAGE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT)));
    }

    public static long maxBytes(FieldDefinition field, String extension) {
        long configured = field.fileRules().maxFileSizeBytes();
        return isImageUpload(field, extension) ? Math.min(configured, MAX_IMAGE_BYTES) : Math.min(configured, MAX_NON_IMAGE_BYTES);
    }

    public static void validateSize(FieldDefinition field, String extension, long size) {
        boolean image = isImageUpload(field, extension);
        if (size == 0) throw new IllegalArgumentException("Choose a non-empty evidence file.");
        if (size > maxBytes(field, extension)) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, image
                ? "This image exceeds the 5 MB per-file limit. Please choose a smaller image."
                : "The file exceeds the configured per-file limit.");
        }
    }

    public static String validateContent(FieldDefinition field, String extension, byte[] bytes) {
        String expectedMime = mimeForExtension(extension);
        if (extension == null || field.fileRules().extensions().stream().noneMatch(value -> value.equalsIgnoreCase(extension)))
            throw new IllegalArgumentException("This file extension is not allowed for the selected field.");
        if (expectedMime.isEmpty() || field.fileRules().mimeTypes().stream().noneMatch(value -> value.equalsIgnoreCase(expectedMime))
            || (field.type() == FieldType.IMAGE_UPLOAD && !expectedMime.startsWith("image/")))
            throw new IllegalArgumentException("This file type is not allowed for the selected field.");

        try {
            switch (expectedMime) {
                case "image/png", "image/jpeg", "image/webp" -> validateImage(bytes, expectedMime);
                case "application/pdf" -> validatePdf(bytes);
                default -> throw invalidContent();
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidContent();
        }
        if (expectedMime.startsWith("image/") && bytes.length > MAX_IMAGE_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "This image exceeds the 5 MB per-file limit. Please choose a smaller image.");
        return expectedMime;
    }

    private static void validateImage(byte[] bytes, String expectedMime) throws IOException {
        if (bytes == null || bytes.length == 0 || beginsWithWindowsExecutable(bytes)) throw invalidContent();
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidContent();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                if (!imageFormatMatches(reader.getFormatName(), expectedMime)) throw invalidContent();
                int frameCount = reader.getNumImages(true);
                if (frameCount < 1 || frameCount > MAX_IMAGE_FRAMES) throw invalidContent();
                long decodedPixels = 0;
                for (int frame = 0; frame < frameCount; frame++) {
                    int width = reader.getWidth(frame);
                    int height = reader.getHeight(frame);
                    long pixels = (long) width * height;
                    if (width < 1 || height < 1 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                        || pixels > MAX_IMAGE_PIXELS || decodedPixels + pixels > MAX_ANIMATED_IMAGE_PIXELS) throw invalidContent();
                    BufferedImage decoded = reader.read(frame);
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw invalidContent();
                    decodedPixels += pixels;
                    decoded.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean imageFormatMatches(String format, String mime) {
        String normalized = format.toUpperCase(Locale.ROOT);
        return switch (mime) {
            case "image/png" -> normalized.equals("PNG");
            case "image/jpeg" -> normalized.equals("JPEG") || normalized.equals("JPG");
            case "image/webp" -> normalized.equals("WEBP");
            default -> false;
        };
    }

    private static void validatePdf(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 10 || beginsWithWindowsExecutable(bytes)
            || !startsWith(bytes, "%PDF-") || !endsWithPdfEof(bytes)) throw invalidContent();
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(bytes), IOUtils.createTempFileOnlyStreamCache())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1 || pageCount > MAX_PDF_PAGES) throw invalidContent();
            for (var page : document.getPages()) {
                // Force parsing/decompression of page content streams so truncated streams are rejected before storage.
                PDFStreamParser parser = new PDFStreamParser(page);
                parser.parse();
            }
        }
    }

    private static boolean endsWithPdfEof(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && (bytes[end - 1] == ' ' || bytes[end - 1] == '\t' || bytes[end - 1] == '\r' || bytes[end - 1] == '\n' || bytes[end - 1] == '\f')) end--;
        byte[] marker = "%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (end < marker.length) return false;
        for (int i = 0; i < marker.length; i++) if (bytes[end - marker.length + i] != marker[i]) return false;
        return true;
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        byte[] expected = prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (bytes.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (bytes[i] != expected[i]) return false;
        return true;
    }

    private static boolean beginsWithWindowsExecutable(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z';
    }

    private static String mimeForExtension(String ext) {
        if (ext == null) return "";
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            default -> "";
        };
    }

    private static IllegalArgumentException invalidContent() {
        return new IllegalArgumentException("The file is malformed or its content does not match an allowed file type.");
    }
}
