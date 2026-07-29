package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class CatalogAuthorizationAdapter implements CatalogAuthorizationPort {
	@Override
	public void requireCatalogRead() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("catalog:read"))) {
			throw new AccessDeniedException("catalog:read permission is required");
		}
	}
}
