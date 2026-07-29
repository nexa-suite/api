package com.nexa.api.tenantmanagement.application.model;

public record WorkspaceSummary(String id, String tenantId, String name, String slug, String status, long version) { }
