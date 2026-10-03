ALTER TABLE tenant_management.organization_invitation_role
    DROP CONSTRAINT ck_organization_invitation_role,
    ADD CONSTRAINT ck_organization_invitation_role CHECK (
        role IN (
            'TENANT_ADMIN',
            'COMPANY_OWNER',
            'SALES',
            'WAREHOUSE',
            'LOGISTICS',
            'BUSINESS_OPERATIONS_MANAGER'
        )
    );
