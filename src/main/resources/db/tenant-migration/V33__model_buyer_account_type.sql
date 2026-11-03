ALTER TABLE sales.client_account
    ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'BUSINESS',
    ALTER COLUMN tax_country_code DROP NOT NULL,
    ALTER COLUMN tax_identifier_type DROP NOT NULL,
    ALTER COLUMN tax_identifier_value DROP NOT NULL,
    ADD CONSTRAINT ck_client_account_type
        CHECK (account_type IN ('BUSINESS', 'INDIVIDUAL')),
    ADD CONSTRAINT ck_client_account_tax_by_type
        CHECK (
            (account_type = 'BUSINESS'
                AND tax_country_code IS NOT NULL
                AND tax_identifier_type IS NOT NULL
                AND tax_identifier_value IS NOT NULL)
            OR
            (account_type = 'INDIVIDUAL'
                AND tax_country_code IS NULL
                AND tax_identifier_type IS NULL
                AND tax_identifier_value IS NULL)
        ),
    ADD CONSTRAINT ck_client_account_peru_ruc_format
        CHECK (
            account_type <> 'BUSINESS'
            OR upper(tax_country_code) <> 'PE'
            OR upper(tax_identifier_type) <> 'RUC'
            OR tax_identifier_value ~ '^[0-9]{11}$'
        );

CREATE INDEX ix_client_account_sales_owner_scope
    ON sales.client_account (tenant_id, workspace_id, sales_owner_membership_id)
    WHERE sales_owner_membership_id IS NOT NULL;
