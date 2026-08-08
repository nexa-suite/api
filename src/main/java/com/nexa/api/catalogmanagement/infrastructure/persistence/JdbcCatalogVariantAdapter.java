package com.nexa.api.catalogmanagement.infrastructure.persistence;

import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.model.CatalogVariantModels;
import com.nexa.api.catalogmanagement.application.port.out.CatalogSkuPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogVariantPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Profile("!test")
@Repository
public class JdbcCatalogVariantAdapter implements CatalogVariantPort {
    private final JdbcTemplate jdbc;
    private final CatalogSkuPort skus;

    public JdbcCatalogVariantAdapter(JdbcTemplate jdbc, CatalogSkuPort skus) {
        this.jdbc = jdbc;
        this.skus = skus;
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogVariantModels.Page<CatalogVariantModels.VariantView> variants(CatalogScope scope, UUID familyId, int page, int size, String search) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String term = like(search);
        String where = "v.tenant_id=? and v.workspace_id=? and (? is null or v.family_id=?) and (? is null or lower(v.name) like lower(?) or lower(v.variant_code) like lower(?) or lower(f.name) like lower(?))";
        Object[] args = {scope.tenantId(), scope.workspaceId(), familyId, familyId, term, term, term, term};
        long total = jdbc.queryForObject("select count(*) from catalog_management.product_variant v join catalog_management.product_family f on f.tenant_id=v.tenant_id and f.workspace_id=v.workspace_id and f.id=v.family_id where " + where, Long.class, args);
        List<CatalogVariantModels.VariantView> items = jdbc.query(sql(where) + " order by v.name,v.id limit ? offset ?", (rs, row) -> variant(rs), concat(args, safeSize, safePage * safeSize));
        return new CatalogVariantModels.Page<>(items, safePage, safeSize, total);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogVariantModels.VariantView variant(CatalogScope scope, UUID id) {
        String where = "v.tenant_id=? and v.workspace_id=? and v.id=?";
        return jdbc.query(sql(where), (rs, row) -> variant(rs), scope.tenantId(), scope.workspaceId(), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Product variant not found"));
    }

    @Override
    @Transactional
    public CatalogVariantModels.VariantView insert(CatalogScope scope, UUID familyId, String code, String name, String description) {
        requireFamily(scope, familyId);
        String normalizedCode = required(code, 80, "Variant code");
        String normalizedName = required(name, 200, "Variant name");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into catalog_management.product_variant (id,tenant_id,workspace_id,family_id,variant_code,name,description,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,'DRAFT',0,?,?)",
                id, scope.tenantId(), scope.workspaceId(), familyId, normalizedCode, normalizedName, optional(description, 4000), Timestamp.from(now), Timestamp.from(now));
        return variant(scope, id);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, UUID variantId, int page, int size, String search) {
        variant(scope, variantId);
        return skus.skusByVariant(scope, page, size, search, variantId);
    }

    private void requireFamily(CatalogScope scope, UUID id) {
        Integer count = jdbc.queryForObject("select count(*) from catalog_management.product_family where tenant_id=? and workspace_id=? and id=?", Integer.class, scope.tenantId(), scope.workspaceId(), id);
        if (count == null || count != 1) throw new IllegalArgumentException("Product family not found");
    }

    private static String sql(String where) {
        return "select v.id,v.family_id,f.family_code,f.name family_name,v.variant_code,v.name,v.description,v.status,v.version,v.created_at,v.updated_at,"
                + "(select count(*) from catalog_management.sellable_sku s where s.tenant_id=v.tenant_id and s.workspace_id=v.workspace_id and s.variant_id=v.id) sku_count "
                + "from catalog_management.product_variant v join catalog_management.product_family f on f.tenant_id=v.tenant_id and f.workspace_id=v.workspace_id and f.id=v.family_id where " + where;
    }

    private static CatalogVariantModels.VariantView variant(ResultSet rs) throws SQLException {
        return new CatalogVariantModels.VariantView(uuid(rs, "id"), uuid(rs, "family_id"), rs.getString("family_code"),
                rs.getString("family_name"), rs.getString("variant_code"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getLong("sku_count"), rs.getLong("version"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Object[] concat(Object[] values, Object... tail) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + tail.length);
        System.arraycopy(tail, 0, result, values.length, tail.length);
        return result;
    }

    private static String uuid(ResultSet rs, String column) throws SQLException { UUID value = rs.getObject(column, UUID.class); return value == null ? null : value.toString(); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String like(String value) { return value == null || value.isBlank() ? null : "%" + value.trim().replace("%", "\\%").replace("_", "\\_") + "%"; }
    private static String required(String value, int max, String label) { String normalized = value == null ? "" : value.strip(); if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid"); return normalized; }
    private static String optional(String value, int max) { if (value == null || value.isBlank()) return null; String normalized = value.strip(); if (normalized.length() > max) throw new IllegalArgumentException("Variant description is invalid"); return normalized; }
}
