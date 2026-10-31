package com.nexa.api.support;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/** Reproduces the central migration bootstrap with a privileged bootstrap and direct restricted Flyway login. */
public final class CentralMigrationTestBootstrap {
	public static final String MIGRATOR_USERNAME = "nexa_migrator";
	public static final String MIGRATOR_PASSWORD = "test-only-central-migrator-password";
	public static final String RUNTIME_PASSWORD = "test-only-runtime-password";
	public static final String WORKSPACE_SCOPE_READER_PASSWORD = "test-only-workspace-scope-reader-password";
	public static final String INTERNAL_OPERATOR_DATABASE_PASSWORD = "test-only-internal-operator-database-password";
	public static final String PROVISIONER_PASSWORD = "bmV4YS10ZXN0LXByb3Zpc2lvbmVyLXNlY3JldC0xMjM=";

	private CentralMigrationTestBootstrap() { }

	public static void migrateToCurrent(PostgreSQLContainer postgres) {
		migrateToCurrent(postgres, () -> { });
	}

	public static void migrateToCurrent(PostgreSQLContainer postgres, Runnable afterV183) {
		createRestrictedRoles(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration").target("168").load().migrate();
		prepareV169(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("171").load().migrate();
		prepareV172BuyerTrackingView(postgres);
		prepareV173WorkspaceMembershipRls(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("173").load().migrate();
		prepareV174WorkspaceMembershipTenantScope(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("174").load().migrate();
		prepareV175WorkspaceScopeReader(postgres);
		prepareV176SecurityAuditRuntimeAcl(postgres);
		prepareV177InboxEventRls(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("177").load().migrate();
		prepareV178InternalOperatorScope(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("179").load().migrate();
		prepareV180ChangeEventRuntimeAcl(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("180").load().migrate();
		prepareV181InternalSupportRuntimeAcl(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("181").load().migrate();

		String organizationSettingsAccess = tableAccessSnapshot(postgres, "tenant_management.organization_settings");
		prepareV182OrganizationSettingsProfile(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("182").load().migrate();
		verifyTableAccessPreserved(postgres, "tenant_management.organization_settings", organizationSettingsAccess);
		verifyOwnerBoundHelper(postgres, "tenant_management.organization_settings",
				"tenant_management.apply_v182_organization_settings_profile()");

		String clientAccountAccess = tableAccessSnapshot(postgres, "sales.client_account");
		String workspaceMembershipAccess = tableAccessSnapshot(postgres, "tenant_management.workspace_membership");
		prepareV183BuyerAccountTypeAndSalesOwnerScope(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("183").load().migrate();
		verifyTableAccessPreserved(postgres, "sales.client_account", clientAccountAccess);
		verifyTableAccessPreserved(postgres, "tenant_management.workspace_membership", workspaceMembershipAccess);
		verifySharedOwnerBoundHelper(postgres, "sales.client_account", "tenant_management.workspace_membership",
				"tenant_management.apply_v183_buyer_account_type_sales_owner_scope()");
		afterV183.run();

		String buyerReceiptFactAccess = tableAccessSnapshot(postgres, "logistics.buyer_receipt_fact");
		String proofOfDeliveryAccess = tableAccessSnapshot(postgres, "logistics.proof_of_delivery");
		prepareV184BuyerAcceptancePod(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("184").load().migrate();
		verifyTableAccessPreserved(postgres, "logistics.buyer_receipt_fact", buyerReceiptFactAccess);
		verifyTableAccessPreserved(postgres, "logistics.proof_of_delivery", proofOfDeliveryAccess);
		verifySharedOwnerBoundHelper(postgres, "logistics.buyer_receipt_fact", "logistics.proof_of_delivery",
				"logistics.apply_v184_buyer_acceptance_to_delivery_pod()");

		prepareV185SkuVariantFamilyScope(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("185").load().migrate();

		prepareV186BomInventoryTemperatureAuditRead(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("186").load().migrate();
		verifyOwnerBoundHelper(postgres, "tenant_management.permission_definition",
				"tenant_management.apply_v186_bom_inventory_temperature_audit_read()");
		verifyBomTemperatureAuditPermission(postgres);

		String activeAttemptAccess = tableAccessSnapshot(postgres, "logistics.delivery_active_attempt");
		String activeAttemptRls = tableRlsSnapshot(postgres, "logistics.delivery_active_attempt");
		String handoffTokenAccess = tableAccessSnapshot(postgres, "logistics.delivery_handoff_token");
		String handoffTokenRls = tableRlsSnapshot(postgres, "logistics.delivery_handoff_token");
		prepareV188BuyerHandoffToActiveAttempt(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("188").load().migrate();
		verifyTableAccessPreserved(postgres, "logistics.delivery_active_attempt", activeAttemptAccess);
		verifyTableAccessPreserved(postgres, "logistics.delivery_handoff_token", handoffTokenAccess);
		verifyTableRlsPreserved(postgres, "logistics.delivery_active_attempt", activeAttemptRls);
		verifyTableRlsPreserved(postgres, "logistics.delivery_handoff_token", handoffTokenRls);
		verifyV188MigrationState(postgres);

		String accountMembershipRls = tableRlsSnapshot(postgres, "sales.client_account_membership");
		String clientAccountRls = tableRlsSnapshot(postgres, "sales.client_account");
		prepareV189ClientAccountBuyerMemberships(postgres);
		Flyway.configure().dataSource(postgres.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").load().migrate();
		verifyTableRlsPreserved(postgres, "sales.client_account_membership", accountMembershipRls);
		verifyTableRlsPreserved(postgres, "sales.client_account", clientAccountRls);
		verifySharedOwnerBoundHelper(postgres, "sales.client_account", "sales.client_account_membership",
				"sales.apply_v189_client_account_buyer_memberships()");
		verifyV189BuyerMembershipState(postgres);
	}

	private static void createRestrictedRoles(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute("""
				DO $$
				BEGIN
			    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
			        IF EXISTS (SELECT 1 FROM pg_auth_members
			                   WHERE member = (SELECT oid FROM pg_roles WHERE rolname = 'nexa_runtime')
			                      OR roleid = (SELECT oid FROM pg_roles WHERE rolname = 'nexa_runtime')) THEN
			            RAISE EXCEPTION 'Test runtime role must not have role memberships';
			        END IF;
			        ALTER ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
			            NOREPLICATION NOBYPASSRLS PASSWORD '%1$s';
			    ELSE
			        CREATE ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
			            NOREPLICATION NOBYPASSRLS PASSWORD '%1$s';
			    END IF;
			    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_migrator') THEN
			        CREATE ROLE nexa_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
			            NOREPLICATION NOBYPASSRLS PASSWORD 'test-only-central-migrator-password';
			    END IF;
			    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_tenant_database_provisioner') THEN
			        CREATE ROLE nexa_tenant_database_provisioner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB
			            NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'test-only-central-provisioner-password';
			    END IF;
			    CREATE ROLE nexa_workspace_scope_reader NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB
		            NOCREATEROLE NOREPLICATION NOBYPASSRLS;
			    CREATE ROLE nexa_workspace_scope_reader_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB
		            NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'test-only-workspace-scope-reader-password';
			    GRANT nexa_workspace_scope_reader TO nexa_workspace_scope_reader_login
			            WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
			    CREATE ROLE nexa_internal_operator NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB
			        NOCREATEROLE NOREPLICATION NOBYPASSRLS;
			    CREATE ROLE nexa_internal_operator_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB
			        NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'test-only-internal-operator-database-password';
			    GRANT nexa_internal_operator TO nexa_internal_operator_login
			            WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
			END
			$$;
			""".formatted(RUNTIME_PASSWORD));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not create the restricted central migration roles", exception);
		}
	}

	private static void prepareV169(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v169-tenant-provisioner.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not provision exact V169 scope-helper grants", exception);
		}
	}

	private static void prepareV172BuyerTrackingView(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v172-buyer-tracking-view.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the restricted V172 tracking view owner", exception);
		}
	}

	private static void prepareV173WorkspaceMembershipRls(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			String preparation = Files.readString(Path.of("ops/database/prepare-v173-workspace-membership-rls.sql"));
			statement.execute(preparation);
			statement.execute(preparation);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V173 membership RLS DDL helper", exception);
		}
	}

	private static void prepareV174WorkspaceMembershipTenantScope(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			String preparation = Files.readString(Path.of("ops/database/prepare-v174-workspace-membership-tenant-scope.sql"));
			statement.execute(preparation);
			statement.execute(preparation);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V174 Tenant/Workspace membership DDL helper", exception);
		}
	}

	private static void prepareV175WorkspaceScopeReader(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			String preparation = Files.readString(Path.of("ops/database/prepare-v175-workspace-scope-reader.sql"));
			statement.execute(preparation);
			statement.execute(preparation);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V175 Workspace scope-reader DDL helper", exception);
		}
	}

	private static void prepareV176SecurityAuditRuntimeAcl(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			String preparation = Files.readString(Path.of("ops/database/prepare-v176-security-audit-runtime-acl.sql"));
			statement.execute(preparation);
			statement.execute(preparation);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V176 Security Audit ACL helper", exception);
		}
	}

	private static void prepareV177InboxEventRls(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			String preparation = Files.readString(Path.of("ops/database/prepare-v177-inbox-event-rls.sql"));
			statement.execute(preparation);
			statement.execute(preparation);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V177 inbox RLS helper", exception);
		}
	}

	private static void prepareV178InternalOperatorScope(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v178-internal-operator-scope.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V178 internal-operator scope helpers", exception);
		}
	}

	private static void prepareV180ChangeEventRuntimeAcl(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			String preparation = Files.readString(Path.of("ops/database/prepare-v180-change-event-runtime-acl.sql"));
			statement.execute(preparation);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V180 change-event ACL helper", exception);
		}
	}

	private static void prepareV181InternalSupportRuntimeAcl(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v181-internal-support-runtime-acl.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V181 internal-support ACL helper", exception);
		}
	}

	private static void prepareV182OrganizationSettingsProfile(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v182-organization-settings-profile.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V182 organization-settings profile DDL helper", exception);
		}
	}

	private static void prepareV183BuyerAccountTypeAndSalesOwnerScope(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v183-buyer-account-type-sales-owner-scope.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V183 Buyer account and sales-owner DDL helper", exception);
		}
	}

	private static void prepareV184BuyerAcceptancePod(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v184-buyer-acceptance-pod.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V184 Buyer acceptance and POD DDL helper", exception);
		}
	}

	private static void prepareV185SkuVariantFamilyScope(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v185-sku-variant-family-scope.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V185 SKU variant family-scope helper", exception);
		}
	}

	private static void prepareV186BomInventoryTemperatureAuditRead(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v186-bom-inventory-temperature-audit-read.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V186 BOM temperature-audit permission helper", exception);
		}
	}

	private static void prepareV188BuyerHandoffToActiveAttempt(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("ops/database/prepare-v188-buyer-handoff-to-active-attempt.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V188 Buyer handoff DDL helper", exception);
		}
	}

	private static void prepareV189ClientAccountBuyerMemberships(PostgreSQLContainer postgres) {
		try (Connection connection = adminConnection(postgres); Statement statement = connection.createStatement()) {
			String preparation = Files.readString(Path.of("ops/database/prepare-v189-client-account-buyer-memberships.sql"));
			statement.execute(preparation);
			statement.execute(preparation);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare the fixed V189 Buyer membership DDL helper", exception);
		}
	}

	private static void verifyBomTemperatureAuditPermission(PostgreSQLContainer postgres) {
		String sql = """
			SELECT (SELECT count(*) = 1
			          FROM tenant_management.permission_definition permission
			         WHERE permission.permission_key = 'inventory.temperature.audit.read'
			           AND permission.permission_group = 'INVENTORY'
			           AND permission.display_name = 'Audit lot temperatures'
			           AND permission.description = 'Read persisted lot temperature observations within the active Tenant and Workspace'
			           AND permission.reserved)
			   AND (SELECT count(*) = 1
			          FROM tenant_management.role_permission role_permission
			          JOIN tenant_management.role_definition role_definition
			            ON role_definition.id = role_permission.role_id
			         WHERE role_permission.permission_key = 'inventory.temperature.audit.read'
			           AND role_definition.tenant_id IS NULL
			           AND role_definition.workspace_id IS NULL
			           AND role_definition.code = 'business_operations_manager'
			           AND role_definition.role_type = 'SYSTEM_TEMPLATE'
			           AND role_definition.status = 'ACTIVE')
			   AND NOT EXISTS (
			       SELECT 1
			         FROM tenant_management.role_permission role_permission
			         JOIN tenant_management.role_definition role_definition
			           ON role_definition.id = role_permission.role_id
			        WHERE role_permission.permission_key = 'inventory.temperature.audit.read'
			          AND NOT (role_definition.tenant_id IS NULL
			                   AND role_definition.workspace_id IS NULL
			                   AND role_definition.code = 'business_operations_manager'
		                   AND role_definition.role_type = 'SYSTEM_TEMPLATE'
			                   AND role_definition.status = 'ACTIVE'))
			""";
		if (!queryBoolean(postgres, sql)) {
			throw new IllegalStateException("V186 did not grant the exact reserved temperature-audit permission only to BOM");
		}
	}

	private static String tableAccessSnapshot(PostgreSQLContainer postgres, String tableName) {
		String sql = """
			SELECT relation.relowner::text || '|' || COALESCE(relation.relacl::text, '<null>') || '|' ||
			       COALESCE((
			           SELECT string_agg(attribute.attname || ':' || COALESCE(attribute.attacl::text, '<null>'), '|' ORDER BY attribute.attnum)
			             FROM pg_attribute attribute
			            WHERE attribute.attrelid = relation.oid
			              AND attribute.attnum > 0
			              AND NOT attribute.attisdropped
			             AND attribute.attacl IS NOT NULL
			       ), '')
			  FROM pg_class relation
			 WHERE relation.oid = ?::regclass
			""";
		try (Connection connection = adminConnection(postgres); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, tableName);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("Migration target table is missing: " + tableName);
				return result.getString(1);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Could not snapshot ownership and ACLs for " + tableName, exception);
		}
	}

	private static String tableRlsSnapshot(PostgreSQLContainer postgres, String tableName) {
		String sql = """
			SELECT relation.relrowsecurity::text || '|' || relation.relforcerowsecurity::text || '|' ||
			       COALESCE(string_agg(
			           policy.polname || ':' || policy.polcmd::text || ':' || policy.polroles::text || ':' ||
			           COALESCE(pg_get_expr(policy.polqual, policy.polrelid), '<null>') || ':' ||
			           COALESCE(pg_get_expr(policy.polwithcheck, policy.polrelid), '<null>'),
			           '|' ORDER BY policy.polname), '')
			  FROM pg_class relation
			  LEFT JOIN pg_policy policy ON policy.polrelid = relation.oid
			 WHERE relation.oid = ?::regclass
			 GROUP BY relation.relrowsecurity, relation.relforcerowsecurity
			""";
		try (Connection connection = adminConnection(postgres); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, tableName);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("Migration target table is missing: " + tableName);
				return result.getString(1);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Could not snapshot row-security state for " + tableName, exception);
		}
	}

	private static void verifyTableAccessPreserved(PostgreSQLContainer postgres, String tableName, String before) {
		String after = tableAccessSnapshot(postgres, tableName);
		if (!before.equals(after)) {
			throw new IllegalStateException("Migration changed table ownership or ACLs for " + tableName);
		}
	}

	private static void verifyTableRlsPreserved(PostgreSQLContainer postgres, String tableName, String before) {
		String after = tableRlsSnapshot(postgres, tableName);
		if (!before.equals(after)) {
			throw new IllegalStateException("Migration changed row-security flags or policies for " + tableName);
		}
	}

	private static void verifyOwnerBoundHelper(PostgreSQLContainer postgres, String tableName, String helperName) {
		String sql = """
			SELECT helper.proowner = relation.relowner
			   AND NOT has_function_privilege('nexa_migrator', helper.oid, 'EXECUTE')
			   AND NOT has_function_privilege('nexa_runtime', helper.oid, 'EXECUTE')
			   AND NOT has_function_privilege('nexa_tenant_database_provisioner', helper.oid, 'EXECUTE')
			   AND NOT EXISTS (
			       SELECT 1
			         FROM aclexplode(COALESCE(helper.proacl, acldefault('f', helper.proowner))) acl
			        WHERE acl.grantee = 0
			   )
			  FROM pg_proc helper
			  JOIN pg_class relation ON relation.oid = ?::regclass
			 WHERE helper.oid = to_regprocedure(?)
			""";
		if (!queryBoolean(postgres, sql, tableName, helperName)) {
			throw new IllegalStateException("Owner-bound helper is missing, broadly executable, or not revoked after migration: " + helperName);
		}
	}

	private static void verifySharedOwnerBoundHelper(PostgreSQLContainer postgres, String firstTable, String secondTable,
			String helperName) {
		String sql = """
			SELECT first_relation.relowner = second_relation.relowner
			   AND helper.proowner = first_relation.relowner
			   AND helper.prosecdef
			   AND COALESCE(helper.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
			   AND NOT has_function_privilege('nexa_migrator', helper.oid, 'EXECUTE')
			   AND NOT has_function_privilege('nexa_runtime', helper.oid, 'EXECUTE')
			   AND NOT has_function_privilege('nexa_tenant_database_provisioner', helper.oid, 'EXECUTE')
			   AND NOT EXISTS (
			       SELECT 1
			         FROM aclexplode(COALESCE(helper.proacl, acldefault('f', helper.proowner))) acl
			        WHERE acl.grantee = 0
			   )
			  FROM pg_class first_relation
			  JOIN pg_class second_relation ON second_relation.oid = ?::regclass
			  JOIN pg_proc helper ON helper.oid = to_regprocedure(?)
			 WHERE first_relation.oid = ?::regclass
			""";
		if (!queryBoolean(postgres, sql, secondTable, helperName, firstTable)) {
			throw new IllegalStateException("Shared tables or their owner-bound helper have invalid ownership or execution grants");
		}
	}

	private static void verifyV188MigrationState(PostgreSQLContainer postgres) {
		verifySharedOwnerBoundHelper(postgres, "logistics.delivery_active_attempt", "logistics.delivery_handoff_token",
				"logistics.apply_v188_buyer_handoff_to_active_attempt()");
		String sql = """
			SELECT (SELECT count(*) = 4
			          FROM pg_constraint
			         WHERE (conrelid = 'logistics.delivery_active_attempt'::regclass
			                AND conname = 'uq_delivery_active_attempt_scope_binding'
			                AND contype = 'u' AND convalidated)
			            OR (conrelid = 'logistics.delivery_handoff_token'::regclass
			                AND conname IN ('ck_handoff_token_attempt_binding_v188',
			                                'fk_handoff_token_attempt_v188',
			                                'fk_handoff_token_active_attempt_v188')
			                AND convalidated))
			   AND (SELECT NOT attnotnull
			          FROM pg_attribute
			         WHERE attrelid = 'logistics.delivery_handoff_token'::regclass
			           AND attname = 'delivery_attempt_id' AND attnum > 0 AND NOT attisdropped)
			   AND (SELECT NOT attnotnull AND atttypid = 'uuid'::regtype
			          FROM pg_attribute
			         WHERE attrelid = 'logistics.delivery_handoff_token'::regclass
			           AND attname = 'delivery_active_attempt_id' AND attnum > 0 AND NOT attisdropped)
			   AND EXISTS (
			       SELECT 1
			         FROM pg_index index_row
			         JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
			        WHERE index_row.indrelid = 'logistics.delivery_handoff_token'::regclass
			          AND index_class.relname = 'uq_handoff_token_active_delivery_attempt'
			          AND index_row.indisunique AND index_row.indisvalid
			          AND lower(pg_get_expr(index_row.indexprs, index_row.indrelid)) LIKE '%coalesce%'
			          AND lower(pg_get_expr(index_row.indexprs, index_row.indrelid)) LIKE '%delivery_active_attempt_id%'
			          AND pg_get_expr(index_row.indpred, index_row.indrelid) LIKE '%status%ACTIVE%')
			   AND EXISTS (
			       SELECT 1 FROM pg_trigger trigger_row
			        WHERE trigger_row.tgrelid = 'logistics.delivery_handoff_token'::regclass
			          AND trigger_row.tgname = 'logistics_delivery_handoff_token_lifecycle_v188'
			          AND NOT trigger_row.tgisinternal
			          AND trigger_row.tgfoid = to_regprocedure(
			              'logistics.prevent_delivery_handoff_token_mutation_v188()'))
			   AND NOT EXISTS (
			       SELECT 1 FROM pg_trigger trigger_row
			        WHERE trigger_row.tgrelid = 'logistics.delivery_handoff_token'::regclass
			          AND trigger_row.tgname = 'logistics_delivery_handoff_token_lifecycle_v17'
			          AND NOT trigger_row.tgisinternal)
			""";
		if (!queryBoolean(postgres, sql)) {
			throw new IllegalStateException("V188 did not preserve delivery ownership/RLS while applying the Buyer handoff binding");
		}
	}

	private static void verifyV189BuyerMembershipState(PostgreSQLContainer postgres) {
		String sql = """
			SELECT NOT has_table_privilege('nexa_runtime', 'sales.client_account_membership', 'DELETE')
			   AND NOT has_table_privilege('nexa_migrator', 'sales.client_account_membership', 'SELECT')
			   AND NOT has_table_privilege('nexa_migrator', 'sales.client_account_membership', 'INSERT')
			   AND NOT has_table_privilege('nexa_migrator', 'sales.client_account_membership', 'UPDATE')
			   AND NOT has_table_privilege('nexa_migrator', 'sales.client_account_membership', 'DELETE')
			   AND NOT has_table_privilege('nexa_migrator', 'sales.client_account_membership', 'TRUNCATE')
			   AND to_regclass('sales.uq_client_account_one_buyer') IS NULL
			   AND to_regclass('sales.uq_client_account_individual_buyer_v189') IS NOT NULL
			   AND (SELECT relrowsecurity AND relforcerowsecurity
			          FROM pg_class WHERE oid = 'sales.client_account_membership'::regclass)
			   AND (SELECT relrowsecurity AND relforcerowsecurity
			          FROM pg_class WHERE oid = 'sales.client_account'::regclass)
			   AND (SELECT count(*) = 4
			          FROM pg_attribute
			         WHERE attrelid = 'sales.client_account_membership'::regclass
			           AND attname IN ('status', 'updated_at', 'version', 'individual_account_id')
			           AND attnum > 0 AND NOT attisdropped)
			   AND EXISTS (
			       SELECT 1 FROM pg_trigger
			        WHERE tgrelid = 'sales.client_account_membership'::regclass
			          AND tgname = 'client_account_membership_identity_guard_v189'
			          AND NOT tgisinternal)
			   AND EXISTS (
			       SELECT 1 FROM pg_trigger
			        WHERE tgrelid = 'sales.client_account'::regclass
			          AND tgname = 'client_account_type_membership_guard_v189'
			          AND NOT tgisinternal)
			""";
		if (!queryBoolean(postgres, sql)) {
			throw new IllegalStateException("V189 Buyer membership lifecycle, isolation, or runtime grants are invalid");
		}
	}

	private static boolean queryBoolean(PostgreSQLContainer postgres, String sql, String... parameters) {
		try (Connection connection = adminConnection(postgres); PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++) statement.setString(index + 1, parameters[index]);
			try (var result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Could not verify restricted migration helper state", exception);
		}
	}

	private static Connection adminConnection(PostgreSQLContainer postgres) throws Exception {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}
}
