package com.ecabin.ledger.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileBlobStorageTest {
    @TempDir Path temp;

    @Test void storesOnlyUnderConfiguredRootAndDoesNotOverwriteKeys() throws Exception {
        Path root=temp.resolve("uploadimages");
        LocalFileBlobStorage storage=new LocalFileBlobStorage(root.toString());
        storage.initialize();
        storage.put("evidence/tenant/opaque-id",new byte[]{1,2,3},"image/png");
        assertArrayEquals(new byte[]{1,2,3},storage.get("evidence/tenant/opaque-id"));
        assertTrue(Files.exists(root.resolve("evidence/tenant/opaque-id")));
        assertThrows(IllegalStateException.class,()->storage.put("evidence/tenant/opaque-id",new byte[]{4},"image/png"));
        assertThrows(IllegalArgumentException.class,()->storage.put("../outside",new byte[]{4},"image/png"));
        storage.delete("evidence/tenant/opaque-id");
        assertFalse(Files.exists(root.resolve("evidence/tenant/opaque-id")));
    }

    @Test void rejectsSymlinkThatEscapesStorageRoot() throws Exception {
        Path root = temp.resolve("uploadimages");
        Path outside = temp.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        LocalFileBlobStorage storage = new LocalFileBlobStorage(root.toString());
        storage.initialize();
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | SecurityException ex) {
            return;
        }
        assertThrows(IllegalArgumentException.class,
            () -> storage.put("escape/file", new byte[] {1}, "image/png"));
    }
}
