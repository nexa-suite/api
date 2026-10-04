-- V86 made registration fields nullable while a public draft is incomplete.
-- Keep the plan vocabulary and require a plan as soon as the row leaves DRAFT.
ALTER TABLE tenant_management.organization_registration
    DROP CONSTRAINT IF EXISTS ck_organization_registration_plan;

ALTER TABLE tenant_management.organization_registration
    ADD CONSTRAINT ck_organization_registration_plan CHECK (
        reference_plan IN ('Starter', 'Standard', 'Professional', 'Enterprise')
        OR (status = 'DRAFT' AND reference_plan IS NULL)
    );
