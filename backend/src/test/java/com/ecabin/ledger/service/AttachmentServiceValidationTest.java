package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class AttachmentServiceValidationTest {
    @Test
    void noEvidenceReturnsAnEmptyTenantScopedList() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BlobStorage storage = mock(BlobStorage.class);
        OperationalFormService forms = mock(OperationalFormService.class);
        UUID tenant = UUID.randomUUID();
        UUID defect = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<com.ecabin.ledger.api.FormModels.AttachmentView>>any(), any(Object[].class)))
            .thenAnswer(invocation -> {
                assertTrue(((String) invocation.getArgument(0)).contains("WHERE a.operator_id=? AND a.defect_id=?"));
                return List.of();
            });

        var result = new AttachmentService(jdbc, storage, forms).listForDefect(tenant, defect);

        assertTrue(result.isEmpty());
        verifyNoInteractions(storage);
    }

    @Test
    void assignedMaintenanceMemberCanDownloadEvidenceAfterCaseMovesToClosureOrClosedStage() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BlobStorage storage = mock(BlobStorage.class);
        OperationalFormService forms = mock(OperationalFormService.class);
        UUID tenant = UUID.randomUUID();
        UUID defect = UUID.randomUUID();
        UUID attachment = UUID.randomUUID();
        byte[] content = new byte[]{1, 2, 3};
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
            .thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                assertTrue(sql.contains("a.operator_id=? AND a.id=?"), "Evidence lookup must be tenant scoped.");
                assertTrue(sql.contains("maintenance_teams_mst"), "Maintenance access must still require active team membership.");
                assertFalse(sql.contains("d.status IN ('ACTION_ASSIGNED','IN_PROGRESS')"),
                    "Evidence read access must not expire when the case advances workflow stage.");
                RowMapper<Object> mapper = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("defect_id", UUID.class)).thenReturn(defect);
                when(rs.getString("object_key")).thenReturn("evidence/tenant/defect/file");
                when(rs.getString("original_filename")).thenReturn("evidence.png");
                when(rs.getString("detected_mime_type")).thenReturn("image/png");
                when(rs.getString("scan_status")).thenReturn("CLEAN");
                when(rs.getLong("size_bytes")).thenReturn((long) content.length);
                return java.util.List.of(mapper.mapRow(rs, 0));
            });
        when(storage.get("evidence/tenant/defect/file")).thenReturn(content);

        AttachmentService service = new AttachmentService(jdbc, storage, forms);
        var result = service.download(new AuthenticatedUser(tenant, "maintenance.user", "Maintenance", "MAINTENANCE"),
            true, false, attachment);

        assertArrayEquals(content, result.content());
        assertEquals(attachment, result.id());
    }

    @Test
    void evidenceMissingFromCurrentTenantRemainsNotFoundAndDoesNotReadBlob() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BlobStorage storage = mock(BlobStorage.class);
        OperationalFormService forms = mock(OperationalFormService.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
            .thenReturn(java.util.List.of());

        AttachmentService service = new AttachmentService(jdbc, storage, forms);
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.download(
            new AuthenticatedUser(UUID.randomUUID(), "maintenance.user", "Maintenance", "MAINTENANCE"),
            true, false, UUID.randomUUID()));

        assertEquals(404, error.getStatusCode().value());
        verifyNoInteractions(storage);
    }

    @Test
    void missingBlobIsReportedAsStorageFailureInsteadOfMissingEvidence() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BlobStorage storage = mock(BlobStorage.class);
        OperationalFormService forms = mock(OperationalFormService.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("defect_id", UUID.class)).thenReturn(UUID.randomUUID());
                when(rs.getString("object_key")).thenReturn("evidence/missing");
                when(rs.getString("original_filename")).thenReturn("evidence.png");
                when(rs.getString("detected_mime_type")).thenReturn("image/png");
                when(rs.getString("scan_status")).thenReturn("CLEAN");
                when(rs.getLong("size_bytes")).thenReturn(10L);
                return java.util.List.of(mapper.mapRow(rs, 0));
            });
        when(storage.get("evidence/missing")).thenThrow(new IllegalStateException("Evidence is temporarily unavailable."));

        AttachmentService service = new AttachmentService(jdbc, storage, forms);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.download(
            new AuthenticatedUser(UUID.randomUUID(), "maintenance.user", "Maintenance", "MAINTENANCE"),
            false, false, UUID.randomUUID()));
        assertEquals("Evidence is temporarily unavailable.", error.getMessage());
    }

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
        when(jdbc.query(contains("SELECT count(*) AS file_count"),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            RowMapper<?> mapper=invocation.getArgument(1);ResultSet rs=mock(ResultSet.class);
            when(rs.getLong("file_count")).thenReturn(0L);when(rs.getBigDecimal("total_bytes")).thenReturn(java.math.BigDecimal.ZERO);
            return List.of(mapper.mapRow(rs,0));
        });
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

    @Test
    void rejectsRepeatedUploadWhenExistingFieldTotalAlreadyReachedBeforeWritingBlob() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        BlobStorage storage=mock(BlobStorage.class);
        OperationalFormService forms=mock(OperationalFormService.class);
        UUID tenant=UUID.randomUUID(),defect=UUID.randomUUID(),form=UUID.randomUUID(),version=UUID.randomUUID();
        FieldDefinition field=new FieldDefinition("evidence","Evidence",FieldType.FILE_UPLOAD,null,true,1,null,null,null,null,null,
            new FileRules(List.of("png"),List.of("image/png"),20L*1024*1024,1,3,30L*1024*1024));
        when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenReturn("actor");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),any(Object[].class))).thenReturn(List.of(defect));
        when(jdbc.query(contains("SELECT count(*) AS file_count"),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            RowMapper<?> mapper=invocation.getArgument(1);ResultSet rs=mock(ResultSet.class);
            when(rs.getLong("file_count")).thenReturn(2L);
            when(rs.getBigDecimal("total_bytes")).thenReturn(java.math.BigDecimal.valueOf(30L*1024*1024));
            return List.of(mapper.mapRow(rs,0));
        });
        when(forms.requireUploadField(tenant,form,version,"evidence",defect)).thenReturn(field);
        BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",output));
        AttachmentService service=new AttachmentService(jdbc,storage,forms);

        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->service.upload(
            new AuthenticatedUser(tenant,"actor","Actor","REPORTER"),false,false,defect,form,version,"evidence",
            new MockMultipartFile("file","extra.png","image/png",output.toByteArray())));

        assertTrue(error.getMessage().contains("30 MB"));
        verifyNoInteractions(storage);
    }
}
