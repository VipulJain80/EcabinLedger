package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FileRules;
import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.FormForArea;
import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.WorkflowStage;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.storage.BlobStorage;
import java.util.List;
import java.util.UUID;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;

class AttachmentServiceValidationTest {
    @Test
    void rejectsRenamedExecutableBeforeItReachesStorageEvenWithSpoofedMimeType() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BlobStorage storage = mock(BlobStorage.class);
        OperationalFormService forms = mock(OperationalFormService.class);
        UUID tenant = UUID.randomUUID();
        UUID defect = UUID.randomUUID();
        UUID form = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("actor");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(), any(Object[].class)))
            .thenReturn(List.of(defect));
        when(forms.requireUploadField(tenant, form, version, "photo", defect)).thenReturn(new FieldDefinition(
            "photo", "Photo", FieldType.IMAGE_UPLOAD, null, false, 1, null, null, null, null, null,
            new FileRules(List.of("jpg", "png", "webp"), List.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024, 0, 5)));
        AttachmentService service = new AttachmentService(jdbc, storage, forms);
        MockMultipartFile executable = new MockMultipartFile("file", "payload.jpg", "image/jpeg",
            new byte[]{'M', 'Z', 0, 0, 1, 2, 3});

        assertThrows(IllegalArgumentException.class, () -> service.upload(
            new AuthenticatedUser(tenant, "actor", "Actor", "REPORTER"), false, false,
            defect, form, version, "photo", executable));
        verifyNoInteractions(storage);
    }

    @Test
    void ignoresSpoofedBrowserMimeAndStoresAValidImageUsingDetectedType() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BlobStorage storage = mock(BlobStorage.class);
        OperationalFormService forms = mock(OperationalFormService.class);
        UUID tenant = UUID.randomUUID();
        UUID defect = UUID.randomUUID();
        UUID form = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        FieldDefinition field = new FieldDefinition("photo", "Photo", FieldType.IMAGE_UPLOAD, null, false, 1,
            null, null, null, null, null,
            new FileRules(List.of("png"), List.of("image/png"), 5L * 1024 * 1024, 0, 5));
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("actor");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(), any(Object[].class)))
            .thenReturn(List.of(defect));
        when(forms.requireUploadField(tenant, form, version, "photo", defect)).thenReturn(field);
        when(forms.requireOperationalForm(tenant, defect, form, version)).thenReturn(new FormForArea(
            form, "TEST", "Test", null, FormPurpose.DEFECT_REPORT, WorkflowStage.INITIAL_REPORT,
            version, 1, new FormConfiguration(List.of(field))));
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        AttachmentService service = new AttachmentService(jdbc, storage, forms);

        service.upload(new AuthenticatedUser(tenant, "actor", "Actor", "REPORTER"), false, false,
            defect, form, version, "photo", new MockMultipartFile("file", "photo.png", "application/x-msdownload", output.toByteArray()));

        verify(storage).put(org.mockito.ArgumentMatchers.startsWith("evidence/"),
            org.mockito.ArgumentMatchers.any(byte[].class), eq("image/png"));
    }
}
