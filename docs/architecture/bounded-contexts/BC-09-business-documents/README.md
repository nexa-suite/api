# BC-09 — Business Documents

- **Owner:** `com.nexa.api.businessdocuments`
- **Storage:** `business_documents`.
- **Owns:** document metadata, generation requests, storage objects and evidence
  availability metadata.
- **Public contracts:** `BusinessEvidenceQuery` (availability and immutable
  subject binding), `BusinessDocumentCommands` (durable payment-receipt
  generation request), and document generation/query ports.
- **v0.15 relation:** validates referenced POD/temperature evidence objects;
  it does not own delivery facts or create a second evidence store.
- **Excludes:** fiscal certification, payment settlement and the BC-11 business
  fact timeline.

Binary content and scanning remain behind the existing storage/scanner ports.
