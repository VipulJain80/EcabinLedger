package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.FormVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FormMasterMutationRulesTest {
    private final UUID formId=UUID.randomUUID();
    private final FormVersion published=version("PUBLISHED",1);
    private final FormVersion draft=version("DRAFT",2);

    @Test void defaultsToPublishedAndNeverImplicitlyChoosesAnUnpublishedDraft() {
        assertEquals(published,FormMasterMutationRules.selectSourceVersion(List.of(draft,published),published.id(),null));
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->FormMasterMutationRules.selectSourceVersion(List.of(draft),null,null));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        ResponseStatusException invalidPointer=assertThrows(ResponseStatusException.class,
            ()->FormMasterMutationRules.selectSourceVersion(List.of(draft),draft.id(),null));
        assertEquals(HttpStatus.CONFLICT,invalidPointer.getStatusCode());
    }

    @Test void anExplicitVersionMustBelongToTheTenantScopedSourceVersionList() {
        assertEquals(draft,FormMasterMutationRules.selectSourceVersion(List.of(draft,published),published.id(),draft.id()));
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->FormMasterMutationRules.selectSourceVersion(List.of(published),published.id(),UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
    }

    @Test void validatesNormalizedFormKeysAndDisplayNames() {
        assertEquals("GALLEY_DEFECT_COPY",FormMasterMutationRules.normalizeKey(" galley_defect_copy "));
        assertThrows(IllegalArgumentException.class,()->FormMasterMutationRules.normalizeKey("bad key"));
        assertEquals("Galley Copy",FormMasterMutationRules.validateName(" Galley Copy "));
        assertThrows(IllegalArgumentException.class,()->FormMasterMutationRules.validateName("  "));
        assertThrows(IllegalArgumentException.class,()->FormMasterMutationRules.validateName("x".repeat(161)));
    }

    private FormVersion version(String status,int number) {
        return new FormVersion(UUID.randomUUID(),formId,number,status,new FormConfiguration(List.of()),"admin",
            Instant.parse("2026-10-14T00:00:00Z"),null,null,"Galley",UUID.randomUUID(),
            com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT,
            com.ecabin.ledger.api.FormModels.WorkflowStage.INITIAL_REPORT);
    }
}
