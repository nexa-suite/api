package com.nexa.api.sales.presentation.reference;

import com.nexa.api.sales.application.reference.model.ReferenceOptionView;
import com.nexa.api.sales.application.reference.port.PeruGeographyUseCase;
import com.nexa.api.sales.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
    private static final String ACCESS_CONTEXT = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final PeruGeographyUseCase geography;

    public ReferenceDataController(PeruGeographyUseCase geography) { this.geography = geography; }

    @GetMapping("/{resource}")
    public List<ReferenceOptionView> list(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                          @PathVariable String resource,
                                          @RequestParam(required = false) String parentCode) {
        PeruGeographyLevel level = PeruGeographyLevel.fromResource(resource);
        return geography.list(context, level, parentCode);
    }
}
