package com.nexa.api.businessdocuments.infrastructure.storage;

import com.nexa.api.businessdocuments.application.port.ObjectStoragePort;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.shared.infrastructure.observability.TechnicalMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Private local adapter used only by the explicit local profile. */
@Component
@Profile("local & !s3 & !minio")
public final class LocalObjectStorageAdapter implements ObjectStoragePort {
    private final Path root;
    private final TechnicalMetrics metrics;

    @Autowired
    public LocalObjectStorageAdapter(org.springframework.core.env.Environment environment,
                                     ObjectProvider<TechnicalMetrics> metrics) {
        this(environment, metrics == null ? null : metrics.getIfAvailable());
    }

    public LocalObjectStorageAdapter(org.springframework.core.env.Environment environment) {
        this(environment, (TechnicalMetrics) null);
    }

    private LocalObjectStorageAdapter(org.springframework.core.env.Environment environment, TechnicalMetrics metrics) {
        this.root = Path.of(environment.getProperty("nexa.object-storage.root", "./.local-object-storage")).toAbsolutePath().normalize();
        this.metrics = metrics;
    }
    @Override public StoredObject put(String objectKey, InputStream content, long contentLength, String contentType) {
        validateKey(objectKey);
        if (content == null || contentLength < 0 || contentLength > 52428800) throw new IllegalArgumentException("Object size is invalid");
        TechnicalMetrics.TimerSample timer = start("put");
        try {
            Path destination = safePath(objectKey);
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
                StoredObject stored = new StoredObject(objectKey, hex(digest.digest()), contentType, total);
                record(timer, "success");
                return stored;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            record(timer, "unavailable");
            throw new TechnicalFailureException(TechnicalFailureException.Kind.STORAGE_UNAVAILABLE, "Private object storage write failed", exception);
        } catch (java.security.GeneralSecurityException exception) {
            record(timer, "error");
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
    @Override public InputStream open(String objectKey) {
        TechnicalMetrics.TimerSample timer = start("open");
        try {
            validateKey(objectKey);
            InputStream stream = Files.newInputStream(safePath(objectKey));
            record(timer, "success");
            return stream;
        } catch (IOException exception) {
            record(timer, "unavailable");
            throw new TechnicalFailureException(TechnicalFailureException.Kind.STORAGE_UNAVAILABLE, "Private object storage read failed", exception);
        }
    }
    @Override public void delete(String objectKey) {
        TechnicalMetrics.TimerSample timer = start("delete");
        try {
            Files.deleteIfExists(safePath(objectKey));
            record(timer, "success");
        } catch (IOException exception) {
            record(timer, "unavailable");
            throw new TechnicalFailureException(TechnicalFailureException.Kind.STORAGE_UNAVAILABLE, "Private object storage delete failed", exception);
        }
    }
    private TechnicalMetrics.TimerSample start(String operation) { return metrics == null ? null : metrics.start("storage_local", operation); }
    private void record(TechnicalMetrics.TimerSample timer, String outcome) { if (metrics != null) { metrics.count("storage_local", "operation", outcome); if (timer != null) timer.stop(outcome); } }
    private Path safePath(String objectKey) { validateKey(objectKey); Path path = root.resolve(objectKey).normalize(); if (!path.startsWith(root)) throw new IllegalArgumentException("Object key escapes storage root"); return path; }
    private static void validateKey(String objectKey) { if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/") || objectKey.contains("..") || objectKey.contains("\\")) throw new IllegalArgumentException("Object key is invalid"); }
    private static String hex(byte[] digest) { StringBuilder value = new StringBuilder(64); for (byte part : digest) value.append(String.format("%02x", part)); return value.toString(); }
}
