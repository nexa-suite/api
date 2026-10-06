package com.nexa.api.edge.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"nexa.swagger.enabled=true",
		"springdoc.api-docs.enabled=true",
		"springdoc.swagger-ui.enabled=true"
})
class SwaggerSecurityConfigurationTests {
	@Autowired
	private MockMvc mockMvc;

	@Test
	void swaggerAndOpenApiArePublicWhenSwaggerEnabled() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk());
	}

	@Test
	void swaggerUiIsAccessibleWhenSwaggerEnabled() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}
}
