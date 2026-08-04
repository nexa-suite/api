package com.nexa.api.invoicing.infrastructure.storage;

import com.nexa.api.invoicing.application.port.ObjectStoragePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    public S3CompatibleObjectStorageAdapter(org.springframework.core.env.Environment environment) {
        this.endpoint = URI.create(environment.getProperty("nexa.object-storage.endpoint", "http://localhost:9000").replaceAll("/$", ""));
        this.bucket = required(environment.getProperty("nexa.object-storage.bucket", "nexa-private"), "bucket");
        this.accessKey = required(environment.getProperty("nexa.object-storage.access-key", "nexa-minio"), "access key");
        this.secretKey = required(environment.getProperty("nexa.object-storage.secret-key", "nexa-minio-local-foundation"), "secret key");
        this.region = environment.getProperty("nexa.object-storage.region", "us-east-1");
        this.timeout = Duration.ofMillis(Long.parseLong(environment.getProperty("nexa.object-storage.timeout-ms", "5000")));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public StoredObject put(String objectKey, byte[] content, String contentType) {
        validate(objectKey, content);
        String hash = sha256(content);
        HttpResponse<byte[]> response = sendBytes("PUT", objectKey, content, contentType, hash);
        requireSuccess(response.statusCode(), response.body());
        return new StoredObject(objectKey, hash, contentType, content.length);
    }

    @Override
    public InputStream open(String objectKey) {
        validateKey(objectKey);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(request("GET", objectKey, new byte[0], null, sha256(new byte[0])), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
                response.body().close();
                if (attempt == 2 || response.statusCode() < 500) throw new IllegalArgumentException("Object is not available: HTTP " + response.statusCode());
            } catch (IOException exception) {
                if (attempt == 2) throw new IllegalStateException("S3-compatible object read failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("S3-compatible object read interrupted", exception);
            }
        }
        throw new IllegalStateException("S3-compatible object read failed");
    }

    @Override
    public void delete(String objectKey) {
        validateKey(objectKey);
        HttpResponse<byte[]> response = sendBytes("DELETE", objectKey, new byte[0], null, sha256(new byte[0]));
        requireSuccess(response.statusCode(), response.body());
    }

    private HttpResponse<byte[]> sendBytes(String method, String objectKey, byte[] content, String contentType, String hash) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest.Builder builder = requestBuilder(method, objectKey, content, hash);
                if (contentType != null && !contentType.isBlank()) builder.header("content-type", contentType);
                HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 500 || attempt == 2) return response;
            } catch (IOException exception) {
                if (attempt == 2) throw new IllegalStateException("S3-compatible object write failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("S3-compatible object write interrupted", exception);
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
        HttpRequest.BodyPublisher body = method.equals("GET") || method.equals("DELETE") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(content);
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
    private static void validate(String key, byte[] content) { validateKey(key); if (content == null || content.length > 52428800) throw new IllegalArgumentException("Object size is invalid"); }
    private static void validateKey(String key) { if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..") || key.contains("\\")) throw new IllegalArgumentException("Object key is invalid"); }
    private static String required(String value, String label) { if (value == null || value.isBlank()) throw new IllegalStateException("S3-compatible " + label + " is required"); return value; }
    private static void requireSuccess(int status, byte[] body) { if (status < 200 || status >= 300) throw new IllegalStateException("S3-compatible storage rejected request: HTTP " + status + " " + new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8)); }
}
