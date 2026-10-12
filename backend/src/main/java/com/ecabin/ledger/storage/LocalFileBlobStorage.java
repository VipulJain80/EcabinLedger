package com.ecabin.ledger.storage;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Private workstation storage. Files are served only through the authorized attachment API. */
@Component
@ConditionalOnProperty(name = "app.attachments.provider", havingValue = "local")
public class LocalFileBlobStorage implements BlobStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalFileBlobStorage.class);
    private final Path configuredRoot;
    private Path realRoot;

    public LocalFileBlobStorage(@Value("${app.attachments.local-path:/Users/vipuljain/Desktop/EcabinLedger/uploadimages}") String root) {
        this.configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(configuredRoot);
            if (!Files.isDirectory(configuredRoot) || !Files.isWritable(configuredRoot)) {
                throw new IllegalStateException("Configured local evidence storage is not a writable directory.");
            }
            realRoot = configuredRoot.toRealPath();
            log.info("Evidence storage provider selected: local filesystem");
        } catch (IOException ex) {
            throw new IllegalStateException("Configured local evidence storage could not be initialized.", ex);
        }
    }

    @Override public void put(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            ensureContained(target.getParent().toRealPath());
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new IllegalStateException("Evidence could not be stored in local development storage.", ex);
        }
    }

    @Override public byte[] get(String key) {
        try {
            Path target = resolve(key);
            ensureContained(target.toRealPath());
            return Files.readAllBytes(target);
        }
        catch (IOException ex) { throw new IllegalStateException("Evidence is temporarily unavailable.", ex); }
    }

    @Override public void delete(String key) {
        try {
            Path target = resolve(key);
            if (Files.exists(target)) ensureContained(target.toRealPath());
            Files.deleteIfExists(target);
        }
        catch (IOException ex) { throw new IllegalStateException("Evidence cleanup could not be completed.", ex); }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.startsWith("\\") || key.contains("\\")) {
            throw new IllegalArgumentException("Invalid evidence storage key.");
        }
        Path target = configuredRoot.resolve(key).normalize();
        if (!target.startsWith(configuredRoot) || target.equals(configuredRoot)) {
            throw new IllegalArgumentException("Invalid evidence storage key.");
        }
        return target;
    }

    private void ensureContained(Path realPath) {
        if (realRoot == null || !realPath.startsWith(realRoot)) {
            throw new IllegalArgumentException("Invalid evidence storage key.");
        }
    }
}
