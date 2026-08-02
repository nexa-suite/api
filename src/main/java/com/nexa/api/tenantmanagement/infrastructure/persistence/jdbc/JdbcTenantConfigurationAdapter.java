package com.nexa.api.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantmanagement.domain.model.configuration.CustomFieldDefinition;
import com.nexa.api.tenantmanagement.domain.model.configuration.NotificationPreference;
import com.nexa.api.tenantmanagement.domain.model.configuration.OperationalSettings;
import com.nexa.api.tenantmanagement.domain.model.configuration.OrganizationProfile;
import com.nexa.api.tenantmanagement.domain.model.configuration.ReferencePlanAssignment;
import com.nexa.api.tenantmanagement.domain.model.configuration.RegionalSettings;
import com.nexa.api.tenantmanagement.domain.model.configuration.TenantSecuritySettings;
import com.nexa.api.tenantmanagement.domain.model.configuration.UnitPreferences;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcTenantConfigurationAdapter implements TenantConfigurationPort {
	private final JdbcTemplate jdbc;

	public JdbcTenantConfigurationAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public Optional<OrganizationProfile> findOrganizationProfile(String tenantId) {
		ensureTenantDefaults(tenantId);
		return jdbc.query("select legal_name,display_name,business_identifier,operation_category,version from tenant_management.organization_settings where tenant_id=?",
				(rs, row) -> new OrganizationProfile(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)), uuid(tenantId)).stream().findFirst();
	}

	@Override
	public int updateOrganizationProfile(String tenantId, OrganizationProfile profile) {
		return jdbc.update("update tenant_management.organization_settings set legal_name=?,display_name=?,business_identifier=?,operation_category=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and version=?",
				profile.legalName(), profile.displayName(), profile.businessIdentifier(), profile.operationCategory(), uuid(tenantId), profile.version());
	}

	@Override
	public Optional<RegionalSettings> findRegionalSettings(String tenantId) {
		ensureTenantDefaults(tenantId);
		return jdbc.query("select timezone,language,currency,country_region,date_time_policy,locale,version from tenant_management.regional_settings where tenant_id=?",
				(rs, row) -> new RegionalSettings(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)), uuid(tenantId)).stream().findFirst();
	}

	@Override
	public int updateRegionalSettings(String tenantId, RegionalSettings settings) {
		return jdbc.update("update tenant_management.regional_settings set timezone=?,language=?,currency=?,country_region=?,date_time_policy=?,locale=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and version=?",
				settings.timezone(), settings.language(), settings.currency(), settings.countryRegion(), settings.dateTimePolicy(), settings.locale(), uuid(tenantId), settings.version());
	}

	@Override
	public Optional<UnitPreferences> findUnitPreferences(String tenantId) {
		ensureTenantDefaults(tenantId);
		return jdbc.query("select mass_unit,temperature_unit,distance_unit,volume_unit,version from tenant_management.unit_preferences where tenant_id=?",
				(rs, row) -> new UnitPreferences(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)), uuid(tenantId)).stream().findFirst();
	}

	@Override
	public int updateUnitPreferences(String tenantId, UnitPreferences preferences) {
		return jdbc.update("update tenant_management.unit_preferences set mass_unit=?,temperature_unit=?,distance_unit=?,volume_unit=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and version=?",
				preferences.massUnit(), preferences.temperatureUnit(), preferences.distanceUnit(), preferences.volumeUnit(), uuid(tenantId), preferences.version());
	}

	@Override
	public Optional<OperationalSettings> findOperationalSettings(String workspaceId) {
		ensureWorkspaceDefaults(workspaceId);
		return jdbc.query("select warehouse_preference_strategy,order_cutoff_policy,fulfillment_defaults,inventory_visibility_policy,buyer_availability_policy,operating_hours_start,operating_hours_end,order_cutoff_minutes,thermal_log_required,version from tenant_management.operational_settings where workspace_id=?",
				(rs, row) -> new OperationalSettings(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTime(6).toLocalTime(), rs.getTime(7).toLocalTime(), rs.getInt(8), rs.getBoolean(9), rs.getLong(10)), uuid(workspaceId)).stream().findFirst();
	}

	@Override
	public int updateOperationalSettings(String workspaceId, OperationalSettings settings) {
		return jdbc.update("update tenant_management.operational_settings set warehouse_preference_strategy=?,order_cutoff_policy=?,fulfillment_defaults=?,inventory_visibility_policy=?,buyer_availability_policy=?,operating_hours_start=?,operating_hours_end=?,order_cutoff_minutes=?,thermal_log_required=?,updated_at=current_timestamp,version=version+1 where workspace_id=? and version=?",
				settings.defaultWarehouseSelectionPolicy(), settings.orderCutoffPolicy(), settings.fulfillmentDefaults(), settings.inventoryVisibilityPolicy(), settings.buyerAvailabilityPolicy(), Time.valueOf(settings.operatingHoursStart()), Time.valueOf(settings.operatingHoursEnd()), settings.orderCutoffMinutes(), settings.thermalLogRequired(), uuid(workspaceId), settings.version());
	}

	@Override
	public Optional<TenantConfigurationModels.WorkspaceSettingsView> findWorkspaceSettings(String workspaceId) {
		ensureWorkspaceDefaults(workspaceId);
		return jdbc.query("select ws.workspace_id,ws.default_workspace_behavior,os.warehouse_preference_strategy,ws.version from tenant_management.workspace_settings ws join tenant_management.operational_settings os on os.workspace_id=ws.workspace_id where ws.workspace_id=?",
				(rs, row) -> new TenantConfigurationModels.WorkspaceSettingsView(rs.getObject(1).toString(), rs.getString(2), rs.getString(3), rs.getLong(4)), uuid(workspaceId)).stream().findFirst();
	}

	@Override
	public int updateWorkspaceSettings(String workspaceId, String defaultBehavior, String warehouseStrategy, long expectedVersion) {
		ensureWorkspaceDefaults(workspaceId);
		int updated = jdbc.update("update tenant_management.workspace_settings set default_workspace_behavior=?,updated_at=current_timestamp,version=version+1 where workspace_id=? and version=?",
				defaultBehavior, uuid(workspaceId), expectedVersion);
		if (updated == 1) {
			jdbc.update("update tenant_management.operational_settings set warehouse_preference_strategy=?,updated_at=current_timestamp,version=version+1 where workspace_id=?",
					warehouseStrategy, uuid(workspaceId));
		}
		return updated;
	}

	@Override
	public List<NotificationPreference> findNotificationPreferences(String workspaceId) {
		ensureWorkspaceDefaults(workspaceId);
		return jdbc.query("select event_category,channel,enabled,version from tenant_management.notification_preference where workspace_id=? order by event_category,channel",
				(rs, row) -> new NotificationPreference(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getLong(4)), uuid(workspaceId));
	}

	@Override
	public long notificationVersion(String workspaceId) {
		ensureWorkspaceDefaults(workspaceId);
		Long version = jdbc.queryForObject("select coalesce(max(version),0) from tenant_management.notification_preference where workspace_id=?", Long.class, uuid(workspaceId));
		return version == null ? 0 : version;
	}

	@Override
	public int updateNotificationPreference(String workspaceId, NotificationPreference preference) {
		return jdbc.update("update tenant_management.notification_preference set enabled=?,updated_at=current_timestamp,version=version+1 where workspace_id=? and event_category=? and channel=? and version=?",
				preference.enabled(), uuid(workspaceId), preference.eventCategory(), preference.channel(), preference.version());
	}

	@Override
	public Optional<TenantSecuritySettings> findTenantSecuritySettings(String tenantId) {
		ensureTenantDefaults(tenantId);
		return jdbc.query("select password_min_length,session_duration_minutes,invitation_expiration_hours,required_email_domain,version from tenant_management.tenant_security_settings where tenant_id=?",
				(rs, row) -> new TenantSecuritySettings(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getLong(5)), uuid(tenantId)).stream().findFirst();
	}

	@Override
	public int updateTenantSecuritySettings(String tenantId, TenantSecuritySettings settings) {
		return jdbc.update("update tenant_management.tenant_security_settings set password_min_length=?,session_duration_minutes=?,invitation_expiration_hours=?,required_email_domain=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and version=?",
				settings.passwordMinLength(), settings.sessionDurationMinutes(), settings.invitationExpirationHours(), settings.requiredEmailDomain(), uuid(tenantId), settings.version());
	}

	@Override
	public List<TenantConfigurationModels.CustomFieldView> findCustomFields(String tenantId, String workspaceId, String scope, boolean includeInactive) {
		StringBuilder sql = new StringBuilder("select id,field_key,label,field_kind,scope,required,unique_value,display_order,active,version from tenant_management.custom_field_definition where tenant_id=? and workspace_id=?");
		List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId)));
		if (scope != null && !scope.isBlank()) { sql.append(" and scope=?"); args.add(scope.toUpperCase(Locale.ROOT)); }
		if (!includeInactive) sql.append(" and active=true");
		sql.append(" order by display_order,label");
		return jdbc.query(sql.toString(), (rs, row) -> customField(rs), args.toArray());
	}

	@Override
	public Optional<TenantConfigurationModels.CustomFieldView> findCustomField(String tenantId, String workspaceId, UUID id) {
		return jdbc.query("select id,field_key,label,field_kind,scope,required,unique_value,display_order,active,version from tenant_management.custom_field_definition where tenant_id=? and workspace_id=? and id=?",
				(rs, row) -> customField(rs), uuid(tenantId), uuid(workspaceId), id).stream().findFirst();
	}

	@Override
	public int createCustomField(String tenantId, String workspaceId, CustomFieldDefinition definition) {
		Timestamp now = Timestamp.from(java.time.Instant.now());
		return jdbc.update("insert into tenant_management.custom_field_definition (id,tenant_id,workspace_id,field_key,label,field_kind,scope,required,unique_value,display_order,active,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
				definition.id(), uuid(tenantId), uuid(workspaceId), definition.fieldKey(), definition.label(), definition.fieldKind(), definition.scope(), definition.required(), definition.uniqueValue(), definition.displayOrder(), definition.active(), now, now);
	}

	@Override
	public int updateCustomField(String tenantId, String workspaceId, UUID id, CustomFieldDefinition definition, long expectedVersion) {
		return jdbc.update("update tenant_management.custom_field_definition set field_key=?,label=?,field_kind=?,scope=?,required=?,unique_value=?,display_order=?,active=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
				definition.fieldKey(), definition.label(), definition.fieldKind(), definition.scope(), definition.required(), definition.uniqueValue(), definition.displayOrder(), definition.active(), uuid(tenantId), uuid(workspaceId), id, expectedVersion);
	}

	@Override
	public int updateCustomFieldActive(String tenantId, String workspaceId, UUID id, boolean active, long expectedVersion) {
		return jdbc.update("update tenant_management.custom_field_definition set active=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
				active, uuid(tenantId), uuid(workspaceId), id, expectedVersion);
	}

	@Override
	public Optional<ReferencePlanAssignment> findReferencePlan(String tenantId) {
		ensureTenantDefaults(tenantId);
		return jdbc.query("select plan_code,monthly_price,seat_limit,workspace_limit,transaction_limit,version from tenant_management.reference_plan_assignment where tenant_id=?",
				(rs, row) -> new ReferencePlanAssignment(rs.getString(1), rs.getBigDecimal(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getLong(6)), uuid(tenantId)).stream().findFirst();
	}

	@Override
	public int updateReferencePlan(String tenantId, ReferencePlanAssignment plan, long expectedVersion) {
		return jdbc.update("update tenant_management.reference_plan_assignment set plan_code=?,monthly_price=?,seat_limit=?,workspace_limit=?,transaction_limit=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and version=?",
				plan.planCode(), plan.monthlyPrice(), plan.seatLimit(), plan.workspaceLimit(), plan.transactionLimit(), uuid(tenantId), expectedVersion);
	}

	@Override
	public TenantConfigurationModels.PlanUsageView planUsage(String tenantId, String workspaceId) {
		ReferencePlanAssignment plan = findReferencePlan(tenantId).orElseThrow();
		Integer activeUsers = jdbc.queryForObject("select count(*) from tenant_management.workspace_membership where workspace_id=? and status='ACTIVE'", Integer.class, uuid(workspaceId));
		Integer workspaces = jdbc.queryForObject("select count(*) from tenant_management.workspace where tenant_id=? and status='ACTIVE'", Integer.class, uuid(tenantId));
		Long transactions = jdbc.queryForObject("select (select count(*) from sales.purchase_request where tenant_id=?) + (select count(*) from sales.sales_order where tenant_id=?)", Long.class, uuid(tenantId), uuid(tenantId));
		return new TenantConfigurationModels.PlanUsageView(plan.planCode(), plan.monthlyPrice(), plan.seatLimit(), plan.workspaceLimit(), plan.transactionLimit(), activeUsers == null ? 0 : activeUsers, workspaces == null ? 0 : workspaces, transactions == null ? 0 : transactions, plan.version());
	}

	private void ensureTenantDefaults(String tenantId) {
		UUID tenant = uuid(tenantId);
		jdbc.update("insert into tenant_management.organization_settings (tenant_id,legal_name,display_name,business_identifier,operation_category,version,updated_at) select id,name,name,null,'B2B_COLD_CHAIN_DISTRIBUTOR',0,current_timestamp from tenant_management.tenant where id=? on conflict (tenant_id) do nothing", tenant);
		jdbc.update("insert into tenant_management.regional_settings (tenant_id,version,updated_at) values (?,0,current_timestamp) on conflict (tenant_id) do nothing", tenant);
		jdbc.update("insert into tenant_management.unit_preferences (tenant_id,version,updated_at) values (?,0,current_timestamp) on conflict (tenant_id) do nothing", tenant);
		jdbc.update("insert into tenant_management.tenant_security_settings (tenant_id,version,updated_at) values (?,0,current_timestamp) on conflict (tenant_id) do nothing", tenant);
		jdbc.update("insert into tenant_management.reference_plan_assignment (tenant_id,version,updated_at) values (?,0,current_timestamp) on conflict (tenant_id) do nothing", tenant);
	}

	private void ensureWorkspaceDefaults(String workspaceId) {
		UUID workspace = uuid(workspaceId);
		jdbc.update("insert into tenant_management.workspace_settings (workspace_id,version,updated_at) values (?,0,current_timestamp) on conflict (workspace_id) do nothing", workspace);
		jdbc.update("insert into tenant_management.operational_settings (workspace_id,version,updated_at) values (?,0,current_timestamp) on conflict (workspace_id) do nothing", workspace);
		for (String category : List.of("TEMPERATURE_ALERT", "DOCUMENT_REMINDER", "ORDER_STATUS", "INVITATION")) {
			for (String channel : List.of("IN_APP", "EMAIL")) {
				jdbc.update("insert into tenant_management.notification_preference (workspace_id,event_category,channel,enabled,version,updated_at) values (?,?,?,true,0,current_timestamp) on conflict (workspace_id,event_category,channel) do nothing", workspace, category, channel);
			}
		}
	}

	private static TenantConfigurationModels.CustomFieldView customField(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new TenantConfigurationModels.CustomFieldView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getInt(8), rs.getBoolean(9), rs.getLong(10));
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
