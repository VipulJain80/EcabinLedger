package com.ecabin.ledger.storage;

import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared server-side evidence limits and content-signature checks. */
public final class EvidenceFileValidator {
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_NON_IMAGE_BYTES = 15L * 1024 * 1024;
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
        String detected = detect(bytes);
        if (extension == null || field.fileRules().extensions().stream().noneMatch(value -> value.equalsIgnoreCase(extension)))
            throw new IllegalArgumentException("This file extension is not allowed for the selected field.");
        if (detected == null || (field.type() == FieldType.IMAGE_UPLOAD && !detected.startsWith("image/"))
            || field.fileRules().mimeTypes().stream().noneMatch(value -> value.equalsIgnoreCase(detected)) || !mimeForExtension(extension).equals(detected))
            throw new IllegalArgumentException("The file content does not match an allowed file type.");
        if (detected.startsWith("image/") && bytes.length > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "This image exceeds the 5 MB per-file limit. Please choose a smaller image.");
        }
        return detected;
    }

    private static String mimeForExtension(String ext) { return switch (ext.toLowerCase(Locale.ROOT)) { case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg"; case "webp" -> "image/webp"; case "pdf" -> "application/pdf"; default -> ""; }; }
    private static String detect(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0]&255)==137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71 && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10) return "image/png";
        if (bytes.length >= 3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0]=='R' && bytes[1]=='I' && bytes[2]=='F' && bytes[3]=='F' && bytes[8]=='W' && bytes[9]=='E' && bytes[10]=='B' && bytes[11]=='P') return "image/webp";
        if (bytes.length >= 5 && bytes[0]=='%' && bytes[1]=='P' && bytes[2]=='D' && bytes[3]=='F' && bytes[4]=='-') return "application/pdf";
        return null;
    }
}
