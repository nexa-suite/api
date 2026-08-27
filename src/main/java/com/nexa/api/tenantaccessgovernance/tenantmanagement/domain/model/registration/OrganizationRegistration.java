package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.registration;

import java.time.Instant;
import java.util.Objects;

/** Registration lifecycle aggregate. Public input cannot select ACTIVE. */
public final class OrganizationRegistration {
    private final OrganizationRegistrationId id;
    private final FounderIdentity founder;
    private final WorkspaceSlug workspaceSlug;
    private final TermsAcceptance terms;
    private final ReferencePlan plan;
    private final RegistrationStatusTokenHash statusTokenHash;
    private OrganizationRegistrationStatus status;

    private OrganizationRegistration(OrganizationRegistrationId id, FounderIdentity founder, WorkspaceSlug workspaceSlug,
            TermsAcceptance terms, ReferencePlan plan, RegistrationStatusTokenHash statusTokenHash,
            OrganizationRegistrationStatus status) {
        this.id = Objects.requireNonNull(id); this.founder = Objects.requireNonNull(founder);
        this.workspaceSlug = Objects.requireNonNull(workspaceSlug); this.terms = Objects.requireNonNull(terms);
        this.plan = Objects.requireNonNull(plan); this.statusTokenHash = Objects.requireNonNull(statusTokenHash);
        this.status = Objects.requireNonNull(status);
    }

    public static OrganizationRegistration submit(OrganizationRegistrationId id, FounderIdentity founder,
            WorkspaceSlug slug, TermsAcceptance terms, ReferencePlan plan, RegistrationStatusTokenHash tokenHash) {
        return new OrganizationRegistration(id, founder, slug, terms, plan, tokenHash, OrganizationRegistrationStatus.PENDING_ACTIVATION);
    }

    /** Rehydrates persisted state; callers cannot use this to create public ACTIVE registrations. */
    public static OrganizationRegistration restore(OrganizationRegistrationId id, FounderIdentity founder,
            WorkspaceSlug slug, TermsAcceptance terms, ReferencePlan plan, RegistrationStatusTokenHash tokenHash,
            OrganizationRegistrationStatus status) {
        return new OrganizationRegistration(id, founder, slug, terms, plan, tokenHash, status);
    }

    public void activate() { transition(OrganizationRegistrationStatus.ACTIVE); }
    public void reject() { transition(OrganizationRegistrationStatus.REJECTED); }
    public void suspend() { transition(OrganizationRegistrationStatus.SUSPENDED); }
    private void transition(OrganizationRegistrationStatus next) {
        if (status != OrganizationRegistrationStatus.PENDING_ACTIVATION) throw new IllegalStateException("Registration transition is invalid");
        status = next;
    }
    public OrganizationRegistrationId id() { return id; }
    public FounderIdentity founder() { return founder; }
    public WorkspaceSlug workspaceSlug() { return workspaceSlug; }
    public TermsAcceptance terms() { return terms; }
    public ReferencePlan plan() { return plan; }
    public RegistrationStatusTokenHash statusTokenHash() { return statusTokenHash; }
    public OrganizationRegistrationStatus status() { return status; }
}
