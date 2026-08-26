package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryProjectionPort;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryRenderer;
import com.nexa.api.salescommitment.application.salesorder.export.service.SalesOrderSummaryExportService;
import com.nexa.api.salescommitment.application.salesorder.export.service.SalesOrderSummaryRendererStrategy;
import com.nexa.api.salescommitment.infrastructure.export.CsvSalesOrderSummaryRenderer;
import com.nexa.api.salescommitment.infrastructure.export.PdfSalesOrderSummaryRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SalesOrderSummaryExportRuntimeConfiguration {
	@Bean
	CsvSalesOrderSummaryRenderer csvSalesOrderSummaryRenderer() { return new CsvSalesOrderSummaryRenderer(); }

	@Bean
	PdfSalesOrderSummaryRenderer pdfSalesOrderSummaryRenderer() { return new PdfSalesOrderSummaryRenderer(); }

	@Bean
	SalesOrderSummaryRendererStrategy salesOrderSummaryRendererStrategy(List<SalesOrderSummaryRenderer> renderers) {
		return new SalesOrderSummaryRendererStrategy(renderers);
	}

	@Bean
	SalesOrderSummaryExportUseCase salesOrderSummaryExportUseCase(SalesOrderSummaryProjectionPort projection,
			CustomerAccountQuery accounts, SalesOrderSummaryRendererStrategy renderers) {
		return new SalesOrderSummaryExportService(projection, accounts, renderers);
	}
}
