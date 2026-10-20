package com.ecabin.ledger.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class EmailOutboxServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<VerifiedEmailDirectory> provider = mock(ObjectProvider.class);
    private final EmailOutboxService service = new EmailOutboxService(jdbc, provider);
    private final UUID tenant = UUID.randomUUID();
    private final UUID defect = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    @Test
    void disabledSettingsDoNotResolveRecipientsOrInsertJobs() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        assertEquals(0, service.enqueue(tenant, defect, event, "actor", "DEFECT_REPORTED", null, null));
        verify(provider, never()).getIfAvailable();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void missingVerifiedDirectoryNeverGuessesEmailFromExternalUserId() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        mockSource("REPORTED", "REPORTED");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("person@example.test"));
        when(provider.getIfAvailable()).thenReturn(null);
        assertEquals(0, service.enqueue(tenant, defect, event, "actor", "DEFECT_REPORTED", null, null));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void enabledSwitchesAndVerifiedDirectoryCreateOneIdempotentOutboxInsert() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        mockSource("REPORTED", "REPORTED");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("auditor-subject"));
        VerifiedEmailDirectory directory = mock(VerifiedEmailDirectory.class);
        when(provider.getIfAvailable()).thenReturn(directory);
        when(directory.findVerified(eq(tenant), anyList())).thenReturn(Map.of("auditor-subject", new VerifiedEmailDirectory.VerifiedAddress("auditor@example.test", true)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1, service.enqueue(tenant, defect, event, "reviewer-subject", "DEFECT_REPORTED", null, null));
        verify(jdbc).update(contains("ON CONFLICT (operator_id,source_event_id,recipient_user_id,notification_type) DO NOTHING"), any(Object[].class));
        verify(directory).findVerified(eq(tenant), anyList());
    }

    @Test
    void unverifiedAddressIsSkippedWithoutInsert() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        mockSource("REPORTED", "REPORTED");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("auditor-subject"));
        VerifiedEmailDirectory directory = mock(VerifiedEmailDirectory.class);
        when(provider.getIfAvailable()).thenReturn(directory);
        when(directory.findVerified(eq(tenant), anyList())).thenReturn(Map.of("auditor-subject", new VerifiedEmailDirectory.VerifiedAddress("auditor@example.test", false)));
        assertEquals(0, service.enqueue(tenant, defect, event, "reviewer-subject", "DEFECT_REPORTED", null, null));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private void mockSource(String type, String status) throws Exception {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn("20261020-GLY-01");
            when(rs.getString(2)).thenReturn(status);
            when(rs.getString(3)).thenReturn(type);
            when(rs.getString(4)).thenReturn(status);
            when(rs.getTimestamp(5)).thenReturn(new Timestamp(1_800_000_000_000L));
            return extractor.extractData(rs);
        });
    }
}
