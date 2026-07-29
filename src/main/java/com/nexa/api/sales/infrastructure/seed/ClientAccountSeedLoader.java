package com.nexa.api.sales.infrastructure.seed;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Component
public final class ClientAccountSeedLoader {
	private final ObjectMapper objectMapper;
	public ClientAccountSeedLoader(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
	public List<ClientAccountSeedRecord> load() {
		try {
			var resource = new ClassPathResource("seed/sales/client-accounts.v1.json");
			byte[] raw; try (var input = resource.getInputStream()) { raw = input.readAllBytes(); }
			List<ClientAccountSeedRecord> records = objectMapper.readValue(raw, new TypeReference<>() {});
			ClientAccountSeedValidator.validate(records, raw);
			return List.copyOf(records);
		} catch (IOException exception) { throw new UncheckedIOException("Unable to read client account seed", exception); }
	}
}
