package com.ecabin.ledger.service;

import java.util.Locale;

/** Pure validation for the two closure outcomes and conditional remark requirement. */
public final class ClosureDecisionRules {
    private ClosureDecisionRules() {}

    public static String normalize(String submitted) {
        return switch (submitted == null ? "" : submitted.trim().toUpperCase(Locale.ROOT)) {
            case "ACCEPT", "APPROVED" -> "APPROVED";
            case "DECLINE", "REJECTED" -> "REJECTED";
            default -> throw new IllegalArgumentException("Decision must be ACCEPT or DECLINE.");
        };
    }

    public static String validateReason(String decision, String reason) {
        String value=reason==null?"":reason.trim();
        if(value.length()>2000)throw new IllegalArgumentException("Remarks must be 2,000 characters or fewer.");
        if("REJECTED".equals(decision)&&value.isBlank())throw new IllegalArgumentException("Enter a reason before declining closure.");
        return value;
    }
}
