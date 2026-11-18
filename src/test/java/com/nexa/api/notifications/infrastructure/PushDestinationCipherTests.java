package com.nexa.api.notifications.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushDestinationCipherTests {
    private static final String OLD_KEY = "old-push-destination-key-012345678901234567890123456789";
    private static final String NEW_KEY = "new-push-destination-key-012345678901234567890123456789";
    private static final UUID TENANT = UUID.fromString("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10");
    private static final UUID WORKSPACE = UUID.fromString("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17");
    private static final UUID SUBSCRIPTION = UUID.fromString("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2");
    private static final String DESTINATION = "fcm-fid:abcdefghijk12345678901";

    @Test
    void encryptsAndAuthenticatesDestinationAgainstTenantWorkspaceAndSubscription() {
        PushDestinationCipher cipher = new PushDestinationCipher(OLD_KEY, "v1", "");
        PushDestinationCipher.EncryptedDestination encrypted = cipher.encrypt(DESTINATION, TENANT, WORKSPACE,
                SUBSCRIPTION);

        assertThat(encrypted.ciphertext()).doesNotContain(DESTINATION).isNotEqualTo(DESTINATION);
        assertThat(encrypted.toString()).doesNotContain(encrypted.ciphertext()).contains("<redacted>");
        assertThat(cipher.decrypt(encrypted.ciphertext(), encrypted.keyVersion(), TENANT, WORKSPACE, SUBSCRIPTION))
                .isEqualTo(DESTINATION);
        assertThatThrownBy(() -> cipher.decrypt(encrypted.ciphertext(), encrypted.keyVersion(), UUID.randomUUID(),
                WORKSPACE, SUBSCRIPTION))
                .isInstanceOf(IllegalStateException.class).hasMessage("Stored push destination cannot be decrypted")
                .hasMessageNotContaining(DESTINATION);
    }

    @Test
    void detectsCiphertextTamperingWithoutRevealingStoredMaterial() {
        PushDestinationCipher cipher = new PushDestinationCipher(OLD_KEY, "v1", "");
        PushDestinationCipher.EncryptedDestination encrypted = cipher.encrypt(DESTINATION, TENANT, WORKSPACE,
                SUBSCRIPTION);
        String tampered = (encrypted.ciphertext().charAt(0) == 'a' ? "b" : "a")
                + encrypted.ciphertext().substring(1);

        assertThatThrownBy(() -> cipher.decrypt(tampered, encrypted.keyVersion(), TENANT, WORKSPACE, SUBSCRIPTION))
                .isInstanceOf(IllegalStateException.class).hasMessage("Stored push destination cannot be decrypted")
                .hasMessageNotContaining(DESTINATION);
    }

    @Test
    void readsPreviousKeyVersionsAndWritesUsingTheCurrentVersion() {
        PushDestinationCipher oldCipher = new PushDestinationCipher(OLD_KEY, "v1", "");
        PushDestinationCipher.EncryptedDestination oldValue = oldCipher.encrypt(DESTINATION, TENANT, WORKSPACE,
                SUBSCRIPTION);
        PushDestinationCipher rotated = new PushDestinationCipher(NEW_KEY, "v2", "v1=" + OLD_KEY);

        assertThat(rotated.decrypt(oldValue.ciphertext(), oldValue.keyVersion(), TENANT, WORKSPACE, SUBSCRIPTION))
                .isEqualTo(DESTINATION);
        PushDestinationCipher.EncryptedDestination newValue = rotated.encrypt(DESTINATION, TENANT, WORKSPACE,
                SUBSCRIPTION);
        assertThat(newValue.keyVersion()).isEqualTo("v2");
        assertThat(rotated.decrypt(newValue.ciphertext(), newValue.keyVersion(), TENANT, WORKSPACE, SUBSCRIPTION))
                .isEqualTo(DESTINATION);
    }

    @Test
    void rejectsMissingOrDuplicateKeysAtConfigurationTime() {
        assertThatThrownBy(() -> new PushDestinationCipher("", "v1", ""))
                .isInstanceOf(IllegalStateException.class).hasMessage("Push destination encryption key is required");
        assertThatThrownBy(() -> new PushDestinationCipher(OLD_KEY, "v1", "v1=" + NEW_KEY))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Push destination key versions must be unique");
    }
}
