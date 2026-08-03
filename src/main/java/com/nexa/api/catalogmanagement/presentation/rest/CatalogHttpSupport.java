package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.exception.CatalogIdempotencyKeyRequiredException;
import com.nexa.api.catalogmanagement.application.exception.CatalogPreconditionRequiredException;
import com.nexa.api.catalogmanagement.application.port.out.CatalogClientAccountPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

final class CatalogHttpSupport {
    static final String ACCESS_CONTEXT = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";

    private CatalogHttpSupport() { }

    static CatalogScope scope(CurrentAccessContext context) {
        return new CatalogScope(context.tenantId().value(), context.workspaceId().value(), false);
    }

    static CatalogScope scope(CurrentAccessContext context, ObjectProvider<CatalogClientAccountPort> clientAccounts) {
        boolean buyer = context.hasRole(MembershipRole.BUYER);
        if (!buyer || clientAccounts == null) return scope(context);
        CatalogClientAccountPort resolver = clientAccounts.getIfAvailable();
        if (resolver == null) return new CatalogScope(context.tenantId().value(), context.workspaceId().value(), true);
        return resolver.findProfileForMembership(context.tenantId().value(), context.workspaceId().value(), context.membershipId().value())
                .map(profile -> new CatalogScope(context.tenantId().value(), context.workspaceId().value(), true,
                        profile.id(), profile.segment(), profile.buyerTier()))
                .orElseGet(() -> new CatalogScope(context.tenantId().value(), context.workspaceId().value(), true));
    }

    static long version(String value) {
        if (value == null || value.isBlank()) throw new CatalogPreconditionRequiredException();
        try { return Long.parseLong(value.replace("\"", "").trim()); }
        catch (NumberFormatException exception) { throw new CatalogPreconditionRequiredException(); }
    }

    static String etag(long version) { return "\"" + version + "\""; }

    static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Catalog identifier is invalid"); }
    }

    static void requireIdempotency(String value) {
        if (value == null || value.isBlank() || value.length() > 160) throw new CatalogIdempotencyKeyRequiredException();
    }
}
