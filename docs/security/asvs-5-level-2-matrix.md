# Nexa ASVS 5 Level 2 control matrix

Estado verificable de los controles aplicables a este gate. `PASS` requiere una ubicación de implementación y una prueba reproducible.

| Requisito | Aplicabilidad | Implementación/evidencia | Estado | Revisión manual |
|---|---|---|---|---|
| V1 Architecture | Sí | `ArchitectureConstitutionTests`, Spring Modulith | PASS | Revisar nuevas dependencias de módulo |
| V2 Authentication | Sí | `SignInService`, throttle, BCrypt, IAM tests | PARTIAL | Revisar secretos de despliegue |
| V3 Session management | Sí | refresh rotation, `IamSecurityController`, `CurrentAccessContextFilter` | PARTIAL | Playwright con dos sesiones |
| V4 Access control | Sí | role policy, tenant/workspace resolution, BOLA matrix | PARTIAL | Revisar nuevos casos de negocio |
| V5 Validation | Sí | Bean Validation y Problem Details | PARTIAL | Fuzzing de inputs |
| V6 Cryptography | Sí | BCrypt, SHA-256 de tokens, `SecureRandom` 256-bit | PASS | Revisar strength operativo |
| V7 Error handling | Sí | `GlobalExceptionHandler`, respuestas genéricas de reset | PARTIAL | Comparar tiempos de respuestas |
| V8 Data protection | Sí | tokens solo hash, auditoría sin secretos | PASS | Revisar logs de infraestructura |
| V9 Communication | Sí | CORS, CSP, HSTS fuera de local, cookies HttpOnly/SameSite; SEC-01–03 transport validators | PARTIAL | Verificar TLS real y configuración de salida desplegada |
| V10 Malicious input | Sí | validación de DTOs, SQL parametrizado | PARTIAL | DAST |
| V11 Business logic | Sí | activación bloqueada a operador y lock transaccional | PARTIAL | Prueba de concurrencia en entorno integrado |
| V12 Files/resources | Sí | SEC-02 `ObjectStorageRuntimeConfigurationValidator` y pruebas de endpoint HTTPS | PARTIAL | Verificar endpoint, certificados y configuración real del object storage |
| V13 API security | Sí | OpenAPI, Problem Details, If-Match | PARTIAL | Revisar runtime/static parity |
| V14 Configuration | Sí | variables de entorno; SEC-01 SMTP/reset links, SEC-03 Google Routes, SEC-04 Stripe custom base validators | PARTIAL | Revisar secretos y configuración real del entorno |
| V15 Files | No | no se implementan archivos | NOT_IMPLEMENTED | TASK-011 |

SEC-01–04 cubren validación de configuración de transporte al iniciar. No acreditan entrega SMTP real, configuración de proveedores en producción, TLS desplegado, revisión completa de pagos/documentos, MFA ni seguridad Mobile.

## SEC-01–SEC-04 — External transport configuration evidence

Los estados permanecen `PARTIAL`: las pruebas locales validan rechazo/aceptación de configuración, no conectividad externa ni configuración desplegada. Las pruebas focalizadas de los cuatro validadores reportaron 32 casos aprobados en total (SMTP 11, almacenamiento 7, Google Routes 7, Stripe 7); la verificación completa del repositorio sigue pendiente.

| Control | Implementación | Pruebas/evidencia automatizada | Estado | No verificado |
|---|---|---|---|---|
| SEC-01 SMTP reset/invitation transport | `src/main/java/com/nexa/api/tenantaccessgovernance/iam/infrastructure/notification/SmtpSecurityRuntimeConfigurationValidator.java` exige STARTTLS habilitado y requerido o SSL implícito fuera de `local`/`test`, y valida URLs reset de plataforma/portal cuando SMTP está activo. `src/main/java/com/nexa/api/tenantaccessgovernance/iam/infrastructure/notification/SmtpPasswordResetDeliveryAdapter.java` deriva enlace de invitación desde URL de plataforma. | `src/test/java/com/nexa/api/tenantaccessgovernance/iam/infrastructure/notification/SmtpSecurityRuntimeConfigurationValidatorTests.java` — 11 pruebas focalizadas aprobadas; cubre TLS, URLs HTTPS inválidas, loopback, placeholders, userinfo y excepciones locales/de prueba. | PARTIAL | Entrega con SMTP real, valores desplegados de reset/invitación y TLS del despliegue. No hay evidencia de configuración SMTP de producción. |
| SEC-02 S3-compatible object storage TLS | `src/main/java/com/nexa/api/businessdocuments/infrastructure/storage/ObjectStorageRuntimeConfigurationValidator.java` requiere endpoint HTTPS sin userinfo para perfiles `s3`/`minio` fuera de `local`/`test`; HTTP local/test sigue permitido para fixtures. | `src/test/java/com/nexa/api/businessdocuments/infrastructure/storage/ObjectStorageRuntimeConfigurationValidatorTests.java` — 7 pruebas focalizadas aprobadas; cubre HTTPS durable, HTTP remoto, userinfo y MinIO local/test. | PARTIAL | Conexión real, certificados y endpoint/configuración de S3/MinIO en producción. |
| SEC-03 Google Routes API key boundary | `src/main/java/com/nexa/api/salescommitment/infrastructure/maps/GoogleMapsHttpBoundaryAdapter.java` exige HTTPS y host exacto `routes.googleapis.com` fuera de `local`/`test`; rechaza userinfo y destinos arbitrarios antes de enviar `X-Goog-Api-Key`. | `src/test/java/com/nexa/api/salescommitment/infrastructure/GoogleMapsHttpBoundaryAdapterTest.java` — 7 pruebas focalizadas aprobadas; cubre host confiable, HTTP, host arbitrario, userinfo, URI inválida y mock local. | PARTIAL | Configuración real de Google en producción y transporte desplegado. |
| SEC-04 Stripe custom API base | `src/main/java/com/nexa/api/payments/infrastructure/stripe/PaymentRuntimeConfigurationValidator.java` rechaza `nexa.payments.api-base-url` fuera de `local`/`test`; mocks locales/de prueba conservan override. | `src/test/java/com/nexa/api/payments/infrastructure/stripe/PaymentRuntimeConfigurationValidatorTests.java` — 7 pruebas focalizadas aprobadas; cubre rechazo fuera de perfiles locales y override de mock local/test. | PARTIAL | Configuración/provider Stripe real en producción. No se ejecutó una prueba contra Stripe ni se verificó TLS de despliegue. La verificación de firma de webhooks no se modificó; estas pruebas focalizadas no la cubren. |

## Browser gate alternative

The modern Platform and Portal manifests do not declare `playwright/test`, and installing a new package is outside this gate. The repository Playwright specs remain stored under each frontend `e2e/` directory. The supported alternative gate was executed with installed Chromium through the local Playwright CLI wrapper:

- Platform organization registration submitted and navigated to `registration-pending/:registrationId`.
- Platform public forgot-password route rendered with the generic recovery form.
- Portal public forgot-password route rendered without internal roles.

The Playwright test-runner command remains a documented manual follow-up until the dependency is approved and declared.
