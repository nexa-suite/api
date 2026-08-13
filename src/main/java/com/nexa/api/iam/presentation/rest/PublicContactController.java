package com.nexa.api.iam.presentation.rest;

import com.nexa.api.iam.application.port.in.SubmitPublicContactRequestCommand;
import com.nexa.api.shared.infrastructure.security.TrustedClientAddressResolver;
import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/public")
@Tag(name = "Public contact")
public final class PublicContactController {
    private final SubmitPublicContactRequestCommand submit;
    private final TrustedClientAddressResolver clientAddressResolver;

    public PublicContactController(SubmitPublicContactRequestCommand submit, TrustedClientAddressResolver clientAddressResolver) {
        this.submit = submit;
        this.clientAddressResolver = clientAddressResolver;
    }

    @PostMapping("/contact-requests")
    @Operation(operationId = "submitPublicContactRequest", summary = "Submit a public contact or demo request")
    public ResponseEntity<ReceiptResponse> submit(HttpServletRequest httpRequest, @Valid @RequestBody Request request) {
        var receipt = submit.submit(new SubmitPublicContactRequestCommand.Command(request.requestType(), request.name(),
                        request.email(), request.companyName(), request.message()), clientAddressResolver.resolve(httpRequest),
                correlation(httpRequest), trace(httpRequest));
        return ResponseEntity.accepted().body(new ReceiptResponse(receipt.requestId(), receipt.requestType(), receipt.status(), receipt.receivedAt()));
    }

    private static String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }

    private static String trace(HttpServletRequest request) {
        String value = request.getHeader("X-Trace-ID");
        return value == null || value.isBlank() ? correlation(request) : value;
    }

    public record Request(@NotBlank @Pattern(regexp = "DEMO|CONTACT") String requestType,
            @NotBlank @Size(min = 2, max = 160) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @Size(max = 160) String companyName,
            @NotBlank @Size(min = 20, max = 4000) String message) { }

    public record ReceiptResponse(UUID requestId, String requestType, String status, Instant receivedAt) { }
}
