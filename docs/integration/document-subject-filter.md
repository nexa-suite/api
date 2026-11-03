# Business documents by subject

Implementation: additive optional filters on `GET /api/v1/business-documents`.
Runtime and Product acceptance remain pending.

Clients may supply `subjectType` and `subjectId` together, alongside the existing
`documentType`, `status`, `page` and `size` parameters. The subject type must be a
supported `DocumentSubjectType`; the id must be a UUID. Supplying only one is
invalid. Omitting both preserves the existing list contract.

The persistence query applies both filters before counting and pagination.
Tenant/Workspace context, document-read permission, current Buyer account
visibility and warehouse grants remain required. A subject id only narrows the
current visible set; it does not grant access. An inaccessible or empty subject
has no visible documents in this list. No subject data is synthesized.

Buyer Mobile uses `subjectType=SALES_ORDER` with the current order id, validates
returned document identities, and opens the existing document download flow.
The filter covers documents attached to that Sales Order. Documents attached to
other subject types (for example Dispatch or Payment) remain under their own
subject and the main document list.

The Application port retains its previous method and adds an overload. The
central and tenant-bound implementations support the filter. An implementation
that has not adopted it fails explicitly instead of returning an unfiltered
page. No database migration or document generation behavior changes.

Technical evidence: `./mvnw -o -Dmaven.test.skip=true compile` passed in an
isolated source copy. Integration checked that each edited live source still
matched its pre-edit snapshot. No tests, HTTP requests, OpenAPI regeneration,
deployment or acceptance ran. Regenerate the checked-in OpenAPI snapshot using
the repository workflow before publication.
