package com.nexa.api.salescommitment.presentation.reference;

import com.nexa.api.salescommitment.application.reference.model.ReferenceOptionView;
import com.nexa.api.salescommitment.application.reference.port.PeruGeographyUseCase;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reference")
@Profile("!test")
@Tag(name = "Reference Data")
@SecurityRequirement(name = "bearerAuth")
public final class ReferenceDataController {
    private static final String ACCESS_CONTEXT = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final PeruGeographyUseCase geography;

    public ReferenceDataController(PeruGeographyUseCase geography) { this.geography = geography; }

    @GetMapping("/{resource}")
    @Operation(operationId = "listReferenceData")
    public List<ReferenceOptionView> list(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                          @PathVariable String resource,
                                          @RequestParam(required = false) String parentCode) {
        PeruGeographyLevel level = PeruGeographyLevel.fromResource(resource);
        return geography.list(context, level, parentCode);
    }

    @GetMapping("/departments")
    @Operation(operationId = "listReferenceDepartments")
    public List<ReferenceOptionView> departments(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context) {
        return geography.list(context, PeruGeographyLevel.DEPARTMENT, null);
    }

    @GetMapping("/departments/{departmentCode}/provinces")
    @Operation(operationId = "listReferenceProvinces")
    public List<ReferenceOptionView> provinces(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                               @PathVariable String departmentCode) {
        return geography.list(context, PeruGeographyLevel.PROVINCE, departmentCode);
    }

    @GetMapping("/provinces/{provinceCode}/districts")
    @Operation(operationId = "listReferenceDistricts")
    public List<ReferenceOptionView> districts(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                               @PathVariable String provinceCode) {
        return geography.list(context, PeruGeographyLevel.DISTRICT, provinceCode);
    }

    @GetMapping("/road-types")
    @Operation(operationId = "listReferenceRoadTypes")
    public List<ReferenceOptionView> roadTypes(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context) {
        return geography.list(context, PeruGeographyLevel.ROAD_TYPE, null);
    }
}
