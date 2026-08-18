package com.nexa.api.audit.application.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Allowlist-only projection of audit metadata; raw security event JSON never reaches HTTP. */
public final class SafeAuditMetadata {
	private static final Set<String> ALLOWED = Set.of("section", "workspaceId", "status", "active", "fieldKey",
			"scope", "beforeRoles", "afterRoles", "invitationId", "accountResponse", "roles", "count", "operation", "reason",
			"oldValues", "newValues");
	private static final Set<String> ORGANIZATION_IDENTITY_FIELDS = Set.of("legalName", "displayName", "businessIdentifier", "operationCategory");
	private static final int MAX_STRING_LENGTH = 160;

	private SafeAuditMetadata() { }

	public static Map<String, Object> sanitize(Map<String, Object> raw) {
		Map<String, Object> safe = new LinkedHashMap<>();
		if (raw == null) return Map.of();
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			if (!ALLOWED.contains(entry.getKey())) continue;
			Object value = (entry.getKey().equals("oldValues") || entry.getKey().equals("newValues"))
					? safeOrganizationIdentityValues(entry.getValue()) : safeValue(entry.getValue());
			if (value != null) safe.put(entry.getKey(), value);
		}
		return Map.copyOf(safe);
	}

	private static Object safeOrganizationIdentityValues(Object value) {
		if (!(value instanceof Map<?, ?> raw)) return null;
		Map<String, Object> safe = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			if (!(entry.getKey() instanceof String key) || !ORGANIZATION_IDENTITY_FIELDS.contains(key)) continue;
			Object field = safeValue(entry.getValue());
			if (field != null) safe.put(key, field);
		}
		return Map.copyOf(safe);
	}

	private static Object safeValue(Object value) {
		if (value instanceof String text) return cap(text);
		if (value instanceof Number || value instanceof Boolean) return value;
		if (value instanceof Collection<?> collection) {
			List<Object> values = new ArrayList<>();
			for (Object item : collection) {
				if (values.size() == 20) break;
				Object safe = safeValue(item);
				if (safe instanceof String || safe instanceof Number || safe instanceof Boolean) values.add(safe);
			}
			return List.copyOf(values);
		}
		return null;
	}

	private static String cap(String value) {
		String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
		return normalized.length() <= MAX_STRING_LENGTH ? normalized : normalized.substring(0, MAX_STRING_LENGTH);
	}
}
