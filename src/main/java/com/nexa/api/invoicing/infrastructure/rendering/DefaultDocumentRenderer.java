package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.port.DocumentRendererPort;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

@Component
@Profile("!test")
public final class DefaultDocumentRenderer implements DocumentRendererPort {
    @Override public RenderedDocument render(BusinessDocumentType type, BusinessDocumentFormat format, Map<String, Object> data) {
        return switch (format) {
            case PDF -> pdf(type, data);
            case CSV -> csv(type, data);
            case XML -> xml(type, data);
        };
    }
    private RenderedDocument pdf(BusinessDocumentType type, Map<String, Object> data) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); stream.newLineAtOffset(50, 750);
                stream.showText("NEXA - " + type.name().replace('_', ' ')); stream.newLineAtOffset(0, -20); stream.showText("Service document; fiscal draft where indicated.");
                int lines = 0; for (var entry : data.entrySet()) { if (lines++ > 28) break; stream.newLineAtOffset(0, -16); stream.showText(safe(entry.getKey() + ": " + entry.getValue())); }
                stream.endText();
            }
            document.save(output); return new RenderedDocument(output.toByteArray(), "application/pdf", "pdf");
        } catch (Exception exception) { throw new IllegalStateException("PDF rendering failed", exception); }
    }
    private RenderedDocument csv(BusinessDocumentType type, Map<String, Object> data) {
        StringBuilder value = new StringBuilder("field,value\n"); value.append("document_type,").append(csv(type.name())).append('\n'); data.forEach((key, item) -> value.append(csv(key)).append(',').append(csv(String.valueOf(item))).append('\n'));
        return new RenderedDocument(value.toString().getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8", "csv");
    }
    private RenderedDocument xml(BusinessDocumentType type, Map<String, Object> data) {
        String root = type == BusinessDocumentType.DELIVERY_GUIDE_DRAFT ? "DespatchAdvice"
                : type == BusinessDocumentType.COMMERCIAL_INVOICE_DRAFT ? "Invoice" : "Document";
        StringBuilder value = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append('<').append(root)
                .append(" xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"")
                .append(" xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\"")
                .append(" schemaVersion=\"1.0\">")
                .append("<cbc:DocumentTypeCode>").append(xml(type.name())).append("</cbc:DocumentTypeCode>");
        data.forEach((key, item) -> value.append("<cbc:Note name=\"").append(xml(key)).append("\">")
                .append(xml(String.valueOf(item))).append("</cbc:Note>"));
        value.append("</").append(root).append('>');
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        validateXml(bytes);
        return new RenderedDocument(bytes, "application/xml", "xml");
    }
    private static String csv(String value) { String safe = value == null ? "" : value.replace("\"", "\"\""); return '"' + safe + '"'; }
    private static String xml(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static String safe(String value) { return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").replaceAll("[^\\x20-\\x7E]", "?"); }

    private static void validateXml(byte[] bytes) {
        try (InputStream schema = DefaultDocumentRenderer.class.getResourceAsStream("/schemas/business-document-v1.xsd")) {
            if (schema == null) throw new IllegalStateException("Business document XML schema is missing");
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.newSchema(new StreamSource(schema)).newValidator().validate(new StreamSource(new java.io.ByteArrayInputStream(bytes)));
        } catch (Exception exception) {
            throw new IllegalStateException("Business document XML schema validation failed", exception);
        }
    }
}
