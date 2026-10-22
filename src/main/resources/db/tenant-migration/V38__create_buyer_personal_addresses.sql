-- Buyer-owned delivery locations are separate from shared Client Account addresses.

-- Keep the geography captured on the address consistent with the selected
-- district. The baseline's independent foreign keys allow a district to point
-- at a province in a different department, so add a composite hierarchy key.
ALTER TABLE reference_data.province
    ADD CONSTRAINT uq_reference_data_province_code_department
        UNIQUE (code, department_code);

ALTER TABLE reference_data.district
    ADD CONSTRAINT uq_reference_data_district_geo_hierarchy
        UNIQUE (code, province_code, department_code),
    ADD CONSTRAINT fk_reference_district_province_geo_hierarchy
        FOREIGN KEY (province_code, department_code)
        REFERENCES reference_data.province (code, department_code);

CREATE TABLE sales.buyer_address (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    label VARCHAR(120) NOT NULL,
    recipient_name VARCHAR(160) NOT NULL,
    recipient_phone VARCHAR(48),
    road_type VARCHAR(32),
    street_name VARCHAR(180),
    street_number VARCHAR(32),
    interior VARCHAR(64),
    address_line VARCHAR(500) NOT NULL,
    department_code VARCHAR(16) NOT NULL,
    province_code VARCHAR(16) NOT NULL,
    district_code VARCHAR(16) NOT NULL,
    postal_code VARCHAR(32),
    reference VARCHAR(500),
    receiving_instructions VARCHAR(1000),
    receiving_hours VARCHAR(240),
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    place_id VARCHAR(240),
    source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    default_address BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_buyer_address PRIMARY KEY (id),
    CONSTRAINT uq_buyer_address_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_buyer_address_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id),
    CONSTRAINT fk_buyer_address_membership FOREIGN KEY (tenant_id, workspace_id, buyer_membership_id)
        REFERENCES sales.client_account_membership (tenant_id, workspace_id, workspace_membership_id),
    CONSTRAINT fk_buyer_address_district_geo_hierarchy
        FOREIGN KEY (district_code, province_code, department_code)
        REFERENCES reference_data.district (code, province_code, department_code),
    CONSTRAINT ck_buyer_address_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    ),
    CONSTRAINT ck_buyer_address_source CHECK (
        source IN ('MANUAL', 'SAVED', 'CURRENT_LOCATION', 'MAP_PIN', 'PLACES')
    ),
    CONSTRAINT ck_buyer_address_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_buyer_address_version CHECK (version >= 0),
    CONSTRAINT ck_buyer_address_inactive_not_default CHECK (status = 'ACTIVE' OR NOT default_address)
);

CREATE INDEX ix_buyer_address_membership_status_updated
    ON sales.buyer_address (tenant_id, workspace_id, buyer_membership_id, status, default_address, updated_at DESC, id);

CREATE UNIQUE INDEX uq_buyer_address_default_active
    ON sales.buyer_address (tenant_id, workspace_id, buyer_membership_id)
    WHERE default_address AND status = 'ACTIVE';

COMMENT ON TABLE sales.buyer_address IS
    'Personal delivery locations owned by one Buyer membership. Separate from shared sales.client_account_address.';
COMMENT ON COLUMN sales.buyer_address.buyer_membership_id IS
    'Tenant-local reference to the exact Buyer membership; central membership status is revalidated before API access.';

ALTER TABLE sales.buyer_address ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.buyer_address FORCE ROW LEVEL SECURITY;

CREATE POLICY buyer_address_verified_membership_scope
    ON sales.buyer_address
    FOR ALL
    USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        AND buyer_membership_id::text = nullif(current_setting('app.current_membership_id', true), '')
    )
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        AND buyer_membership_id::text = nullif(current_setting('app.current_membership_id', true), '')
    );

REVOKE ALL PRIVILEGES ON TABLE sales.buyer_address FROM PUBLIC, nexa_runtime;
GRANT SELECT ON TABLE sales.buyer_address TO nexa_runtime;
GRANT INSERT (id, tenant_id, workspace_id, buyer_membership_id, label, recipient_name, recipient_phone,
        road_type, street_name, street_number, interior, address_line, postal_code, reference,
        receiving_instructions, receiving_hours, latitude, longitude, place_id, source,
        department_code, province_code, district_code)
    ON TABLE sales.buyer_address TO nexa_runtime;
GRANT UPDATE (label, recipient_name, recipient_phone, road_type, street_name, street_number, interior,
        address_line, postal_code, reference, receiving_instructions, receiving_hours, latitude,
        longitude, place_id, source, default_address, status, version, updated_at)
    ON TABLE sales.buyer_address TO nexa_runtime;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema ON schema.oid = relation.relnamespace
         WHERE schema.nspname = 'sales'
           AND relation.relname = 'buyer_address'
           AND relation.relrowsecurity
           AND relation.relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'Tenant V38 failed to force row security on sales.buyer_address';
    END IF;

    IF has_table_privilege('nexa_runtime', 'sales.buyer_address', 'DELETE') THEN
        RAISE EXCEPTION 'Tenant V38 unexpectedly granted DELETE on sales.buyer_address';
    END IF;

    IF has_table_privilege('nexa_runtime', 'sales.buyer_address', 'INSERT')
        OR has_table_privilege('nexa_runtime', 'sales.buyer_address', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'tenant_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'workspace_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'buyer_membership_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'created_at', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'default_address', 'INSERT') THEN
        RAISE EXCEPTION 'Tenant V38 granted broader Buyer address write privileges than required';
    END IF;

    IF NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'buyer_membership_id', 'INSERT')
        OR NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'default_address', 'UPDATE')
        OR NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'status', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V38 failed to grant required Buyer address write columns';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_indexes
         WHERE schemaname = 'sales'
           AND indexname = 'uq_buyer_address_default_active'
    ) THEN
        RAISE EXCEPTION 'Tenant V38 failed to enforce one active default address per Buyer membership';
    END IF;
END
$$;
