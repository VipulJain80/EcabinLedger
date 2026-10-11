package com.ecabin.ledger.storage;

public interface BlobStorage {
    void put(String key, byte[] content, String contentType);
    byte[] get(String key);
    void delete(String key);
}
