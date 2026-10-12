package com.ecabin.ledger.storage;

/** Converts scanner results to the only states permitted for stored evidence. */
public final class EvidenceScanStatus {
    private EvidenceScanStatus() {}

    public static String from(EvidenceScanner.Result result) {
        if (result == EvidenceScanner.Result.CLEAN) return "CLEAN";
        if (result == EvidenceScanner.Result.REJECTED) return "REJECTED";
        return "QUARANTINED";
    }
}
