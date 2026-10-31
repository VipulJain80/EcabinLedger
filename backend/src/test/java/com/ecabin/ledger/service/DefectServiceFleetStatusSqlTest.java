package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DefectServiceFleetStatusSqlTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void fleetStatusOpenDefectProjectionHasBalancedScopePredicates() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        new DefectService(jdbc).fleetStatus(UUID.randomUUID(), "reviewer@example.test", true, true, true,
            null, null, 0, 25, "", "tailNumber", "ASC");

        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(statement.capture(), any(RowMapper.class), any(Object[].class));
        String sql = statement.getValue();
        assertTrue(sql.contains("current_action.assigned_team") && sql.contains("d.reviewer_team_id"));
        assertBalancedParentheses(sql);
    }

    private static void assertBalancedParentheses(String sql) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')') {
                depth--;
                assertTrue(depth >= 0, "SQL closes a parenthesis before opening it");
            }
        }
        assertEquals(0, depth, "Generated Fleet Status SQL must close every parenthesis");
        assertTrue(!inString, "Generated Fleet Status SQL must close every string literal");
    }
}
