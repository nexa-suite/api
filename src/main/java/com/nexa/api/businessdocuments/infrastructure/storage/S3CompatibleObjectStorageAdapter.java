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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Minimal S3 Signature V4 adapter for private MinIO/S3-compatible buckets. */
@Profile("s3 | minio")
@Component
public final class S3CompatibleObjectStorageAdapter implements ObjectStoragePort {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private final HttpClient client;
    private final URI endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final Duration timeout;
    private final TechnicalMetrics metrics;

    @Autowired
    public S3CompatibleObjectStorageAdapter(org.springframework.core.env.Environment environment,
                                            ObjectProvider<TechnicalMetrics> metrics) {
        this(environment, metrics == null ? null : metrics.getIfAvailable());
    }

    public S3CompatibleObjectStorageAdapter(org.springframework.core.env.Environment environment) {
        this(environment, (TechnicalMetrics) null);
    }

    private S3CompatibleObjectStorageAdapter(org.springframework.core.env.Environment environment, TechnicalMetrics metrics) {
        this.endpoint = URI.create(required(environment.getProperty("nexa.object-storage.endpoint", ""), "endpoint").replaceAll("/$", ""));
        this.bucket = required(environment.getProperty("nexa.object-storage.bucket", ""), "bucket");
        this.accessKey = required(environment.getProperty("nexa.object-storage.access-key", ""), "access key");
        this.secretKey = required(environment.getProperty("nexa.object-storage.secret-key", ""), "secret key");
        this.region = required(environment.getProperty("nexa.object-storage.region", ""), "region");
        this.timeout = Duration.ofMillis(Long.parseLong(environment.getProperty("nexa.object-storage.timeout-ms", "5000")));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.metrics = metrics;
    }

    @Override
    public StoredObject put(String objectKey, InputStream content, long contentLength, String contentType) {
        validateKey(objectKey);
        if (content == null || contentLength < 0 || contentLength > 52428800) throw new IllegalArgumentException("Object size is invalid");
        TechnicalMetrics.TimerSample timer = start("put");
        Path temporary = null;
        try {
            temporary = Files.createTempFile("nexa-object-", ".part");
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
            String hash = HexFormat.of().formatHex(digest.digest());
            HttpResponse<byte[]> response = sendFile("PUT", objectKey, temporary, contentType, hash);
            requireSuccess(response.statusCode(), response.body());
            StoredObject stored = new StoredObject(objectKey, hash, contentType, total);
            record(timer, "success");
            return stored;
        } catch (TechnicalFailureException exception) {
            record(timer, "unavailable");
            throw exception;
        } catch (IOException exception) {
            record(timer, "unavailable");
            throw unavailable("S3-compatible object write failed", exception);
        } catch (java.security.GeneralSecurityException exception) {
            record(timer, "error");
            throw new IllegalStateException("SHA-256 unavailable", exception);
        } catch (RuntimeException exception) {
            record(timer, "error");
            throw exception;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    @Override
    public InputStream open(String objectKey) {
        validateKey(objectKey);
        TechnicalMetrics.TimerSample timer = start("open");
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(request("GET", objectKey, new byte[0], null, sha256(new byte[0])), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    record(timer, "success");
                    return response.body();
                }
                response.body().close();
                if (attempt == 2 || response.statusCode() < 500) throw new IllegalArgumentException("Object is not available: HTTP " + response.statusCode());
            } catch (IOException exception) {
                if (attempt == 2) {
                    record(timer, "unavailable");
                    throw unavailable("S3-compatible object read failed", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                record(timer, "interrupted");
                throw unavailable("S3-compatible object read interrupted", exception);
            } catch (RuntimeException exception) {
                record(timer, "rejected");
                throw exception;
            }
        }
        record(timer, "error");
        throw new IllegalStateException("S3-compatible object read failed");
    }

    @Override
    public void delete(String objectKey) {
        validateKey(objectKey);
        TechnicalMetrics.TimerSample timer = start("delete");
        try {
            HttpResponse<byte[]> response = sendBytes("DELETE", objectKey, new byte[0], null, sha256(new byte[0]));
            requireSuccess(response.statusCode(), response.body());
            record(timer, "success");
        } catch (TechnicalFailureException exception) {
            record(timer, "unavailable");
            throw exception;
        } catch (RuntimeException exception) {
            record(timer, "error");
            throw exception;
        }
    }

    private TechnicalMetrics.TimerSample start(String operation) { return metrics == null ? null : metrics.start("storage_s3", operation); }
    private void record(TechnicalMetrics.TimerSample timer, String outcome) { if (metrics != null) { metrics.count("storage_s3", "operation", outcome); if (timer != null) timer.stop(outcome); } }

    private HttpResponse<byte[]> sendBytes(String method, String objectKey, byte[] content, String contentType, String hash) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest.Builder builder = requestBuilder(method, objectKey, content, hash);
                if (contentType != null && !contentType.isBlank()) builder.header("content-type", contentType);
                HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 500 || attempt == 2) return response;
            } catch (IOException exception) {
                if (attempt == 2) throw unavailable("S3-compatible object write failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable("S3-compatible object write interrupted", exception);
            }
        }
        throw new IllegalStateException("S3-compatible object write failed");
    }

    private HttpResponse<byte[]> sendFile(String method, String objectKey, Path content, String contentType, String hash) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest.Builder builder = requestBuilder(method, objectKey, HttpRequest.BodyPublishers.ofFile(content), hash);
                if (contentType != null && !contentType.isBlank()) builder.header("content-type", contentType);
                HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 500 || attempt == 2) return response;
            } catch (IOException exception) {
                if (attempt == 2) throw unavailable("S3-compatible object write failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable("S3-compatible object write interrupted", exception);
            }
        }
        throw new IllegalStateException("S3-compatible object write failed");
    }

    private HttpRequest request(String method, String objectKey, byte[] content, String contentType, String hash) {
        HttpRequest.Builder builder = requestBuilder(method, objectKey, content, hash);
        if (contentType != null && !contentType.isBlank()) builder.header("content-type", contentType);
        return builder.build();
    }

    private HttpRequest.Builder requestBuilder(String method, String objectKey, byte[] content, String hash) {
        HttpRequest.BodyPublisher body = method.equals("GET") || method.equals("DELETE") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(content);
        return requestBuilder(method, objectKey, body, hash);
    }

    private HttpRequest.Builder requestBuilder(String method, String objectKey, HttpRequest.BodyPublisher body, String hash) {
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String shortDate = DATE.format(now);
        String host = endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
        String path = path(objectKey);
        String canonicalHeaders = "host:" + host + "\n" + "x-amz-content-sha256:" + hash + "\n" + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + path + "\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + hash;
        String scope = shortDate + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(shortDate), stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return HttpRequest.newBuilder(endpoint.resolve(path)).timeout(timeout).method(method, body)
                .header("x-amz-content-sha256", hash).header("x-amz-date", amzDate).header("authorization", authorization);
    }

    private String path(String objectKey) {
        StringBuilder value = new StringBuilder("/").append(encode(bucket));
        for (String part : objectKey.split("/", -1)) value.append('/').append(encode(part));
        return value.toString();
    }

    private static String encode(String value) {
        StringBuilder output = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte current : bytes) {
            int character = current & 0xff;
            if (character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z' || character >= '0' && character <= '9' || "-_.~".indexOf(character) >= 0) output.append((char) character);
            else output.append('%').append(String.format(Locale.ROOT, "%02X", character));
        }
        return output.toString();
    }

    private byte[] signingKey(String shortDate) { return hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), shortDate), region), "s3"), "aws4_request"); }
    private static byte[] hmac(byte[] key, String value) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception exception) { throw new IllegalStateException("S3 signing failed", exception); } }
    private static String sha256(byte[] content) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); } catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); } }
    private static void validateKey(String key) { if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..") || key.contains("\\")) throw new IllegalArgumentException("Object key is invalid"); }
    private static String required(String value, String label) { if (value == null || value.isBlank()) throw new IllegalStateException("S3-compatible " + label + " is required"); return value; }
    private static TechnicalFailureException unavailable(String message, Throwable cause) {
        return new TechnicalFailureException(TechnicalFailureException.Kind.STORAGE_UNAVAILABLE, message, cause);
    }
    private static void requireSuccess(int status, byte[] body) {
        if (status < 200 || status >= 300) {
            throw new TechnicalFailureException(TechnicalFailureException.Kind.STORAGE_UNAVAILABLE,
                    "S3-compatible storage rejected request: HTTP " + status);
        }
    }
}
