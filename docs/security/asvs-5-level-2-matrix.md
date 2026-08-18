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
| V9 Communication | Sí | CORS, CSP, HSTS fuera de local, cookies HttpOnly/SameSite | PARTIAL | Verificar TLS real |
| V10 Malicious input | Sí | validación de DTOs, SQL parametrizado | PARTIAL | DAST |
| V11 Business logic | Sí | activación bloqueada a operador y lock transaccional | PARTIAL | Prueba de concurrencia en entorno integrado |
| V12 Files/resources | No | object storage fuera de este gate | NOT_APPLICABLE | TASK-011 |
| V13 API security | Sí | OpenAPI, Problem Details, If-Match | PARTIAL | Revisar runtime/static parity |
| V14 Configuration | Sí | variables de entorno, no credencial seed de operador | PARTIAL | Revisar secretos del entorno |
| V15 Files | No | no se implementan archivos | NOT_IMPLEMENTED | TASK-011 |

Los controles de pagos, documentos, MFA, mobile y almacenamiento de objetos permanecen fuera de alcance y no se marcan como aprobados.

## Browser gate alternative

The modern Platform and Portal manifests do not declare `playwright/test`, and installing a new package is outside this gate. The repository Playwright specs remain stored under each frontend `e2e/` directory. The supported alternative gate was executed with installed Chromium through the local Playwright CLI wrapper:

- Platform organization registration submitted and navigated to `registration-pending/:registrationId`.
- Platform public forgot-password route rendered with the generic recovery form.
- Portal public forgot-password route rendered without internal roles.

The Playwright test-runner command remains a documented manual follow-up until the dependency is approved and declared.
