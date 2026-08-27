package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class CatalogAuthorizationAdapter implements CatalogAuthorizationPort {
	@Override
	public void requireCatalogRead() {
		require("catalog:read");
	}

	@Override
	public void require(String permission) {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals(permission))) {
			throw new AccessDeniedException(permission + " permission is required");
		}
	}
}
