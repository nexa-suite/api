package com.nexa.api.shared.presentation.error;

import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTests.TestController.class)
class GlobalExceptionHandlerTests {
	@Autowired
	private MockMvc mockMvc;

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
