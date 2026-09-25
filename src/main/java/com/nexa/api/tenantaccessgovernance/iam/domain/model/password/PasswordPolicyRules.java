package com.nexa.api.tenantaccessgovernance.iam.domain.model.password;

import java.text.Normalizer;
import java.util.Set;

/** IAM-owned credential policy used by authentication and invitation flows. */
public final class PasswordPolicyRules {
    public static final int MINIMUM_LENGTH = 12;
    public static final int MAXIMUM_LENGTH = 128;
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password123", "123456789012", "qwertyuiop", "letmeinplease", "welcome123", "admin123456");

    private PasswordPolicyRules() { }

    public static boolean isValid(String password) {
        return isValid(password, MINIMUM_LENGTH);
    }

    public static boolean isValid(String password, int minimumLength) {
        int effectiveMinimum = Math.max(MINIMUM_LENGTH, minimumLength);
        if (password == null || password.length() < effectiveMinimum || password.length() > MAXIMUM_LENGTH) return false;
        if (password.codePoints().anyMatch(Character::isISOControl)) return false;
        String normalized = Normalizer.normalize(password, Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        return COMMON_PASSWORDS.stream().noneMatch(normalized::equals);
    }
}
