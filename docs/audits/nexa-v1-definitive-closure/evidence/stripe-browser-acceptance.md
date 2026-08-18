# Stripe browser acceptance

Fecha: 2026-08-08
Repositorio: `nexa-suite/api`
Perfil: `local,minio,observability`
Provider efectivo: `NEXA_PAYMENTS_PROVIDER=stripe`
Endpoint Stripe-compatible: WireMock `modern-stripe-mock` (`/v1/payment_intents`)

## API y adapter

- `StripeJavaPaymentProviderIntegrationTests`: `1/1`, incluyendo `create`, `retrieve` y `confirm` mediante el SDK oficial `stripe-java`.
- `./mvnw -B -ntp clean verify -Dnexa.integration.enabled=true`: `333/333`, `Failures: 0`, `Errors: 0`, `Skipped: 0`.
- El mapping WireMock de confirmación devuelve `succeeded`; la aplicación no marca el pago por la respuesta HTTP. Construye un evento `payment_intent.succeeded`, lo firma con HMAC Stripe, lo ingresa al inbox y ejecuta el worker existente.

## Flujo Portal

Sesión Playwright `nexa-stripe`, workspace `icisa`, usuario buyer autenticado:

1. La tabla de receivables mostró una fila `OPEN` y creó el PaymentIntent mediante `POST /api/v1/receivables/{id}/payment-intents` (`201`).
2. El Payment Element contract-compatible se montó en Portal y mostró el control de tarjeta de prueba; los datos de tarjeta no entran al API.
3. El submit llamó `POST /api/v1/receivables/{id}/payment-intents/test-confirm` (`200`). La ruta existe solo con profile `local` y provider `stripe`, y está oculta de OpenAPI.
4. La siguiente lectura de receivables devolvió la fila `PAID`, con `amountPaid` completo y botón de pago deshabilitado.
5. `/portal/documents` mostró el `PAYMENT_RECEIPT` generado; `Descargar` produjo un PDF válido (`PDF 1.6`, `Content-Type: application/pdf`).

No se exponen client secrets, tokens, cookies ni credenciales en este registro. La cuenta Stripe externa y sus credenciales son explícitamente out-of-scope; WireMock se usa como proveedor Stripe-compatible permitido por la especificación, no como un provider determinista alternativo.

## Gates remotos del commit

- `d6fa950`: API CI `31255791257` — `success`.
- API Security and Load `31255791245` — `success`; DAST autenticado y baseline con `FAIL-NEW: 0`, k6 service `720/720` y negocio `3567/3567`, ambos con `http_req_failed=0`.
- API Supply Chain `31255791246` — `success`; Trivy filesystem limpio y SBOM generado.
