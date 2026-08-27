package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

public record WorkspaceSummary(String id, String tenantId, String name, String slug, String status, long version) { }
