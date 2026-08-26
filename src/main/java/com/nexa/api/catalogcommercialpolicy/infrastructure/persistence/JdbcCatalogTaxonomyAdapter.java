package com.nexa.api.catalogcommercialpolicy.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConflictException;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogTaxonomyPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCatalogTaxonomyAdapter implements CatalogTaxonomyPort {
    private final JdbcTemplate jdbc;
    private final CatalogCommandIdempotencySupport idempotency;
    public JdbcCatalogTaxonomyAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; this.idempotency = new CatalogCommandIdempotencySupport(jdbc); }

    @Override
    public CatalogManagementModels.Page<CatalogManagementModels.CategoryView> categories(CatalogScope scope, int page, int size, String search) {
        pageCheck(page, size);
        String filter = " where tenant_id=? and workspace_id=? and status<>'ARCHIVED'";
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        if (search != null && !search.isBlank()) { filter += " and (lower(name) like lower(?) or lower(slug) like lower(?))"; String value = "%" + search.strip() + "%"; args.add(value); args.add(value); }
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) page * size);
        List<CatalogManagementModels.CategoryView> values = jdbc.query("select id,slug,name,description,parent_category_id,status,version from catalog_management.category" + filter + " order by name,id limit ? offset ?",
                (rs, row) -> new CatalogManagementModels.CategoryView(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getString(3), rs.getString(4), nullableUuid(rs.getObject(5, UUID.class)), rs.getString(6), rs.getLong(7)), pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) from catalog_management.category" + filter, Long.class, args.toArray());
        return new CatalogManagementModels.Page<>(values, page, size, total == null ? 0 : total);
    }

    @Override
    public CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description) {
        return createCategory(scope, parentId, slug, name, description, null);
    }

    @Override
    public CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description, String idempotencyKey) {
        String normalizedSlug = clean(slug, 100);
        String normalizedName = clean(name, 160);
        String normalizedDescription = nullable(description, 2000);
        UUID candidate = UUID.randomUUID();
        UUID id = idempotency.reserve(scope, "category:create", idempotencyKey,
                CatalogCommandIdempotencySupport.hash(parentId, normalizedSlug, normalizedName, normalizedDescription), candidate);
        if (!id.equals(candidate)) return category(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("category"));
        if (parentId != null) requireCategory(scope, parentId);
        jdbc.update("insert into catalog_management.category (id,tenant_id,workspace_id,parent_category_id,slug,name,description,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,'ACTIVE',0,?,?)",
                id, scope.tenantId(), scope.workspaceId(), parentId, normalizedSlug, normalizedName, normalizedDescription, now(), now());
        return category(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("category"));
    }

    @Override
    public CatalogManagementModels.CategoryView updateCategory(CatalogScope scope, UUID id, UUID parentId, String slug, String name, String description, long version) {
        requireCategory(scope, id);
        if (parentId != null) requireCategory(scope, parentId);
        int updated = jdbc.update("update catalog_management.category set parent_category_id=?,slug=?,name=?,description=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                parentId, clean(slug, 100), clean(name, 160), nullable(description, 2000), now(), scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConcurrencyException();
        return category(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("category"));
    }

    @Override
    public boolean categoryWouldCreateCycle(CatalogScope scope, UUID id, UUID parentId) {
        if (id == null || parentId == null) return false;
        if (id.equals(parentId)) return true;
        Boolean cycle = jdbc.queryForObject("with recursive descendants(id) as (select id from catalog_management.category where tenant_id=? and workspace_id=? and id=? union all select c.id from catalog_management.category c join descendants d on c.parent_category_id=d.id where c.tenant_id=? and c.workspace_id=?) select exists(select 1 from descendants where id=?)",
                Boolean.class, scope.tenantId(), scope.workspaceId(), id, scope.tenantId(), scope.workspaceId(), parentId);
        return Boolean.TRUE.equals(cycle);
    }

    @Override
    public CatalogManagementModels.CategoryView changeCategoryStatus(CatalogScope scope, UUID id, String status, long version) {
        requireCategory(scope, id);
        String normalized = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("ACTIVE") && !normalized.equals("INACTIVE") && !normalized.equals("ARCHIVED")) throw new IllegalArgumentException("Category status is invalid");
        int updated = jdbc.update("update catalog_management.category set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, now(), scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConcurrencyException();
        return category(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("category"));
    }

    @Override
    public CatalogManagementModels.Page<CatalogManagementModels.BrandView> brands(CatalogScope scope, int page, int size, String search) {
        pageCheck(page, size);
        String filter = " where tenant_id=? and workspace_id=? and status<>'ARCHIVED'";
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        if (search != null && !search.isBlank()) { filter += " and (lower(name) like lower(?) or lower(slug) like lower(?))"; String value = "%" + search.strip() + "%"; args.add(value); args.add(value); }
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) page * size);
        List<CatalogManagementModels.BrandView> values = jdbc.query("select id,slug,name,description,status,version from catalog_management.brand" + filter + " order by name,id limit ? offset ?",
                (rs, row) -> new CatalogManagementModels.BrandView(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)), pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) from catalog_management.brand" + filter, Long.class, args.toArray());
        return new CatalogManagementModels.Page<>(values, page, size, total == null ? 0 : total);
    }

    @Override
    public CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description) {
        return createBrand(scope, slug, name, description, null);
    }

    @Override
    public CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description, String idempotencyKey) {
        String normalizedSlug = clean(slug, 100);
        String normalizedName = clean(name, 160);
        String normalizedDescription = nullable(description, 2000);
        UUID candidate = UUID.randomUUID();
        UUID id = idempotency.reserve(scope, "brand:create", idempotencyKey,
                CatalogCommandIdempotencySupport.hash(normalizedSlug, normalizedName, normalizedDescription), candidate);
        if (!id.equals(candidate)) return brand(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("brand"));
        jdbc.update("insert into catalog_management.brand (id,tenant_id,workspace_id,slug,name,description,status,version,created_at,updated_at) values (?,?,?,?,? ,?,'ACTIVE',0,?,?)",
                id, scope.tenantId(), scope.workspaceId(), normalizedSlug, normalizedName, normalizedDescription, now(), now());
        return brand(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("brand"));
    }

    @Override
    public CatalogManagementModels.BrandView updateBrand(CatalogScope scope, UUID id, String slug, String name, String description, long version) {
        requireBrand(scope, id);
        int updated = jdbc.update("update catalog_management.brand set slug=?,name=?,description=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                clean(slug, 100), clean(name, 160), nullable(description, 2000), now(), scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConcurrencyException();
        return brand(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("brand"));
    }

    @Override
    public CatalogManagementModels.BrandView changeBrandStatus(CatalogScope scope, UUID id, String status, long version) {
        requireBrand(scope, id);
        String normalized = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("ACTIVE") && !normalized.equals("INACTIVE") && !normalized.equals("ARCHIVED")) throw new IllegalArgumentException("Brand status is invalid");
        int updated = jdbc.update("update catalog_management.brand set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, now(), scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConcurrencyException();
        return brand(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("brand"));
    }

    @Override
    public Optional<CatalogManagementModels.CategoryView> category(CatalogScope scope, UUID id) {
        return jdbc.query("select id,slug,name,description,parent_category_id,status,version from catalog_management.category where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new CatalogManagementModels.CategoryView(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getString(3), rs.getString(4), nullableUuid(rs.getObject(5, UUID.class)), rs.getString(6), rs.getLong(7)), scope.tenantId(), scope.workspaceId(), id).stream().findFirst();
    }

    @Override
    public Optional<CatalogManagementModels.BrandView> brand(CatalogScope scope, UUID id) {
        return jdbc.query("select id,slug,name,description,status,version from catalog_management.brand where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new CatalogManagementModels.BrandView(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)), scope.tenantId(), scope.workspaceId(), id).stream().findFirst();
    }

    private void requireCategory(CatalogScope scope, UUID id) { if (category(scope, id).isEmpty()) throw new CatalogResourceNotFoundException("category"); }
    private void requireBrand(CatalogScope scope, UUID id) { if (brand(scope, id).isEmpty()) throw new CatalogResourceNotFoundException("brand"); }
    private static void pageCheck(int page, int size) { if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid catalog pagination"); }
    private static String clean(String value, int max) { String normalized = java.util.Objects.requireNonNullElse(value, "").strip(); if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException("Catalog value is invalid"); return normalized; }
    private static String nullable(String value, int max) { if (value == null || value.isBlank()) return null; if (value.strip().length() > max) throw new IllegalArgumentException("Catalog value is invalid"); return value.strip(); }
    private static String nullableUuid(UUID value) { return value == null ? null : value.toString(); }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
}
