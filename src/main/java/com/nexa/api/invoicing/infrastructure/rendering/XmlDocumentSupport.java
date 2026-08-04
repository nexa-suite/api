package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.BusinessParty;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentLine;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentTotals;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DeliveryGuideDraftProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.CommercialInvoiceDraftProjection;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

final class XmlDocumentSupport {
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    private static final String DESPATCH = "urn:oasis:names:specification:ubl:schema:xsd:DespatchAdvice-2";

    private XmlDocumentSupport() { }

    static byte[] invoice(CommercialInvoiceDraftProjection projection) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<Invoice xmlns=\"").append(INVOICE).append("\" xmlns:cbc=\"").append(CBC).append("\" xmlns:cac=\"").append(CAC).append("\" schemaVersion=\"1.0\">");
        xml.append(element("cbc:ID", projection.invoiceNumber()));
        xml.append(element("cbc:IssueDate", projection.issueDate().atOffset(ZoneOffset.UTC).toLocalDate().toString()));
        xml.append(amount("cbc:DocumentCurrencyCode", projection.totals().currency()));
        party(xml, "cac:AccountingSupplierParty", projection.issuer());
        party(xml, "cac:AccountingCustomerParty", projection.buyer());
        xml.append(element("cbc:Note", "FISCAL-DRAFT: not SUNAT-certified"));
        for (int index = 0; index < projection.lines().size(); index++) invoiceLine(xml, projection.lines().get(index), index + 1, projection.totals().currency());
        totals(xml, projection.totals());
        xml.append("</Invoice>");
        return validated(xml.toString(), "/schemas/business-invoice-v1.xsd");
    }

    static byte[] deliveryGuide(DeliveryGuideDraftProjection projection) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<DespatchAdvice xmlns=\"").append(DESPATCH).append("\" xmlns:cbc=\"").append(CBC).append("\" xmlns:cac=\"").append(CAC).append("\" schemaVersion=\"1.0\">");
        xml.append(element("cbc:ID", projection.dispatchNumber()));
        xml.append(element("cbc:IssueDate", projection.issueDate().atOffset(ZoneOffset.UTC).toLocalDate().toString()));
        xml.append(element("cbc:Note", "NON-FISCAL-DRAFT: delivery guide information only"));
        party(xml, "cac:DespatchSupplierParty", projection.issuer());
        party(xml, "cac:DeliveryCustomerParty", projection.buyer());
        xml.append("<cac:Shipment>").append(element("cbc:ID", projection.salesOrderReference())).append(element("cbc:Information", value(projection.delivery().address()))).append("</cac:Shipment>");
        for (int index = 0; index < projection.lines().size(); index++) despatchLine(xml, projection.lines().get(index), index + 1);
        xml.append("</DespatchAdvice>");
        return validated(xml.toString(), "/schemas/business-document-despatch-advice-v1.xsd");
    }

    private static void party(StringBuilder xml, String name, BusinessParty party) {
        xml.append("<").append(name).append("><cac:Party><cac:PartyName>")
                .append(element("cbc:Name", party.legalName())).append("</cac:PartyName></cac:Party></").append(name).append(">");
    }

    private static void invoiceLine(StringBuilder xml, DocumentLine line, int index, String currency) {
        xml.append("<cac:InvoiceLine>").append(element("cbc:ID", String.valueOf(index)))
                .append(attributeElement("cbc:InvoicedQuantity", line.quantity().toPlainString(), "unitCode", line.uom()))
                .append(attributeElement("cbc:LineExtensionAmount", line.lineTotal().toPlainString(), "currencyID", currency))
                .append("<cac:Item>").append(element("cbc:Name", line.skuCode() + " - " + line.productFamily() + " - " + line.presentation())).append("</cac:Item>")
                .append("<cac:Price>").append(attributeElement("cbc:PriceAmount", line.effectiveUnitPrice().toPlainString(), "currencyID", currency)).append("</cac:Price>")
                .append("</cac:InvoiceLine>");
    }

    private static void despatchLine(StringBuilder xml, DocumentLine line, int index) {
        xml.append("<cac:DespatchLine>").append(element("cbc:ID", String.valueOf(index)))
                .append(attributeElement("cbc:DeliveredQuantity", line.quantity().toPlainString(), "unitCode", line.uom()))
                .append("<cac:Item>").append(element("cbc:Name", line.skuCode() + " - " + line.productFamily() + " - " + line.presentation())).append("</cac:Item>")
                .append("</cac:DespatchLine>");
    }

    private static void totals(StringBuilder xml, DocumentTotals totals) {
        xml.append("<cac:TaxTotal>").append(attributeElement("cbc:TaxAmount", totals.tax().toPlainString(), "currencyID", totals.currency())).append("</cac:TaxTotal>")
                .append("<cac:LegalMonetaryTotal>").append(attributeElement("cbc:PayableAmount", totals.total().toPlainString(), "currencyID", totals.currency())).append("</cac:LegalMonetaryTotal>");
    }

    private static String amount(String name, String value) { return element(name, value); }
    private static String element(String name, String value) { return "<" + name + ">" + escape(value) + "</" + name + ">"; }
    private static String attributeElement(String name, String value, String attribute, String attributeValue) {
        return "<" + name + " " + attribute + "=\"" + escape(attributeValue) + "\">" + escape(value) + "</" + name + ">";
    }
    private static String value(String value) { return value == null || value.isBlank() ? "-" : value; }
    private static String escape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }

    private static byte[] validated(String xml, String schemaPath) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        try {
            URL schemaUrl = XmlDocumentSupport.class.getResource(schemaPath);
            if (schemaUrl == null) throw new IllegalStateException("Business document XML schema is missing: " + schemaPath);
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setResourceResolver(new ClasspathSchemaResolver());
            StreamSource schema = new StreamSource(schemaUrl.openStream());
            schema.setSystemId(schemaUrl.toExternalForm());
            factory.newSchema(schema).newValidator().validate(new StreamSource(new ByteArrayInputStream(bytes)));
            return bytes;
        } catch (Exception exception) {
            throw new IllegalStateException("Business document XML schema validation failed", exception);
        }
    }

    private static final class ClasspathSchemaResolver implements LSResourceResolver {
        @Override public LSInput resolveResource(String type, String namespaceUri, String publicId, String systemId, String baseUri) {
            if (systemId == null || systemId.contains("/") && !systemId.endsWith(".xsd")) return null;
            String name = systemId.substring(systemId.lastIndexOf('/') + 1);
            URL resource = XmlDocumentSupport.class.getResource("/schemas/" + name);
            if (resource == null) return null;
            try {
                return new ClasspathSchemaInput(publicId, systemId, resource.openStream());
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Business document XML schema import failed", exception);
            }
        }
    }

    private static final class ClasspathSchemaInput implements LSInput {
        private final String publicId;
        private final String systemId;
        private java.io.InputStream byteStream;

        private ClasspathSchemaInput(String publicId, String systemId, java.io.InputStream byteStream) {
            this.publicId = publicId; this.systemId = systemId; this.byteStream = byteStream;
        }
        @Override public Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(Reader characterStream) { }
        @Override public java.io.InputStream getByteStream() { return byteStream; }
        @Override public void setByteStream(java.io.InputStream byteStream) { this.byteStream = byteStream; }
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String stringData) { }
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String systemId) { }
        @Override public String getPublicId() { return publicId; }
        @Override public void setPublicId(String publicId) { }
        @Override public String getBaseURI() { return null; }
        @Override public void setBaseURI(String baseURI) { }
        @Override public String getEncoding() { return "UTF-8"; }
        @Override public void setEncoding(String encoding) { }
        @Override public boolean getCertifiedText() { return true; }
        @Override public void setCertifiedText(boolean certifiedText) { }
    }
}
