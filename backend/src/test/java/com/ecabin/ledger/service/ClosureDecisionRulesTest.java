package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClosureDecisionRulesTest {
    @Test void acceptsOnlyAcceptAndDeclineAndAllowsEmptyAcceptRemark(){
        assertEquals("APPROVED",ClosureDecisionRules.normalize("ACCEPT"));
        assertEquals("REJECTED",ClosureDecisionRules.normalize("DECLINE"));
        assertEquals("",ClosureDecisionRules.validateReason("APPROVED",null));
        assertThrows(IllegalArgumentException.class,()->ClosureDecisionRules.normalize("REOPEN"));
    }

    @Test void declineNeedsReasonAndAllReasonsHaveTwoThousandCharacterLimit(){
        assertThrows(IllegalArgumentException.class,()->ClosureDecisionRules.validateReason("REJECTED"," "));
        assertEquals("Needs more work",ClosureDecisionRules.validateReason("REJECTED"," Needs more work "));
        assertThrows(IllegalArgumentException.class,()->ClosureDecisionRules.validateReason("APPROVED","x".repeat(2001)));
    }
}
