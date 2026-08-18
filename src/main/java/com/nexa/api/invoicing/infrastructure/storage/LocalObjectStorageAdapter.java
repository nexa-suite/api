package com.nexa.api.invoicing.infrastructure.storage;

import com.nexa.api.invoicing.application.port.ObjectStoragePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    @Override public StoredObject put(String objectKey, InputStream content, long contentLength, String contentType) {
        validateKey(objectKey);
        if (content == null || contentLength < 0 || contentLength > 52428800) throw new IllegalArgumentException("Object size is invalid");
        try {
            Path destination = root.resolve(objectKey).normalize();
            if (!destination.startsWith(root)) throw new IllegalArgumentException("Object key escapes storage root");
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(root, ".upload-", ".part");
            try {
                long total = 0;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = content.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        total += read;
                        if (total > 52428800) throw new IllegalArgumentException("Object size is invalid");
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                if (total != contentLength) throw new IllegalArgumentException("Object length does not match declared length");
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new StoredObject(objectKey, hex(digest.digest()), contentType, total);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) { throw new IllegalStateException("Private object storage write failed", exception); }
        catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    @Override public InputStream open(String objectKey) {
        try {
            validateKey(objectKey);
            Path path = root.resolve(objectKey).normalize();
            if (!path.startsWith(root)) throw new IllegalArgumentException("Object key escapes storage root");
            return Files.newInputStream(path);
        } catch (IOException exception) { throw new IllegalArgumentException("Object is not available", exception); }
    }
    @Override public void delete(String objectKey) {
        try { validateKey(objectKey); Files.deleteIfExists(root.resolve(objectKey).normalize()); } catch (IOException exception) { throw new IllegalStateException("Private object storage delete failed", exception); }
    }
    private static void validateKey(String objectKey) { if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/") || objectKey.contains("..") || objectKey.contains("\\")) throw new IllegalArgumentException("Object key is invalid"); }
    private static String hex(byte[] digest) { StringBuilder value = new StringBuilder(64); for (byte part : digest) value.append(String.format("%02x", part)); return value.toString(); }
}
