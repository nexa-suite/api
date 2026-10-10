package com.nexa.api.creditreceivables.presentation;

import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationQuery;
import com.nexa.api.creditreceivables.application.service.CreditAccountConfigurationApplicationService;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDirectoryQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** BOM/Company Owner credit setup endpoints backed by the routed Tenant database. */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
@Validated
@RequestMapping("/api/v1")
@Tag(name = "Credit and Receivables")
@SecurityRequirement(name = "bearerAuth")
public final class CreditAccountConfigurationController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final CreditAccountConfigurationApplicationService service;

    public CreditAccountConfigurationController(CreditAccountConfigurationApplicationService service) {
        this.service = service;
    }

    @GetMapping("/credit-account-configurations/customer-accounts")
    @Operation(operationId = "listCreditConfigurationCustomerAccounts",
            description = "Lists only the tenant-scoped customer account id, commercial name and status for credit configuration. Requires PLATFORM, client.credit.configuration.manage and a verified Business Operations Manager or Company Owner role.")
    public ResponseEntity<CandidatePage> candidates(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        CustomerAccountDirectoryQuery.Page result = service.candidates(context, search, status, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CandidatePage.from(result));
    }

    @GetMapping("/client-accounts/{clientAccountId}/credit-account")
    @Operation(operationId = "getClientCreditAccountConfiguration",
            description = "Reads the selected tenant-scoped customer's credit configuration and current used-credit buckets. Currency is required. NOT_CONFIGURED returns null limit, exposure buckets and version.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Credit configuration returned", headers = @Header(name = "ETag", description = "Current version; absent for NOT_CONFIGURED")), @ApiResponse(responseCode = "404", description = "Customer account not found"), @ApiResponse(responseCode = "403", description = "Missing role, permission or surface"), @ApiResponse(responseCode = "503", description = "Tenant credit configuration is unavailable")})
    public ResponseEntity<ConfigurationResponse> read(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID clientAccountId,
            @Parameter(description = "Three-letter currency code; explicit currency is required")
            @RequestParam @Pattern(regexp = "[A-Za-z]{3}") String currency) {
        CreditAccountConfigurationQuery.Snapshot result = service.read(context, clientAccountId, currency);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (result.version() != null) response.eTag(etag(result.version()));
        return response.body(ConfigurationResponse.from(result));
    }

    @PutMapping("/client-accounts/{clientAccountId}/credit-account")
    @Operation(operationId = "configureClientCreditAccount",
            description = "Creates or updates one tenant-scoped credit account. First configuration requires If-None-Match: *; updates require If-Match. Every write requires Idempotency-Key. Lowering the limit below financed exposure plus outstanding receivables plus active reservations conflicts.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Credit account created", headers = @Header(name = "ETag", description = "Created version")), @ApiResponse(responseCode = "200", description = "Credit account updated", headers = @Header(name = "ETag", description = "Updated version")), @ApiResponse(responseCode = "412", description = "Precondition failed"), @ApiResponse(responseCode = "428", description = "If-Match or If-None-Match precondition required"), @ApiResponse(responseCode = "409", description = "Credit floor, closed account or idempotency conflict"), @ApiResponse(responseCode = "403", description = "Missing role, permission or surface"), @ApiResponse(responseCode = "503", description = "Tenant credit configuration is unavailable")})
    public ResponseEntity<ConfigurationResponse> configure(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID clientAccountId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConfigurationRequest request) {
        CreditAccountConfigurationApplicationService.ConfigurationRequest command =
                new CreditAccountConfigurationApplicationService.ConfigurationRequest(
                        request.currency(), request.creditLimit(), request.active());
        CreditAccountConfigurationQuery.Snapshot result = service.configure(context, clientAccountId,
                command, ifMatch, ifNoneMatch, idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ifNoneMatch == null ? 200 : 201)
                .cacheControl(CacheControl.noStore()).eTag(etag(result.version()));
        return response.body(ConfigurationResponse.from(result));
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    public record ConfigurationRequest(@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
                                       @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4)
                                       BigDecimal creditLimit,
                                       @NotNull Boolean active) { }

    public record Candidate(String id, String commercialName, String status) {
        private static Candidate from(CustomerAccountDirectoryQuery.Entry value) {
            return new Candidate(value.id().toString(), value.commercialName(), value.status());
        }
    }

    public record CandidatePage(List<Candidate> items, int page, int size, long total) {
        public CandidatePage { items = List.copyOf(items); }
        private static CandidatePage from(CustomerAccountDirectoryQuery.Page value) {
            return new CandidatePage(value.items().stream().map(Candidate::from).toList(),
                    value.page(), value.size(), value.total());
        }
    }

    public record ConfigurationResponse(String clientAccountId, String currency, String status,
                                        BigDecimal creditLimit, BigDecimal financedExposure,
                                        BigDecimal outstandingReceivables, BigDecimal reservedExposure,
                                        BigDecimal used, BigDecimal availableCredit, Long version) {
        private static ConfigurationResponse from(CreditAccountConfigurationQuery.Snapshot value) {
            return new ConfigurationResponse(value.customerAccountId().toString(), value.currency(),
                    value.status().name(), value.creditLimit(), value.financedExposure(),
                    value.outstandingReceivables(), value.reservedExposure(), value.used(),
                    value.availableCredit(), value.version());
        }
    }
}
