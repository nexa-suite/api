-- Install a fixed, one-shot central Buyer acceptance DDL helper under the
-- common owner of buyer_receipt_fact and proof_of_delivery. The restricted
-- migrator receives only EXECUTE on this no-argument helper.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    receipt_owner oid;
    pod_owner oid;
    receipt_oid oid;
    pod_oid oid;
    workspace_membership_oid oid;
    owner_super boolean;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V184 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 183 THEN
        RAISE EXCEPTION 'V184 preparation requires central Flyway version 183 to be current';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    SELECT * INTO runtime_role FROM pg_roles WHERE rolname = 'nexa_runtime';
    IF migration_role.oid IS NULL OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'nexa_migrator must remain a restricted direct login without role memberships';
    END IF;
    IF runtime_role.oid IS NULL OR NOT runtime_role.rolcanlogin OR runtime_role.rolsuper
        OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole OR runtime_role.rolreplication
        OR runtime_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = runtime_role.oid OR roleid = runtime_role.oid) THEN
        RAISE EXCEPTION 'V184 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.oid, relation.relowner
      INTO receipt_oid, receipt_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'buyer_receipt_fact'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO pod_oid, pod_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'proof_of_delivery'
       AND relation.relkind = 'r';
    SELECT relation.oid
      INTO workspace_membership_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'workspace_membership'
       AND relation.relkind = 'r';
    IF receipt_oid IS NULL OR pod_oid IS NULL OR workspace_membership_oid IS NULL
        OR receipt_owner <> pod_owner
        OR receipt_owner = runtime_role.oid
        OR pg_has_role(runtime_role.oid, receipt_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V184 requires buyer_receipt_fact and proof_of_delivery to share a non-runtime owner';
    END IF;

    SELECT rolsuper INTO owner_super FROM pg_roles WHERE oid = receipt_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> receipt_owner
        AND NOT pg_has_role(current_role_oid, receipt_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V184 preparation must run as the shared table owner or an authorized database administrator';
    END IF;
    IF NOT owner_super
        AND (NOT has_schema_privilege(receipt_owner, 'logistics', 'USAGE')
            OR NOT has_schema_privilege(receipt_owner, 'logistics', 'CREATE')
            OR NOT has_schema_privilege(receipt_owner, 'tenant_management', 'USAGE')
            OR NOT has_table_privilege(receipt_owner, workspace_membership_oid, 'REFERENCES')) THEN
        RAISE EXCEPTION 'The shared table owner must be able to own the helper and create the V184 workspace-membership foreign key';
    END IF;
    IF NOT has_schema_privilege(migration_role.oid, 'logistics', 'USAGE') THEN
        RAISE EXCEPTION 'nexa_migrator must have existing USAGE on logistics to call the V184 helper';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM pg_attribute
         WHERE attrelid = pod_oid
           AND attname = ANY (ARRAY['buyer_membership_id', 'buyer_receipt_fact_id'])
           AND attnum > 0 AND NOT attisdropped
    ) OR EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE (conrelid = receipt_oid AND conname = 'uq_buyer_receipt_scope_actor_v17')
            OR (conrelid = pod_oid AND conname IN (
                'fk_pod_buyer_membership_v17', 'fk_pod_buyer_receipt_v17',
                'ck_pod_receiver_identity_v17', 'ck_pod_buyer_receipt_binding_v17',
                'uq_pod_buyer_receipt_v17'))
    ) OR EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = pod_oid AND tgname = 'logistics_pod_lifecycle_v17' AND NOT tgisinternal
    ) OR EXISTS (
        SELECT 1 FROM pg_class index_row
        JOIN pg_namespace schema_row ON schema_row.oid = index_row.relnamespace
         WHERE schema_row.nspname = 'logistics' AND index_row.relname = 'ix_pod_buyer_membership_v17'
    ) OR to_regprocedure('logistics.prevent_pod_mutation_buyer_acceptance_v17()') IS NOT NULL THEN
        RAISE EXCEPTION 'V184 Buyer acceptance DDL must not exist before its Flyway migration';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION logistics.apply_v184_buyer_acceptance_to_delivery_pod()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    receipt_oid oid;
    pod_oid oid;
    receipt_owner oid;
    pod_owner oid;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO receipt_oid, receipt_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'buyer_receipt_fact'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO pod_oid, pod_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'proof_of_delivery'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR receipt_oid IS NULL OR pod_oid IS NULL
        OR receipt_owner <> pod_owner
        OR current_user <> pg_get_userbyid(receipt_owner) THEN
        RAISE EXCEPTION 'V184 Buyer acceptance DDL may only run as nexa_migrator under the common table owner';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM pg_attribute
         WHERE attrelid = pod_oid
           AND attname = ANY (ARRAY['buyer_membership_id', 'buyer_receipt_fact_id'])
           AND attnum > 0 AND NOT attisdropped
    ) OR EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE (conrelid = receipt_oid AND conname = 'uq_buyer_receipt_scope_actor_v17')
            OR (conrelid = pod_oid AND conname IN (
                'fk_pod_buyer_membership_v17', 'fk_pod_buyer_receipt_v17',
                'ck_pod_receiver_identity_v17', 'ck_pod_buyer_receipt_binding_v17',
                'uq_pod_buyer_receipt_v17'))
    ) OR EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = pod_oid AND tgname = 'logistics_pod_lifecycle_v17' AND NOT tgisinternal
    ) OR EXISTS (
        SELECT 1 FROM pg_class index_row
        JOIN pg_namespace schema_row ON schema_row.oid = index_row.relnamespace
         WHERE schema_row.nspname = 'logistics' AND index_row.relname = 'ix_pod_buyer_membership_v17'
    ) OR to_regprocedure('logistics.prevent_pod_mutation_buyer_acceptance_v17()') IS NOT NULL THEN
        RAISE EXCEPTION 'V184 Buyer acceptance DDL is already present';
    END IF;

    ALTER TABLE logistics.buyer_receipt_fact
        ADD CONSTRAINT uq_buyer_receipt_scope_actor_v17
            UNIQUE (tenant_id, workspace_id, id, buyer_membership_id);

    ALTER TABLE logistics.proof_of_delivery
        ADD COLUMN buyer_membership_id UUID,
        ADD COLUMN buyer_receipt_fact_id UUID,
        ALTER COLUMN receiver_name DROP NOT NULL,
        ADD CONSTRAINT fk_pod_buyer_membership_v17
            FOREIGN KEY (workspace_id, buyer_membership_id)
            REFERENCES tenant_management.workspace_membership (workspace_id, id),
        ADD CONSTRAINT fk_pod_buyer_receipt_v17
            FOREIGN KEY (tenant_id, workspace_id, buyer_receipt_fact_id, buyer_membership_id)
            REFERENCES logistics.buyer_receipt_fact (tenant_id, workspace_id, id, buyer_membership_id),
        ADD CONSTRAINT ck_pod_receiver_identity_v17
            CHECK (receiver_name IS NOT NULL OR buyer_membership_id IS NOT NULL OR status = 'PENDING'),
        ADD CONSTRAINT ck_pod_buyer_receipt_binding_v17
            CHECK ((buyer_receipt_fact_id IS NULL) = (buyer_membership_id IS NULL)),
        ADD CONSTRAINT uq_pod_buyer_receipt_v17
            UNIQUE (tenant_id, workspace_id, buyer_receipt_fact_id);

    CREATE INDEX ix_pod_buyer_membership_v17
        ON logistics.proof_of_delivery (tenant_id, workspace_id, buyer_membership_id, delivery_id)
        WHERE buyer_membership_id IS NOT NULL;

    COMMENT ON COLUMN logistics.proof_of_delivery.buyer_membership_id IS
        'Authenticated Buyer membership that explicitly accepted the delivery represented by this POD.';
    COMMENT ON COLUMN logistics.proof_of_delivery.buyer_receipt_fact_id IS
        'Immutable Buyer receipt fact finalized by this POD.';

    DROP TRIGGER IF EXISTS logistics_pod_lifecycle_v16 ON logistics.proof_of_delivery;

    CREATE OR REPLACE FUNCTION logistics.prevent_pod_mutation_buyer_acceptance_v17()
    RETURNS trigger
    LANGUAGE plpgsql
    AS $pod_trigger$
    DECLARE
        photo_added BOOLEAN;
        signature_added BOOLEAN;
        evidence_attached BOOLEAN;
        buyer_membership_bound BOOLEAN;
        buyer_receipt_bound BOOLEAN;
        matching_buyer_acceptance BOOLEAN;
    BEGIN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Proof of delivery is append-only';
        END IF;

        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
           OR NEW.id IS DISTINCT FROM OLD.id
           OR NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
           OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
           OR NEW.dispatch_order_id IS DISTINCT FROM OLD.dispatch_order_id
           OR NEW.receiver_name IS DISTINCT FROM OLD.receiver_name
           OR NEW.completed_at IS DISTINCT FROM OLD.completed_at
           OR NEW.notes IS DISTINCT FROM OLD.notes
           OR NEW.photo_object_key IS DISTINCT FROM OLD.photo_object_key
           OR NEW.signature_object_key IS DISTINCT FROM OLD.signature_object_key
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Proof of delivery identity is immutable';
        END IF;

        buyer_membership_bound := OLD.buyer_membership_id IS NULL
            AND NEW.buyer_membership_id IS NOT NULL;
        buyer_receipt_bound := OLD.buyer_receipt_fact_id IS NULL
            AND NEW.buyer_receipt_fact_id IS NOT NULL;
        IF OLD.buyer_membership_id IS NOT NULL
           AND NEW.buyer_membership_id IS DISTINCT FROM OLD.buyer_membership_id THEN
            RAISE EXCEPTION 'Proof of delivery Buyer identity is immutable';
        END IF;
        IF OLD.buyer_receipt_fact_id IS NOT NULL
           AND NEW.buyer_receipt_fact_id IS DISTINCT FROM OLD.buyer_receipt_fact_id THEN
            RAISE EXCEPTION 'Proof of delivery Buyer receipt link is immutable';
        END IF;

        photo_added := OLD.photo_evidence_object_id IS NULL AND NEW.photo_evidence_object_id IS NOT NULL;
        signature_added := OLD.signature_evidence_object_id IS NULL AND NEW.signature_evidence_object_id IS NOT NULL;
        evidence_attached := photo_added OR signature_added;

        IF (OLD.photo_evidence_object_id IS NOT NULL AND NEW.photo_evidence_object_id IS DISTINCT FROM OLD.photo_evidence_object_id)
           OR (OLD.signature_evidence_object_id IS NOT NULL AND NEW.signature_evidence_object_id IS DISTINCT FROM OLD.signature_evidence_object_id)
           OR (photo_added AND NOT NEW.photo_evidence_declared)
           OR (signature_added AND NOT NEW.signature_evidence_declared)
           OR (NOT photo_added AND NEW.photo_evidence_declared IS DISTINCT FROM OLD.photo_evidence_declared)
           OR (NOT signature_added AND NEW.signature_evidence_declared IS DISTINCT FROM OLD.signature_evidence_declared) THEN
            RAISE EXCEPTION 'Proof of delivery evidence is immutable';
        END IF;

        IF evidence_attached THEN
            IF photo_added = signature_added OR NOT (
                (OLD.status = 'PENDING' AND NEW.status = 'CAPTURED')
                OR (OLD.status = 'CAPTURED' AND NEW.status = 'CAPTURED')
            ) THEN
                RAISE EXCEPTION 'Proof of delivery evidence transition is invalid';
            END IF;
            IF NEW.sealed_at IS DISTINCT FROM OLD.sealed_at OR buyer_membership_bound OR buyer_receipt_bound THEN
                RAISE EXCEPTION 'Proof of delivery evidence transition is invalid';
            END IF;
            RETURN NEW;
        END IF;

        matching_buyer_acceptance := NEW.buyer_membership_id IS NOT NULL
            AND NEW.buyer_receipt_fact_id IS NOT NULL
            AND EXISTS (
                SELECT 1
                FROM logistics.buyer_receipt_fact receipt
                WHERE receipt.tenant_id = NEW.tenant_id
                  AND receipt.workspace_id = NEW.workspace_id
                  AND receipt.id = NEW.buyer_receipt_fact_id
                  AND receipt.buyer_membership_id = NEW.buyer_membership_id
                  AND receipt.decision = 'ACCEPTED'
                  AND (
                      receipt.delivery_id = NEW.delivery_id
                      OR (NEW.delivery_id IS NULL AND NEW.dispatch_order_id IS NOT NULL AND EXISTS (
                          SELECT 1
                          FROM logistics.delivery delivery
                          WHERE delivery.tenant_id = receipt.tenant_id
                            AND delivery.workspace_id = receipt.workspace_id
                            AND delivery.id = receipt.delivery_id
                            AND delivery.dispatch_order_id = NEW.dispatch_order_id
                      ))
                  )
                  AND (NEW.attempt_id IS NULL OR receipt.delivery_attempt_id = NEW.attempt_id)
            );

        IF OLD.status IN ('PENDING','CAPTURED') AND NEW.status = 'SEALED'
           AND NEW.sealed_at IS NOT NULL
           AND matching_buyer_acceptance THEN
            RETURN NEW;
        END IF;

        IF OLD.status = 'CAPTURED' AND NEW.status = 'SEALED'
           AND NEW.sealed_at IS NOT NULL
           AND NEW.buyer_membership_id IS NULL THEN
            RETURN NEW;
        END IF;

        IF OLD.status = 'CAPTURED' AND NEW.status = 'REJECTED'
           AND NOT buyer_membership_bound THEN
            RETURN NEW;
        END IF;

        IF OLD.status IN ('SEALED','COMPLETED') AND NEW.status = OLD.status
           AND NEW.sealed_at IS NOT DISTINCT FROM OLD.sealed_at
           AND (buyer_membership_bound OR buyer_receipt_bound) AND matching_buyer_acceptance THEN
            RETURN NEW;
        END IF;

        RAISE EXCEPTION 'Proof of delivery transition is invalid';
    END;
    $pod_trigger$;

    CREATE TRIGGER logistics_pod_lifecycle_v17
        BEFORE UPDATE OR DELETE ON logistics.proof_of_delivery FOR EACH ROW
        EXECUTE FUNCTION logistics.prevent_pod_mutation_buyer_acceptance_v17();

    EXECUTE 'REVOKE ALL ON FUNCTION logistics.apply_v184_buyer_acceptance_to_delivery_pod() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    receipt_owner oid;
    helper_oid oid;
    function_grantee record;
BEGIN
    SELECT relowner INTO receipt_owner FROM pg_class WHERE oid = 'logistics.buyer_receipt_fact'::regclass;
    helper_oid := 'logistics.apply_v184_buyer_acceptance_to_delivery_pod()'::regprocedure;

    EXECUTE format('ALTER FUNCTION logistics.apply_v184_buyer_acceptance_to_delivery_pod() OWNER TO %I',
        pg_get_userbyid(receipt_owner));

    FOR function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = helper_oid
           AND acl.grantee <> function_catalog.proowner
    LOOP
        IF function_grantee.grantee = 0 THEN
            EXECUTE 'REVOKE ALL ON FUNCTION logistics.apply_v184_buyer_acceptance_to_delivery_pod() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION logistics.apply_v184_buyer_acceptance_to_delivery_pod() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION logistics.apply_v184_buyer_acceptance_to_delivery_pod() TO nexa_migrator;
END;
$$;

COMMIT;
