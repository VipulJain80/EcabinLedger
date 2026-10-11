package com.ecabin.ledger.storage;

public interface EvidenceScanner {
    enum Result { CLEAN, REJECTED, UNAVAILABLE }
    Result scan(byte[] content);
}
