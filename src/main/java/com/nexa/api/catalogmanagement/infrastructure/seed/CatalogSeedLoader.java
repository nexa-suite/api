package com.nexa.api.catalogmanagement.infrastructure.seed;

import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItem;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Component
public final class CatalogSeedLoader {
	private static final String RESOURCE_PATH = "seed/catalog/catalog-items.v1.json";
	private final ObjectMapper objectMapper;
	private final CatalogSeedDomainMapper domainMapper;

	public CatalogSeedLoader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.domainMapper = new CatalogSeedDomainMapper();
	}

	public List<CatalogSeedItemRecord> load() {
		try {
			ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
			byte[] rawContent;
			try (var inputStream = resource.getInputStream()) {
				rawContent = inputStream.readAllBytes();
			}
			List<CatalogSeedItemRecord> items = objectMapper.readValue(rawContent, new TypeReference<>() {});
			CatalogSeedValidator.validate(items, rawContent);
			return List.copyOf(items);
		} catch (IOException exception) {
			throw new UncheckedIOException("Unable to read catalog seed resource", exception);
		}
	}

	public List<CatalogItem> loadDomainCatalog() {
		return load().stream().map(domainMapper::map).toList();
	}
}
