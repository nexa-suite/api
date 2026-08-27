package com.nexa.api.salescommitment.infrastructure.reference;

import com.nexa.api.salescommitment.application.reference.port.PeruGeographyPersistencePort;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyOption;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyPath;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!test")
public class JdbcPeruGeographyPersistenceAdapter implements PeruGeographyPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcPeruGeographyPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<PeruGeographyOption> list(PeruGeographyLevel level, String parentCode) {
        String normalizedParent = parentCode == null || parentCode.isBlank() ? null : parentCode.trim();
        return switch (level) {
            case DEPARTMENT -> jdbc.query("select code,name from reference_data.department order by code",
                    (rs, row) -> option(rs, PeruGeographyLevel.DEPARTMENT, null));
            case PROVINCE -> jdbc.query("select code,name,department_code from reference_data.province where (? is null or department_code=?) order by code",
                    (rs, row) -> option(rs, PeruGeographyLevel.PROVINCE, rs.getString(3)), normalizedParent, normalizedParent);
            case DISTRICT -> jdbc.query("select code,name,province_code from reference_data.district where (? is null or province_code=?) order by code",
                    (rs, row) -> option(rs, PeruGeographyLevel.DISTRICT, rs.getString(3)), normalizedParent, normalizedParent);
            case ROAD_TYPE -> jdbc.query("select code,name from reference_data.road_type order by code",
                    (rs, row) -> option(rs, PeruGeographyLevel.ROAD_TYPE, null));
        };
    }

    @Override
    public Optional<PeruGeographyPath> resolve(String departmentCode, String provinceCode, String districtCode) {
        if (blank(departmentCode) || blank(provinceCode) || blank(districtCode)) return Optional.empty();
        return jdbc.query("select d.code,d.name,p.code,p.name,p.department_code,x.code,x.name,x.province_code "
                        + "from reference_data.department d join reference_data.province p on p.department_code=d.code "
                        + "join reference_data.district x on x.department_code=p.department_code and x.province_code=p.code "
                        + "where d.code=? and p.code=? and x.code=?",
                (org.springframework.jdbc.core.ResultSetExtractor<Optional<PeruGeographyPath>>) rs -> rs.next()
                        ? Optional.of(new PeruGeographyPath(
                                option(rs, PeruGeographyLevel.DEPARTMENT, null, 1, 2),
                                option(rs, PeruGeographyLevel.PROVINCE, rs.getString(5), 3, 4),
                                option(rs, PeruGeographyLevel.DISTRICT, rs.getString(8), 6, 7)))
                        : Optional.empty(), departmentCode.trim(), provinceCode.trim(), districtCode.trim());
    }

    private static PeruGeographyOption option(ResultSet rs, PeruGeographyLevel level, String parent) throws java.sql.SQLException {
        return option(rs, level, parent, 1, 2);
    }

    private static PeruGeographyOption option(ResultSet rs, PeruGeographyLevel level, String parent,
                                              int codeColumn, int labelColumn) throws java.sql.SQLException {
        String code = rs.getString(codeColumn);
        return new PeruGeographyOption(stableId(code), level, code, rs.getString(labelColumn), parent, true);
    }

    private static long stableId(String code) {
        try { return Long.parseLong(code); }
        catch (NumberFormatException ignored) { return Integer.toUnsignedLong(code.hashCode()); }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
