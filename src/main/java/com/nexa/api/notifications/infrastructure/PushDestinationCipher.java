package com.nexa.api.notifications.infrastructure;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Encrypts recoverable provider destinations with keys held outside Tenant databases. */
public final class PushDestinationCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String AAD_PREFIX = "nexa-push-destination-v1|";

    private final String currentKeyVersion;
    private final Map<String, byte[]> keys;

    public PushDestinationCipher(String currentKeyMaterial, String currentKeyVersion, String previousKeys) {
        this.currentKeyVersion = requireKeyVersion(currentKeyVersion);
        Map<String, byte[]> configured = new HashMap<>();
        configured.put(this.currentKeyVersion, deriveKey(currentKeyMaterial));
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String entry : previousKeys.split(",")) {
                String[] pair = entry.trim().split("=", 2);
                if (pair.length != 2) throw new IllegalArgumentException("Previous push destination key entry is invalid");
                String version = requireKeyVersion(pair[0]);
                if (configured.putIfAbsent(version, deriveKey(pair[1])) != null) {
                    throw new IllegalArgumentException("Push destination key versions must be unique");
                }
            }
        }
        this.keys = Map.copyOf(configured);
    }

    public EncryptedDestination encrypt(String destination, UUID tenantId, UUID workspaceId, UUID subscriptionId) {
        requireScope(destination, tenantId, workspaceId, subscriptionId);
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(currentKeyVersion), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(tenantId, workspaceId, subscriptionId));
            byte[] ciphertext = cipher.doFinal(destination.getBytes(StandardCharsets.UTF_8));
            byte[] encoded = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, encoded, 0, iv.length);
            System.arraycopy(ciphertext, 0, encoded, iv.length, ciphertext.length);
            return new EncryptedDestination(Base64.getUrlEncoder().withoutPadding().encodeToString(encoded),
                    currentKeyVersion);
        } catch (Exception exception) {
            throw new IllegalStateException("Push destination cannot be encrypted");
        }
    }

    public String decrypt(String encoded, String keyVersion, UUID tenantId, UUID workspaceId, UUID subscriptionId) {
        if (encoded == null || keyVersion == null || tenantId == null || workspaceId == null || subscriptionId == null) {
            throw new IllegalArgumentException("Stored push destination is incomplete");
        }
        try {
            byte[] key = keys.get(keyVersion);
            if (key == null) throw new IllegalArgumentException("Stored push destination key version is unavailable");
            byte[] packed = Base64.getUrlDecoder().decode(encoded);
            if (packed.length < IV_LENGTH + TAG_BITS / 8 + 1) {
                throw new IllegalArgumentException("Stored push destination is invalid");
            }
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(tenantId, workspaceId, subscriptionId));
            String destination = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            if (destination.isBlank()) throw new IllegalArgumentException("Stored push destination is invalid");
            return destination;
        } catch (Exception exception) {
            // Do not retain the ciphertext, key material, or crypto provider detail in the failure message.
            throw new IllegalStateException("Stored push destination cannot be decrypted");
        }
    }

    public String currentKeyVersion() { return currentKeyVersion; }

    private static byte[] aad(UUID tenantId, UUID workspaceId, UUID subscriptionId) {
        return (AAD_PREFIX + tenantId + "|" + workspaceId + "|" + subscriptionId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] deriveKey(String material) {
        if (material == null || material.isBlank() || material.strip().length() < 32) {
            throw new IllegalStateException("Push destination encryption key is required");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.strip().getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required");
        }
    }

    private static String requireKeyVersion(String version) {
        String value = Objects.requireNonNull(version, "Push destination key version is required").strip();
        if (!value.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Push destination key version is invalid");
        }
        return value;
    }

    private static void requireScope(String destination, UUID tenantId, UUID workspaceId, UUID subscriptionId) {
        if (destination == null || destination.isBlank() || tenantId == null || workspaceId == null || subscriptionId == null) {
            throw new IllegalArgumentException("Push destination and Tenant scope are required");
        }
    }

    public record EncryptedDestination(String ciphertext, String keyVersion) {
        public EncryptedDestination {
            Objects.requireNonNull(ciphertext, "Encrypted push destination is required");
            Objects.requireNonNull(keyVersion, "Push destination key version is required");
        }

        @Override public String toString() {
            return "EncryptedDestination[ciphertext=<redacted>, keyVersion=" + keyVersion + "]";
        }
    }
}
