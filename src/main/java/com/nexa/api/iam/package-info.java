@org.springframework.modulith.ApplicationModule(id = "iam", allowedDependencies = {
		"shared", "tenantmanagement::access", "tenantmanagement::membership", "tenantmanagement::access-context",
		"tenantmanagement::registration" })
package com.nexa.api.iam;
