package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentLine;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

final class CsvDocumentSupport {
    private CsvDocumentSupport() { }

    static byte[] render(DocumentProjection projection) {
        StringBuilder csv = new StringBuilder();
        row(csv, List.of("order_number", "sku_code", "product_family", "presentation", "quantity", "uom",
                "base_unit_price", "discount", "effective_unit_price", "line_total", "currency", "subtotal", "tax", "total"));
        if (projection.lines().isEmpty()) {
            row(csv, List.of(projection.reference(), "", "", "", "", "", "", "", "", "", projection.totals().currency(),
                    projection.totals().subtotal(), projection.totals().tax(), projection.totals().total()));
        } else {
            for (DocumentLine line : projection.lines()) {
                row(csv, List.of(projection.reference(), line.skuCode(), line.productFamily(), line.presentation(), line.quantity(), line.uom(),
                        line.baseUnitPrice(), line.discount(), line.effectiveUnitPrice(), line.lineTotal(), line.currency(), projection.totals().subtotal(),
                        projection.totals().tax(), projection.totals().total()));
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder csv, List<?> values) {
        csv.append(values.stream().map(CsvDocumentSupport::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    private static String cell(Object value) {
        if (value == null) return "\"\"";
        String text = String.valueOf(value);
        if (text.stripLeading().matches("^[=+\\-@].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
