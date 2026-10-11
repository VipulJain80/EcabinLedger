package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.Test;

class ApiRateLimitClassificationTest {
    private final ApiRateLimitService service = new ApiRateLimitService(null, null, null, null);

    @Test void mapsProtectedPathsToApprovedBoundedCategories() {
        assertEquals("DEFECT_CREATE", service.classify("POST", "/api/v1/defects"));
        assertEquals("CSV_IMPORTS", service.classify("POST", "/api/v1/configuration/areas/imports/validate"));
        assertEquals("CSV_EXPORTS", service.classify("GET", "/api/v1/configuration/areas/export"));
        assertEquals("EVIDENCE_UPLOADS", service.classify("POST", "/api/v1/defects/123/forms/456/versions/1/fields/image/attachments"));
        assertEquals("EXPENSIVE_SEARCH", service.classify("GET", "/api/v1/dashboard/summary"));
        assertEquals("WORKFLOW_MUTATIONS", service.classify("POST", "/api/v1/defects/123/verifications"));
        assertEquals("ORDINARY_READS", service.classify("GET", "/api/v1/reference-data/severities"));
    }

    @Test void rejectsUnsupportedScopesAndLimitsAboveServerCeilings() {
        assertDoesNotThrow(() -> service.validate(new ApiRateLimitService.PolicyInput("ORDINARY_READS","Safe read limit","","TENANT",120,60,"approved")));
        assertThrows(IllegalArgumentException.class, () -> service.validate(new ApiRateLimitService.PolicyInput("ORDINARY_READS","Too high","","TENANT",1201,60,"approved")));
        assertThrows(IllegalArgumentException.class, () -> service.validate(new ApiRateLimitService.PolicyInput("ORDINARY_READS","Unscoped","","IP",50,60,"approved")));
        assertThrows(IllegalArgumentException.class, () -> service.validate(new ApiRateLimitService.PolicyInput("/api/v1/**","Raw pattern","","TENANT",50,60,"approved")));
    }
}
