# Invoicing Domain Foundation

## Scope

Invoicing owns the identity of fiscal documents and payments associated with
those documents. It is separate from Sales pricing and from any external tax
or payment provider.

## Value objects and vocabulary

- `InvoiceId` and `PaymentId` are internal required, normalized, safe uppercase
  text identifiers with a 64-character maximum.
- `InvoiceNumber` is a distinct human-facing document number. It is required,
  normalized to uppercase, uses `A-Z`, digits and `-`, and is limited to 64
  characters; it must not be conflated with `InvoiceId`.
- `InvoiceStatus` provides `DRAFT`, `ISSUED`, `PAID`, `OVERDUE` and `VOID` as
  candidate document vocabulary.
- `PaymentStatus` provides `PENDING`, `AUTHORIZED`, `SETTLED`, `FAILED` and
  `REFUNDED` as candidate payment vocabulary.

The current API classifies Invoicing as planned and has no fiscal-provider,
tax-authority or payment-provider implementation. The authoritative invoice
and payment states, legal numbering rules, settlement evidence, refund
semantics and allowed transitions remain unresolved. These enums are not an
external fiscal contract.

## Deliberate exclusions

No invoice aggregate, payment integration, tax calculation, repository,
service, controller, DTO, JPA mapping or framework dependency is introduced.
