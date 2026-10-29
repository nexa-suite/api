package com.nexa.api.businessdocuments.presentation;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentModels;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentUseCase;
import com.nexa.api.businessdocuments.application.service.BusinessDocumentServiceFacade;
import com.nexa.api.edge.problem.GlobalExceptionHandler;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BusinessDocumentDownloadTests {
    private static final String ACCESS = CurrentAccessContext.class.getName();

    @Test
    void missingObjectReturnsProblemBeforeCommittingDownloadHeaders() throws Exception {
        var port = mock(BusinessDocumentUseCase.class);
        var context = mock(CurrentAccessContext.class);
        UUID id = UUID.randomUUID();
        when(port.download(context, id)).thenReturn(new BusinessDocumentModels.Download("document.pdf", "application/pdf",
                () -> { throw new TechnicalFailureException(TechnicalFailureException.Kind.STORAGE_UNAVAILABLE, "private storage path"); },
                100, "checksum"));
        var mvc = MockMvcBuilders.standaloneSetup(new BusinessDocumentController(new BusinessDocumentServiceFacade(port)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/business-documents/" + id + "/downloads").requestAttr(ACCESS, context))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().doesNotExist("X-Content-SHA256"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private storage path"))));
    }

    @Test
    void successfulDownloadStreamsExactBytesAndClosesContent() throws Exception {
        var port = mock(BusinessDocumentUseCase.class);
        var context = mock(CurrentAccessContext.class);
        UUID id = UUID.randomUUID();
        byte[] bytes = "%PDF-1.7\nSynthetic fixture".getBytes(StandardCharsets.US_ASCII);
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(bytes) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        when(port.download(context, id)).thenReturn(new BusinessDocumentModels.Download("document.pdf", "application/pdf", input,
                bytes.length, "checksum"));
        var mvc = MockMvcBuilders.standaloneSetup(new BusinessDocumentController(new BusinessDocumentServiceFacade(port))).build();

        mvc.perform(get("/api/v1/business-documents/" + id + "/downloads").requestAttr(ACCESS, context))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        assertThat(closed).isTrue();
    }
}
