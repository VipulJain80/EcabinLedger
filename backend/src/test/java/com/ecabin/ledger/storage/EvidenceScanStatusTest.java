package com.ecabin.ledger.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EvidenceScanStatusTest {
    @Test void unavailableAndUnknownScannerResultsStayQuarantined() {
        assertEquals("QUARANTINED", EvidenceScanStatus.from(EvidenceScanner.Result.UNAVAILABLE));
    }

    @Test void onlyCleanScannerResultIsDownloadable() {
        assertEquals("CLEAN", EvidenceScanStatus.from(EvidenceScanner.Result.CLEAN));
        assertEquals("REJECTED", EvidenceScanStatus.from(EvidenceScanner.Result.REJECTED));
    }
}
