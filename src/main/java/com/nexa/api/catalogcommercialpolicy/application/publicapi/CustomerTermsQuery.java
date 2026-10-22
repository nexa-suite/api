package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data-only Catalog & Commercial Policy contract for resolved customer terms. */
public interface CustomerTermsQuery {
    Optional<CustomerTermsSnapshot> findTerms(String tenantId, String workspaceId, String customerAccountId);

    record CustomerTermsSnapshot(String code) {
        private static final Pattern CREDIT_TERM = Pattern.compile("(?:CREDIT|NET)[-_ ]?(\\d+)", Pattern.CASE_INSENSITIVE);

        public CustomerTermsSnapshot {
            code = code == null || code.isBlank() ? "CASH" : code.trim();
        }

        public String description() { return code; }

        public boolean creditAllowed() {
            return parsedCreditDays().isPresent();
        }

        public int dueDays() {
            return parsedCreditDays().orElse(0);
        }

        private OptionalInt parsedCreditDays() {
            Matcher matcher = CREDIT_TERM.matcher(code);
            if (!matcher.matches()) return OptionalInt.empty();
            try {
                return OptionalInt.of(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }
    }
}
