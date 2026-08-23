package com.nexa.api.shared.presentation.error;

import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandlerTests.TestController.class)
class GlobalExceptionHandlerTests {
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private WebApplicationContext webApplicationContext;

	@BeforeEach
	void setUpMockMvcWithCorrelationFilter() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.addFilters(new CorrelationIdFilter()).build();
	}

	@Test
	void returnsValidationProblemDetailsWithCorrelationId() throws Exception {
		mockMvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(header().exists(CorrelationIdFilter.HEADER_NAME))
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty())
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("MethodArgumentNotValidException"))));
	}

	@Test
	void handlesMalformedJsonAndUnsupportedMethod() throws Exception {
		mockMvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mockMvc.perform(post("/read-only")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void handlesUnexpectedErrorsWithGenericProblem() throws Exception {
		mockMvc.perform(get("/explode")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.detail").value("Internal server error"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("IllegalStateException"))));
	}

	@Test
	void exposesSafeTechnicalFailureContract() throws Exception {
		mockMvc.perform(get("/technical-failure"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("EXTERNAL_TIMEOUT"))
				.andExpect(jsonPath("$.category").value("EXTERNAL"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.detail").value("An external service did not respond in time"));
	}

	@Test
	void handlesControllerMethodValidationAsBadRequest() throws Exception {
		mockMvc.perform(get("/validated-query").param("value", "invalid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void preservesClientAccountNotFoundCodeForCustomerRelationshipResources() throws Exception {
		mockMvc.perform(get("/missing-client-account"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLIENT_ACCOUNT_NOT_FOUND"));
		mockMvc.perform(get("/missing-client-address"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLIENT_ACCOUNT_NOT_FOUND"));
	}

	@RestController
	public static class TestController {
		@PostMapping("/test")
		void validate(@Valid @RequestBody Payload payload) {
		}

		@GetMapping("/read-only")
		void readOnly() {
		}

		@GetMapping("/explode")
		void explode() {
			throw new IllegalStateException("secret internal detail");
		}

		@GetMapping("/technical-failure")
		void technicalFailure() {
			throw new TechnicalFailureException(TechnicalFailureException.Kind.EXTERNAL_TIMEOUT,
					"provider detail must not reach the client", null, "provider-request-id");
		}

		@GetMapping("/validated-query")
		void validatedQuery(@RequestParam @Pattern(regexp = "VALID") String value) {
		}

		@GetMapping("/missing-client-account")
		void missingClientAccount() {
			throw new ApiResourceNotFoundException("client-account");
		}

		@GetMapping("/missing-client-address")
		void missingClientAddress() {
			throw new ApiResourceNotFoundException("client-account-address");
		}
	}

	static class Payload {
		@NotBlank
		private String name;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}
}
