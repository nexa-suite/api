package com.nexa.api.catalogmanagement.application.publicapi;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data-only Catalog & Commercial Policy contract for resolved customer terms. */
public interface CustomerTermsQuery {
    Optional<CustomerTermsSnapshot> findTerms(String tenantId, String workspaceId, String customerAccountId);

    record CustomerTermsSnapshot(String code) {
        private static final Pattern DUE_DAYS = Pattern.compile("(\\d+)");

        public CustomerTermsSnapshot {
            code = code == null || code.isBlank() ? "CASH" : code.trim();
        }

        public String description() { return code; }

        public boolean creditAllowed() {
            String normalized = code.toUpperCase(Locale.ROOT);
            return normalized.contains("CREDIT") || normalized.matches(".*NET[-_ ]?\\d+.*");
        }

        public int dueDays() {
            if (!creditAllowed()) return 0;
            Matcher matcher = DUE_DAYS.matcher(code);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        }
    }
}
