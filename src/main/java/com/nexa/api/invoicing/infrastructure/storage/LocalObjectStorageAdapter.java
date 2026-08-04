package com.nexa.api.invoicing.infrastructure.storage;

import com.nexa.api.invoicing.application.port.ObjectStoragePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Private local adapter used by local and CI profiles; object keys never use user filenames. */
@Component
@Profile("!test & !s3 & !minio")
public final class LocalObjectStorageAdapter implements ObjectStoragePort {
    private final Path root;
    public LocalObjectStorageAdapter(org.springframework.core.env.Environment environment) {
        this.root = Path.of(environment.getProperty("nexa.object-storage.root", "./.local-object-storage")).toAbsolutePath().normalize();
    }
    @Override public StoredObject put(String objectKey, byte[] content, String contentType) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("..") || objectKey.startsWith("/")) throw new IllegalArgumentException("Object key is invalid");
        if (content == null || content.length > 52428800) throw new IllegalArgumentException("Object size is invalid");
        try {
            Path destination = root.resolve(objectKey).normalize();
            if (!destination.startsWith(root)) throw new IllegalArgumentException("Object key escapes storage root");
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(root, ".upload-", ".part");
            Files.write(temporary, content);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new StoredObject(objectKey, sha256(content), contentType, content.length);
        } catch (IOException exception) { throw new IllegalStateException("Private object storage write failed", exception); }
    }
    @Override public InputStream open(String objectKey) {
        try {
            Path path = root.resolve(objectKey).normalize();
            if (!path.startsWith(root)) throw new IllegalArgumentException("Object key escapes storage root");
            return Files.newInputStream(path);
        } catch (IOException exception) { throw new IllegalArgumentException("Object is not available", exception); }
    }
    @Override public void delete(String objectKey) {
        try { Files.deleteIfExists(root.resolve(objectKey).normalize()); } catch (IOException exception) { throw new IllegalStateException("Private object storage delete failed", exception); }
    }
    static String sha256(byte[] content) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(content); StringBuilder value = new StringBuilder(64); for (byte part : digest) value.append(String.format("%02x", part)); return value.toString(); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
