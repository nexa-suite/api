# Backend preparado para frontend

**Estado: parcial; P0 de autenticación preparado y verificado localmente.**
Actualizado el 22 de octubre de 2026 en `api`, rama
`feature/tenant-isolation-hardening`. La autoridad de Producto y arquitectura consultada es Blueprint en
`27e7ea8f7d8a671500039d33e1a4bbab62464691`. El worktree contiene cambios
locales sin commit; este documento describe el código y el contrato local tras
esos cambios. El [OpenAPI](./openapi/openapi.json) define los schemas completos
y las rutas publicadas.

## Conclusión ejecutiva

- **Tenant lógico:** la API ya exige contexto de Tenant/Workspace en las rutas
  de negocio y aplica autorización de objeto, membresía, relación Buyer o
  asignación Driver según el flujo. Cada alta aceptada crea su Tenant y
  Workspace lógicos distintos; V1 conserva la relación Tenant:Workspace 1:1.
- **Aislamiento físico de datos:** la configuración de aplicación revisada
  declara una base PostgreSQL central compartida con schemas de contextos de
  negocio y RLS; esto no demuestra la topología desplegada. El perfil `local`
  conecta selectivamente algunos casos de uso al router y a bases físicas
  por Tenant cuando también están habilitadas las
  flags de capacidad correspondientes; no cubre todos los comandos/consultas
  ni acredita el routing del despliegue. El manifiesto Render revisado declara
  perfil `s3` y un servicio `web`, sin activar el perfil local; la topología,
  flags, credenciales y migraciones vivas son **NOT MEASURED**. La activación
  crea Tenant y Workspace lógicos, pero el aprovisionamiento físico no está
  acreditado como parte del alta ni del runtime productivos. No hay cutover
  autorizado.
- **Ubicación de datos por Tenant:** no es un plan/tier comercial. Blueprint
  acepta como TARGET una base central de identidad/gobierno y una base física
  de negocio independiente por cada Tenant; hoy la API sigue AS-IS en
  PostgreSQL compartido con RLS. La estructura de schemas tampoco declara por
  sí sola ownership de Bounded Context.
- **`public`:** las pruebas de migración sobre PostgreSQL desechable encuentran
  `public.flyway_schema_history` como tabla técnica; no encuentran tablas de
  negocio en ese schema. No es una consulta a una base desplegada.
- **Planes/tier comercial:** Plan, Subscription, Entitlement y feature tiers
  están fuera del contrato V1 aceptado. `/api/v1/plan-usage` y
  `/api/v1/plan-comparison` no aplican cuotas; quedan marcados como
  `deprecated` en OpenAPI para conservar compatibilidad. `referencePlan` es
  metadato de registro, no asigna derechos ni aislamiento de datos.
- **Preparación de clientes:** hay contratos utilizables para login, cambio y
  recuperación web, sesiones, onboarding, catálogo, drafts de compra,
  Warehouse, inventario, entrega, finanzas, documentos y notificaciones. Las
  brechas de abajo impiden declarar listos todos los cuatro consumidores o la
  operación de producción.

En este documento:

- **AS-IS:** evidencia del código o de pruebas del API actual.
- **TARGET:** decisión aceptada por Blueprint; no equivale a implementación.
- **OPEN:** falta decisión o aceptación competente; no se inventa semántica.
- **NOT MEASURED:** no hay evidencia del entorno, uso o consumidor real.

## Matriz de brechas

| Área / prioridad | Contrato e implementación AS-IS | Brecha real | Cambio o dependencia | Consumidores / verificación |
|---|---|---|---|---|
| Recuperación de acceso — P0 | `POST /api/v1/auth/password-reset-requests` devuelve respuesta genérica; token aleatorio, hash en base, expiración por defecto de 30 min, sustitución de solicitudes pendientes y entrega por outbox cifrado. Web conserva sus URLs por superficie. `X-Nexa-Client: NATIVE` enruta Buyer/Operations a URLs HTTPS configuradas en servidor; el cuerpo no admite redirect. `POST /api/v1/auth/password-resets` consume el token una vez, cambia contraseña, revoca sesiones y registra evento sin incluir token/password. Intentos inválidos se limitan por IP (10 cada 10 min por defecto). | Equivalencia temporal entre cuenta existente/inexistente: **NOT MEASURED**. URLs nativas de despliegue se deben configurar; la política de contraseña (12–128, NFKC y lista de valores bloqueados) no tiene aceptación de Producto localizada: **OPEN**. | P0 backend listo para conectar; falta configurar las URLs reales y verificar entrega de correo en el entorno destino. No declarar producción preparada. | Buyer Flutter, Operations Android, Portal Web, Platform Web. Reportes Maven locales del 22-10-2026: `PasswordResetIT` 2/0, `PasswordResetThrottleConcurrencyIT` 1/0, `AuthenticationFlowIT` 17/0, `NativePasswordResetRoutingTests` 7/0 y `OpenApiContractIT` 1/0 (pruebas/fallos). Esto no verifica entrega de correo ni clientes en el entorno destino. |
| Sesiones y refresh — P1 | Login crea access token de 15 min y refresh opaco rotatorio de 30 días configurable; en DB se almacena hash. Web usa cookie HttpOnly/Secure/SameSite estricta y comprobación de Origin/CSRF; Native usa `X-Nexa-Refresh-Token`. Refresh serializa/rota y reutilización revoca familia. Las membresías y el contexto se revalidan. Rutas de consulta/revocación propias existen. | Si se pierde la respuesta de refresh, un retry del token previo se trata como reutilización y revoca la familia; no hay recuperación idempotente para respuesta perdida ni prueba de refresh concurrente en el alcance revisado. Lista de sesiones no pagina; device/IP no se rellenan consistentemente y `last_seen` no es evidencia confiable. | Política de retry/replay y metadatos de sesión: **OPEN**. No guardar contraseñas para “recordarme”. | Los cuatro clientes. `RefreshSessionPostgresIntegrationTests`, `SessionManagementIT`; cobertura de respuesta perdida/concurrencia pendiente. |
| Biometría y passkeys — P0 local / OPEN passkeys | No hay endpoint biométrico ni servidor que reciba huella/foto. Login Native devuelve refresh opaco en `X-Nexa-Refresh-Token`, compatible con protección local en Keychain/Keystore y refresh habitual. | El backend no verifica biometría: esa comprobación es del SO en el dispositivo. WebAuthn/passkeys y su autoridad de Producto siguen abiertos. | El cliente conserva el refresh token en almacenamiento seguro y solicita desbloqueo biométrico local; no envía resultado biométrico. No aceptar `biometricVerified=true`. | Buyer Flutter y Operations Android. Smoke local de login Buyer/Owner pasó; flujo biométrico del cliente todavía requiere implementación/verificación en su repo. |
| Alta de empresa — P1 | Draft anónimo de seis pasos: crear, leer, editar con ETag/Idempotency-Key, enviar; token de reanudación fuera de URL. Activación/rechazo son límites internos. Tras revisión, la activación transaccional crea Tenant/Workspace lógicos, fundador y tarea de provisioning. En el Docker local la cola, el binding y el anchor llegaron a `READY` y hay una base Tenant física saludable. | `termsVersion` acepta cadena no vacía, sin validar versión publicada. `referencePlan` sigue obligatorio aunque sea metadato. El estado `ACTIVE` puede adelantarse a la DB física `READY`; el entorno local no prueba disponibilidad general ni cutover productivo. El flujo one-shot sigue activo como deprecated. | Resolver la fuente vigente de términos. Mantener el fail-closed durante provisioning; política para presentar su estado en onboarding permanece pendiente de contrato. `referencePlan` solo cambia con autorización de Producto. | Portal Web y Platform Web; revisión interna en Platform. Prueba local y migraciones pasan; topology y provisioning de producción **NOT MEASURED**. |
| Ciclo de vida Tenant — P1 | Tenant/Workspace y gobierno de miembros/roles están implementados. La activación interna crea Tenant y Workspace relacionados. | No se encontraron contratos completos de suspender/reactivar, cierre/exportación y su historial. Blueprint considera el ciclo de vida necesario; criterios de aceptación y política legal/retención siguen pendientes. | Definir contratos y transiciones después de aceptación; no inventar retención ni cierre legal. | Platform. Búsqueda de rutas y migraciones; no existe prueba de esos contratos ausentes. |
| Usuarios, roles, permisos — P2 | Rutas de workspace, membresía, rol y grants; cada solicitud reconstruye autoridad en servidor. Existe control explícito de acceso a Warehouse y permisos. | `GET /api/v1/me/sessions` no pagina; algunas listas de gobierno requieren revisión de paginación uniforme en todos los consumidores. | Paginar donde lo requieran los volúmenes y contrato aceptado. | Platform/Operations/Portal; OpenAPI y pruebas de aislamiento existentes. |
| Relación Buyer — P1 | Client Account, asociación de membresía Buyer, suspensión/activación, direcciones, ofertas por cliente y exposición de crédito existen. `client-accounts/me` resuelve al Buyer autenticado. | No se encontró entidad/flujo integral de BuyerRelationship con invitación, revocación, transferencia e historial. Consentimiento de contactos alternos no existe y queda fuera de V1. | Acordar autoridad y transiciones. No inferir una relación desde un `clientAccountId` enviado por cliente. | Buyer Flutter y Platform. Autorización de objeto/membresía comprobable; ciclo de relación completo no está implementado. |
| Catálogo y precio — P2 | Catálogo de producto, familia, variedad/SKU, unidades, precios, promociones, visibilidad por Buyer, filtros, opciones y paginación tienen rutas en OpenAPI. Favoritos personales Buyer solicitados por Owner: `GET /api/v1/buyer/favorites?page=0&size=25` (máximo 100), `PUT /api/v1/buyer/favorites/{sellableSkuId}` y `DELETE` sobre la misma ruta. La asociación se guarda por membresía Buyer y SKU de oferta; GET devuelve páginas con `totalItems` y resuelve disponibilidad/precio actuales desde BC-03. | No se verificó en este alcance que todos los filtros y paginación sean idénticos entre compradores, operaciones y reportes. Un SKU oculto/inactivo conserva su asociación y `favoritedAt`, con `currentOffer: null`, para que pueda eliminarse. Blueprint no define hoy una función de favoritos; este slice es extensión solicitada por Owner. Alias `GET /catalog/products/{productId}/prices` es deprecated y permanece. | Mantener rutas mientras no haya telemetría de uso; documentar divergencias de filtro si los consumidores las reportan. La ruta de favoritos está habilitada en perfil local con `nexa.tenant-business.catalog-favorites.enabled`; no hay evidencia de despliegue productivo. | Buyer Flutter, Operations Android, Portal y Platform. OpenAPI actualizado; uso por cliente **NOT MEASURED**. |
| Compra, venta y cambios — P1 | Buyer drafts: crear, editar destino/líneas/preferencias, revisar, previsualizar ruta y enviar. Purchase Requests de operaciones tienen submissions, cambios materiales, aceptación/rechazo, aprobación, conversión a Sales Order, cancelación e historial. Drafts de venta manual también existen para operaciones. | No hay ruta de repetir compra como draft nuevo. No se encontró endpoint de Direct Order Buyer; la venta manual está en superficie Operations. `/purchase-requests/field-submissions` y flujos manuales tienen consumidor exacto **NOT MEASURED**. | Reorder está diferido a V2. Direct Order Buyer requiere autoridad explícita. Probar conjuntamente inventario, crédito y modalidad de pago en el workflow de extremo a extremo. | Buyer Flutter y Operations Android. `TenantBoundDirectOrderIT` comprueba que el POST de Operations persiste el pedido y sus efectos solo en cada DB física Tenant, permite la misma clave de idempotencia entre Tenants y devuelve 503 sin efectos si falta binding. Cobertura cruzada con clientes **NOT MEASURED**. |
| Política de fecha mínima de entrega | `GET /api/v1/buyer/delivery-lead-policy` devuelve cinco días hábiles globales, contados desde el día siguiente a la fecha actual de `America/Lima`; no hay calendario de feriados ni ajuste por Workspace. Operations conserva el contrato `GET /api/v1/direct-orders/delivery-policy`, con `minimumDeliveryLeadDays=5` y la fecha mínima calculada por la misma regla. | La compatibilidad de clientes debe verificarse contra los SHAs publicados y CI. No hay decisión canónica para configuración comercial por Workspace. | Buyer y Sales validan las fechas al crear, cambiar o enviar pedidos. La política canónica y los contratos están en `docs/integration/buyer-delivery-lead-policy.md`. | Buyer Flutter y Operations Android. La implementación está en la candidata; falta verificación CI sobre el SHA final. |
| Warehouse/inventario y temperatura de lote — P0 lectura BOM / P1 almacén | Rutas de almacén, zonas, ubicación/perfil, acceso, recepción, lotes, ajuste, conteo, HOLD/cuarentena/disposición, transferencias, reservas y scan existen. `GET /api/v1/inventory/lots/{lotId}/temperature-readings?page=0&size=25` devuelve observación de recepción y evaluaciones `RECEIVING`, con evidencia y disposición; solo lectura, página de hasta 100. BOM en superficie `PLATFORM` necesita `inventory.temperature.audit.read`; Warehouse requiere `warehouse.read` y grant activo para el almacén. | Cobertura de aislamiento para otro Workspace del mismo Tenant y los límites de paginación OpenAPI están pendientes de verificación. No se incluyen lecturas de Fulfillment ni umbrales/alertas nuevas. | Mantener Tenant + Workspace + lote en cada consulta; devolver 404 para lote fuera del scope y 403 sin permiso/grant. Conectar el consumidor BOM con el contrato existente. | BOM/Platform y Warehouse de Operations. Implementación presente; verificación enfocada pendiente. |
| Entrega y Driver — P1 | Asignación, trabajo Driver, ubicación, intentos, outcome, evidencia/POD, sellos, instrucciones, parciales, incidencias y receipt Buyer tienen rutas. Backend restringe GPS a Driver asignado y Buyer relacionado durante entrega activa; terminal/reasignación retira acceso. Acknowledgment Buyer, resultado Driver y receipt son hechos separados. | No se encontró contacto directo Driver–Buyer. No se verificó en cliente cada cierre, reprogramación ni adjunto. | Conservar la separación de hechos y el límite de GPS; no habilitar contacto directo sin decisión. | Operations Android, Buyer Flutter, Platform. Controllers/tests de autorización; consumo visual **NOT MEASURED**. |
| Crédito, pagos y reembolsos — P1 | Exposición/credit account, receivables, intents, pagos de línea de crédito/transferencia, conciliación, Stripe webhook, wallet, reservas/liberaciones y wallet refund para Sales Order cancelada existen. | No se encontró comando genérico aceptado para `RefundPayment`/`CorrectPayment`; los endpoints de retry se limitan a casos de conciliación. Uso real de provider, callback, conciliación y wallet en despliegue **NOT MEASURED**. | Definir operaciones y permisos de reembolso/corrección con Producto/Finanzas antes de agregarlos. | Platform/Portal y Buyer wallet. Pruebas de integración existentes no verifican proveedor productivo. |
| Documentos/evidencia — P2 | Listar, generar, regenerar, reemplazar, versionar, guardar checksum, adjuntar evidencia y descargar documento privado están expuestos. Descarga se autoriza por sujeto y ámbito. | ClamAV, object storage productivo, retención y restauración **NOT MEASURED**; no se encontraron URLs públicas como contrato. | Verificar adaptadores operativos y política de retención antes de prometer disponibilidad/ciclo de vida completo. | Portal, Platform, Buyer. OpenAPI y tests; servicios productivos no medidos. |
| Notificaciones y auditoría — P2 | Inbox, leído/no leído, preferencias, push subscriptions y `/audit-logs` están implementados. Business Traceability y Security Audit son separados; outbox/inbox trabajan con reintentos/deduplicación. | No se encontró adaptador de entrega email ni proveedor de push nativo activo en el contrato de API; no hay ruta cliente de Security Audit. `/notifications/unread` y el filtro unread coexisten. `safe_metadata` de cada callsite **NOT MEASURED**. | Confirmar canales reales, contexto de deep links y consumo antes de deprecar duplicados. Transporte es al menos una vez; no declarar exactly-once. | Los cuatro clientes. Pruebas puntuales; entrega de proveedor y telemetría **NOT MEASURED**. |
| Aislamiento multitenant — P0 antes de producción | PostgreSQL central compartido conserva IAM/gobierno; cada Tenant puede tener una DB física de negocio con binding y anclas de Workspace READY. El router enruta capacidades habilitadas a la DB del Tenant; el corte completo de todas las lecturas, escrituras y workers aún no está verificado. V84 impone Tenant:Workspace 1:1. El último inventario disponible, V174, clasifica 217 tablas: 181 con RLS/FORCE directo, 10 con RLS/FORCE parent-scoped y 7 `CROSS_SCOPE_WORKER`; ese corte no muestra tablas Workspace sin policy, pero no está actualizado tras V177. V177 agrega RLS/FORCE directo Tenant/Workspace a `integration.inbox_event`; `RlsRuntimeDatabaseIsolationIT` reporta 18/0 el 22-10-2026. Las pruebas de aislamiento V173–V175 y las seis tablas hijas del Tenant DB se ejecutaron contra PostgreSQL 18.4 desechable. Docker local fue observado en central V188 y Tenant V41 el 22-10-2026; el repo ahora contiene migraciones Tenant V42/V43 y no hay una observación Docker posterior. La observación local no prueba despliegue. Los tests de activación/provisioning comprueban tareas por Tenant, bindings separados e idempotencia; `TenantBusinessDatabaseRouterIT` cubre dos DB físicas, filas ajenas y rechazo 503 sin binding. | No se ha hecho cutover productivo ni se inspeccionaron bases desplegadas. El routing solo se afirma para las capacidades probadas y habilitadas. V169 elimina el uso de `app.cross_scope_workspace_scan` como bypass para el runtime en colas; V175 limita scans globales al rol lector dedicado; V178/V179/V181 reservan rutas internas al principal de operador separado. Persiste el límite de confianza documentado: una sesión comprometida `nexa_internal_operator` puede establecer otro UUID de operador. | Completar la cobertura de cada flujo antes de ampliar routing; regenerar el inventario RLS después de V177; verificar bindings READY, pools/secretos y ACL en el entorno objetivo; ejecutar backup/restore y migración como gates antes del cutover. No reescribir migraciones publicadas. | Todos. Las pruebas locales y desechables no certifican aislamiento de un despliegue. |
| Database / normalización — P1 | El repo contiene migraciones centrales hasta V188 y Tenant hasta V43. V169 separa privilegios de migrator/runtime/provisioner; V170 aplica RLS parent-scoped en diez tablas; V171 agrega restricciones compuestas Tenant/Workspace; V172 fija seguridad invoker en la vista Buyer; V173/V174 protegen memberships; V175 limita el scope reader; V176–V181 restringen permisos de auditoría y operador, incluido el RLS directo de `integration.inbox_event` en V177; V182–V188 continúan contratos de activación, catálogo, Buyer y política operativa. | Docker local fue observado en central V188 y Tenant V41 el 22-10-2026, antes de que el repo incorporara V43; no hay observación Docker posterior a V42/V43. Las pruebas PostgreSQL desechables validan migraciones y rutas cubiertas, no prueban normalización total, roles/ACL de despliegues o datos productivos. | Mantener cambios aditivos; revisar claves, constraints, índices, retención e integridad en cada migración. Completar la cobertura de rutas físicas por Tenant antes de cutover. | Operaciones de DB. `TenantBusinessDatabaseRouterIT` y `RefreshSessionPostgresIntegrationTests` reportaron 18/0 y 2/0 el 22-10-2026 (pruebas/fallos); otras suites corresponden a reportes anteriores. `ModernPostgresMigrationTests` reportó 1/0 en la misma fecha. Despliegues **NOT MEASURED**. |
| Planes y tiers — P1 conformidad de Producto | `referencePlan` se almacena como metadato. `plan-usage` agrega contadores y etiquetas/límites fijos; comparación devuelve opciones/precios fijos; no se encontró enforcement de cuotas. | Estos valores parecen planes/cuotas aunque el Blueprint difiere plan, subscription, entitlement y feature tier. El plan elegido no asigna un plan al Tenant; uso puede crear `STANDARD` de forma perezosa. | Las dos rutas quedan deprecated, no eliminadas. No consumirlas como precio, límite, entitlement o tier de datos. Esperar contrato futuro aceptado. | Portal/Platform. OpenAPI deprecation y tests. Consumo real no medido. |
| REST/OpenAPI — P1 | El snapshot tiene 395 paths y 465 operaciones; `OpenApiContractIT` pasó 1/1 el 22-10-2026. | `check-openapi-compatibility.py` contra `v1.1.0` y `origin/develop` termina con exit 1 y 427 diferencias. La auditoría de fuentes clasifica 423 como drift del snapshot: 248 parámetros (`153 If-Match`, `95 Idempotency-Key`) documentados opcionales aunque el runtime los valida; 82 propiedades de drafts ausentes del snapshot antiguo; 58 respuestas `200` que tag/runtime implementan como `201`, `202` o `204`; 3 `HttpHeaders` de framework publicados por error como query; 3 headers `X-Resume-Token` cuyo token ya era requisito de runtime y conserva el alias anterior; 14 requisitos de seguridad agregados a OpenAPI aunque la configuración runtime no cambió; 13 componentes por nombres/formas del snapshot; 1 propiedad `skuId` ya requerida por el DTO del tag; y 1 media type del 404 que ahora declara `application/problem+json`. Los otros 4 campos removidos sí cambian el cuerpo: ruptura aprobada por Owner para v1.3.0 en `GET /api/v1/client-accounts/me`, que omite `contactPerson`, `contactEmail`, `phone` y `buyerMembershipId`. La revisión verificó fila por fila las 58 respuestas, 3 parámetros fantasma, 3 credenciales de draft y 4 campos Buyer; los grupos de requiredness, drafts, seguridad, componentes y media type son clasificación por fuente/categoría, no ejecución individual de cada ruta/campo. La comparación cruda sigue exit 1; el gate SemVer no se trata como verde. | Mantener la migración explícita de Buyer y conservar la diferencia entre drift documental y el cambio wire. Antes de publicar, resolver formalmente el gate de compatibilidad según la política de release. | Los cuatro clientes y generación SDK. `OpenApiContractIT` verifica snapshot/runtime, no compatibilidad por sí solo. `python3 .github/scripts/test_check_openapi_compatibility.py` pasó 21/21 fixtures; la comparación de versiones real sigue exit 1. |

## Excepciones del inventario RLS

El inventario más reciente disponible es
`docs/security/rls-table-inventory-v174.tsv`; refleja el esquema hasta V174 y
no incorpora la política de V177 para `integration.inbox_event`. V173 y V161
quedan como evidencia histórica. V170 añadió y verificó en PostgreSQL
desechable políticas parent-scoped para diez tablas hijas. Las diez
son:

- `sales.purchase_request_line`, `sales.sales_order_line`;
- `tenant_management.notification_preference`,
  `tenant_management.operational_settings`,
  `tenant_management.workspace_settings`,
  `tenant_management.organization_invitation_role`,
  `tenant_management.role_permission`;
- `warehouse.inventory_reservation_line`,
  `warehouse.inventory_reservation_allocation`,
  `warehouse.reservation_shortage`.

`tenant_management.workspace_membership` ya tiene RLS forzado en V173: la
lectura por contexto de identidad y por par exacto de revalidación conserva
login/context discovery, y las escrituras exigen el Workspace actual. El
preflight usa un helper DDL fijo para no conceder SELECT amplio al migrator;
V173 revoca su ejecución al concluir. V169–V173 y los casos de rechazo
cross-Tenant pasaron con PostgreSQL desechable. Esto no inspecciona roles, ACL
ni filas de una base desplegada.

Seis tablas `CROSS_SCOPE_WORKER` permiten recorrido global de worker/callback
y deben validarse por su propio protocolo (claim/lease, reconstrucción de scope,
binding y deduplicación), no contarse como tablas RLS tenant-directas:
`business_documents.document_generation_request`,
`iam.security_notification_outbox`, `integration.change_event`,
`integration.outbox_event`, `payments.stripe_event_inbox` y
`tenant_management.payment_provider_route`. V177 y
`RlsRuntimeDatabaseIsolationIT` verifican RLS directo de
`integration.inbox_event` por Tenant/Workspace (reporte local: 18 pruebas, 0
fallos, 22-10-2026). Las pruebas revisadas de webhook/outbox cubren flujos y
replay; la cobertura RLS cross-Tenant de las seis tablas globales restantes no
se verificó aquí. No se inspeccionaron roles ni ACL de una base desplegada. La
comprobación de que `public` solo contiene `flyway_schema_history` corresponde a
PostgreSQL desechable.

## Integridad referencial pendiente

La lectura estática de migraciones identifica invariantes de referencia que el
esquema actual no fuerza en todos los casos. No demuestra que existan filas
incorrectas; no se consultó una base desplegada.

| Prioridad | Evidencia AS-IS | Riesgo y siguiente gate |
|---|---|---|
| Resuelto en V171 | `V9__create_membership_administration_events.sql` declaraba FKs independientes para Tenant, Workspace y las membresías actor/objetivo. | V171 valida los datos existentes y agrega FKs compuestas a `workspace(tenant_id,id)` y `workspace_membership(workspace_id,id)`; rechazo de pares cruzados verificado en PostgreSQL desechable. |
| P2 | `V113__customer_field_visit_evidence.sql` no declara FK de Tenant/Workspace, cuenta cliente ni membership. | RLS limita el scope de la fila, pero no valida las referencias. Evaluar FKs compuestas con `workspace`, `client_account` y `workspace_membership`; primero medir huérfanos y referencias cruzadas. |
| Resuelto en V171 | `V25__create_organization_registration.sql` referenciaba Tenant y Workspace por separado; `V85` exigía ambos no nulos si el estado era `ACTIVE`, sin validar que formasen el par correcto. | V171 preflight valida la historia y agrega restricción de par opcional y FK compuesta. Migración y rechazo de referencias cruzadas verificados en PostgreSQL desechable. |
| P3 | `sales.sales_order_sequence` (`V13`) y `logistics.command_idempotency` (`V18`) guardan Tenant/Workspace sin FK al par de Workspace. | Pueden quedar datos técnicos huérfanos. Confirmar ciclo de vida y retención antes de agregar FKs o cascadas; no inferir borrado permitido. |

V4/V12 sí establecen las claves únicas compuestas necesarias para varias de
estas referencias; V84 fuerza un Workspace por Tenant. Eso evidencia el modelo
actual Tenant:Workspace 1:1 y no acredita integridad de los datos de ningún
entorno vivo. Las migraciones publicadas se conservan; cualquier corrección
requiere una migración aditiva con preflight que falle ante datos inconsistentes.

## Data tier, capas y topología

Blueprint separa interfaces, casos de uso, dominio, puertos/adaptadores,
persistencia e infraestructura de runtime. Tenant/RLS, autorización y workers
son controles transversales. Esta separación lógica no prueba aislamiento
físico. También separa los tiers comerciales: Plans, Subscriptions,
Entitlements y Feature tiers están diferidos en V1; el campo `referencePlan` y
el plan de servicio de un manifiesto de despliegue no enrutan ni aíslan datos.

| Dimensión | Evidencia AS-IS | Brecha frente a TARGET / verificación |
|---|---|---|
| Base central | `application.yml` define un DataSource/Hikari central y RLS aplica contexto Tenant/Workspace a conexiones compartidas. Flyway runtime toma esa URL y migra `classpath:db/migration`. | Corresponde al AS-IS shared PostgreSQL; no es una base física por Tenant. |
| Router de base Tenant | `TenantBusinessDatabaseRouter` revalida membership y autorización vía la base central, exige binding `READY`, valida identidad de la base objetivo, aplica RLS y abre transacción en un pool por binding. Falta de binding no hace fallback a la base central. El resumen de pedido consulta proyección y relación Buyer usando el mismo `JdbcTemplate` Tenant. | La composición principal y credenciales locales están en perfil `local`, opt-in. Los adapters con `TenantBound*` cubren grupos de ventas (incluido exporte de resumen), catálogo, pagos, fulfillment, warehouse, wallet, documentos, traceability y notificaciones; otras rutas siguen usando persistencia central/RLS. El nuevo slice se verificó con dos bases físicas y `nexa_runtime`, pero el cableado no es universal ni está verificado en producción. |
| Pools | Los límites observados (por defecto 8 pools cacheados y 4 conexiones por pool en router core) se aplican por proceso. | No expresan capacidad global: réplicas multiplican pools y conexiones. Dimensionamiento, saturación, secret rotation y operación de flota no están medidos. |
| Workers y callbacks | Hay configuraciones locales específicas que enrutan trabajos por Tenant; webhook persiste primero un evento/ruta opaca en central y después el worker resuelve ámbito. | El guard del router usa `ThreadLocal` del mismo hilo; no propaga por sí solo contexto a ejecución async. Falta mapear y probar exhaustivamente cada job/callback y ejecutar rutas físicas en el runtime objetivo. |
| Migraciones de negocio | `db/migration` ejecuta contra central. El CLI local usa `db/tenant-migration` para bases Tenant. | No se encontró un provisioner, proveedor de secretos ni migrador Tenant de producción. No hay evidencia de migración/cutover vivo. |
| Configuración de despliegue | `render.yaml` declara perfil `s3`, URL central y Flyway central. Compose usa perfil `local` con capacidades Tenant configurables. | Son manifiestos versionados, no configuración efectiva observada. Producción, flags, bases, credenciales, migraciones, backup/restore y cutover son **NOT MEASURED**. |

En perfil no `test`, `TenantBoundCatalogCommercialPolicyConfiguration` crea un
adapter cuando se habilita `nexa.tenant-business.catalog-management.enabled`,
pero el bean core del router se proporciona desde una configuración limitada a
`local`. La lectura estática predice una dependencia faltante si esa flag se
activa en otro perfil; no ejecuté el arranque de ese perfil. Resolver el
proveedor y wiring de producción es un gate de infraestructura, no una decisión
de plan comercial. La TARGET aceptada es una base central IAM/gobierno más una
base física de negocio por Tenant; su implementación requiere provisión,
identidad de base, credenciales aisladas, pools, migraciones, backup/restore,
trabajadores y consistencia cross-database antes del cutover.

## Contratos y secuencias por consumidor

### Identidad, sesiones y recuperación

La autoridad de Tenant y Workspace procede de la membresía comprobada en el
servidor. No se acepta un `tenantId`/`workspaceId` del cliente como autoridad.
Cada respuesta de acceso incluye superficie, Tenant, Workspace, membresía,
roles, permissions y versión de autorización en `SessionContext`.

**Native (Buyer Flutter = `PORTAL`; Operations Android = `PLATFORM`):**

1. `POST /api/v1/authentication/identity-sign-in`, header
   `X-Nexa-Client: NATIVE`, body
   `{"identifier":"...","password":"...","surface":"PORTAL"}`.
2. `SESSION_ESTABLISHED`: usar el access token de la respuesta y guardar el
   refresh token devuelto en `X-Nexa-Refresh-Token` mediante almacenamiento
   seguro del SO. `NO_WORK_CONTEXT`: terminar sin crear sesión. Para
   `CONTEXT_SELECTION_REQUIRED`, el ticket de cinco minutos viene solo en
   `X-Nexa-Context-Ticket`, nunca en el JSON.
3. Con ticket, `GET /api/v1/me/access-contexts` lleva
   `X-Nexa-Client: NATIVE`, `X-Nexa-Surface: PORTAL|PLATFORM` y
   `X-Nexa-Context-Ticket`. Con más de una autoridad disponible no enviar a
   la vez ticket y Bearer.
4. `POST /api/v1/me/access-context-selections`, body
   `{"membershipId":"<UUID>"}`, `X-Nexa-Surface`, exactamente una autoridad
   (ticket o Bearer) y marcador Native. El servidor revalida identidad,
   membresía y scope; el ticket se consume o la familia de sesión invocadora
   se revoca.
5. Renovar con `POST /api/v1/authentication/refresh`,
   `X-Nexa-Surface` y `X-Nexa-Client: NATIVE`, enviando el refresh anterior en
   `X-Nexa-Refresh-Token`. Respuesta incierta tras rotación requiere decisión
   de retry antes de prometer restauración automática.

Para desbloqueo biométrico, el cliente protege ese refresh token con Keychain/
Keystore y deja que el sistema operativo autorice su uso. La API nunca recibe
la huella, imagen facial ni un booleano de verificación. La sesión vuelve a
validarse mediante refresh nativo.

**Web (Platform = `PLATFORM`; Portal = `PORTAL`):** usar
`POST /api/v1/auth/workspace-previews` para previsualización pública por slug,
`GET /api/v1/authentication/csrf`, y
`POST /api/v1/authentication/sign-in` con
`{"identifier":"...","password":"...","workspaceSlug":"...","surface":"PLATFORM|PORTAL"}`.
Web recibe refresh en cookie de superficie; Origin y CSRF se comprueban en
operaciones de cookie. Para refresh/sign-out usar la misma superficie. No
transportar el refresh de Web en almacenamiento de aplicación.

**Password:**

- `POST /api/v1/auth/password-reset-requests` recibe
  `{"email":"persona@example.test","surface":"PORTAL|PLATFORM"}` y siempre
  responde con el mismo shape `{"message":"..."}`. No envíes redirect ni
  enlace arbitrario. Web omite `X-Nexa-Client`; Native agrega
  `X-Nexa-Client: NATIVE` para pedir la ruta nativa de su superficie. El request
  usa throttling; el enlace llega al correo con URL permitida por servidor. Para
  Native configura `NEXA_PLATFORM_NATIVE_RESET_URL` y
  `NEXA_PORTAL_NATIVE_RESET_URL` en HTTPS.
- `POST /api/v1/auth/password-resets` recibe
  `{"token":"<token de un uso>","newPassword":"..."}` y devuelve `204` sin
  cuerpo. Token inválido/vencido/usado produce error tipado; el cliente no debe
  confirmar que una cuenta existe. Intentos de consumo se limitan por IP: diez
  por cada diez minutos por defecto; el backend revoca sesiones al completar.
- `POST /api/v1/me/password-changes` recibe
  `{"currentPassword":"...","newPassword":"..."}` y devuelve `204`.
- `GET /api/v1/me/sessions`, `DELETE /api/v1/me/sessions/{sessionId}` y
  `POST /api/v1/me/session-revocations` afectan únicamente sesiones propias.
  No enviar ni guardar contraseñas para implementar “recordarme”.

### Alta y activación de empresa

`POST /api/v1/tenant-management/organization-registration-drafts` no lleva
body; devuelve `201`, `Location`, `ETag` y `resumeToken`. Guardar el token de
forma segura y mandarlo en `X-Resume-Token` (se acepta también el nombre de
compatibilidad `X-Organization-Registration-Token`); no incluirlo en la URL.

Editar con `PUT /api/v1/tenant-management/organization-registration-drafts/{registrationId}/steps/{step}`
y finalizar con `POST /api/v1/tenant-management/organization-registration-drafts/{registrationId}/submit`.
Ambas operaciones usan `If-Match` con el ETag más reciente e
`Idempotency-Key`; una clave repetida con payload distinto es conflicto. Cada
edición devuelve nuevo ETag. Payload JSON por step:

| Step | Campos |
|---|---|
| 1 | `legalName`, `displayName` |
| 2 | `workspaceName`, `workspaceSlug`, `storageSiteName`, `storageSiteAddress` |
| 3 | `operationCategory` |
| 4 | `founderEmail`, `founderDisplayName` |
| 5 | `referencePlan` (metadato de referencia; no concede tier ni cuota) |
| 6 | `termsVersion`, `termsAccepted: true` |

El endpoint público deja un registro en revisión; no crea directamente el Tenant
activo. La activación interna es `POST
/api/v1/internal/organization-registrations/{registrationId}/activation`; el
rechazo es `POST
/api/v1/internal/organization-registrations/{registrationId}/rejection`.
Ambos requieren autoridad de operador de sistema. Los endpoints one-shot
`POST /api/v1/tenant-management/organization-registrations` y su GET de estado
son compatibilidad deprecated; nuevos clientes usan drafts.

### Buyer Flutter / Portal

Con sesión `PORTAL`, servidor verifica la relación activa Buyer. Rutas base:

- Perfil/cuenta vinculada: `GET /api/v1/client-accounts/me` y
  `GET /api/v1/client-accounts/me/credit-exposure`.
- Catálogo y disponibilidad: `GET /api/v1/catalog-items`, sus filtros/detalle,
  `GET /api/v1/buyer/warehouses` y `GET /api/v1/client-accounts/{clientAccountId}/catalog-offers/{catalogItemId}`.
- Draft de compra: `POST /api/v1/buyer/purchase-request-drafts` con
  `clientAccountId` y `requestedDeliveryDate`; luego PUT de `lines`,
  `destination` y `preferences`. Usar GET `review`, POST `route-previews` y
  POST `submissions` con ETag/`If-Match` donde publicado.
- Órdenes/entrega: `GET /api/v1/buyer/deliveries`, detalle, eventos, receipts,
  live-location autorizada y documentos/exportes del Buyer. La ubicación solo
  se expone para entrega activa y cuenta Buyer relacionada.
- Wallet: `GET /api/v1/buyer/wallet`, recargas y estado de recarga.
- Instrucciones de entrega Buyer tienen contrato separado de los resultados y
  receipts del Driver.
- Favoritos personales de ofertas del catálogo: primero toma `sellableSkuId`
  de una fila actualmente visible de `GET /api/v1/catalog-items`; luego usa
  `PUT /api/v1/buyer/favorites/{sellableSkuId}` para guardar y
  `GET /api/v1/buyer/favorites?page=0&size=25` para leer páginas (tamaño máximo
  100). `DELETE` en la misma ruta elimina; ordena por `created_at` descendente
  y SKU como desempate. GET responde `200` con `items`, `page`, `size`,
  `totalItems` y `totalPages`; PUT responde `200` con `sellableSkuId`,
  `favoritedAt` y `currentOffer`; DELETE responde `204`, también si ya no había
  asociación. Si una oferta queda oculta o inactiva, GET conserva su SKU y
  `favoritedAt` con `currentOffer: null`, para que pueda quitarse. PUT exige
  que la oferta siga activa y visible para este Buyer. Los errores se devuelven
  como `application/problem+json` (`NexaProblemDetail`): `400` por entrada
  inválida, `401` sin sesión, `403` sin Buyer/relación/permiso, `404` si PUT no
  encuentra una oferta actual y `503` si no está disponible la base Tenant.
  El servidor toma Tenant, Workspace y membership del contexto verificado; el
  cliente no los envía como autoridad. GET requiere `SALES_BUYER_READ` y
  `CATALOG_READ`; PUT requiere `SALES_BUYER_WRITE` y `CATALOG_READ`; DELETE
  requiere `SALES_BUYER_WRITE`. Todas requieren sesión `PORTAL`, rol Buyer y
  relación Buyer activa en el Tenant. El orden sugerido es leer catálogo,
  guardar el SKU y refrescar la página; borrar los tombstones cuando se desee.
  La ruta solo está disponible con perfil `local` y
  `nexa.tenant-business.catalog-favorites.enabled=true`.

No hay contrato de reorder ni Direct Order Buyer encontrado. No usar un
endpoint de venta manual de Operations para simular compra Buyer.

#### Draft Buyer: request/response concreto

El actor debe tener sesión `PORTAL` y una relación Buyer activa con la cuenta.
El `clientAccountId` del request identifica el objeto; no concede autorización.
El servidor comprueba la relación, Tenant/Workspace y pertenencia del draft.
OpenAPI documenta `bearerAuth`; la autorización de objeto se aplica en el
servicio, no como permiso por ruta en el schema OpenAPI.

```http
POST /api/v1/buyer/purchase-request-drafts
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{"clientAccountId":"<UUID>","requestedDeliveryDate":"2026-11-02"}
```

Respuesta: `201`, `Location`, `ETag` y
`BuyerPurchaseRequestDraftView` (`id`, `clientAccountId`, `buyerMembershipId`,
`status`, `version`, fechas/preferencias, resultado de crédito, líneas, destino,
ruta, selección de Warehouse y marcas de tiempo).

```http
PUT /api/v1/buyer/purchase-request-drafts/<draftId>/lines
If-Match: "<version actual>"
Content-Type: application/json
```

```json
{"lines":[{"skuId":"<UUID>","quantity":12,"unit":"BOX","notes":""}]}
```

Respuesta: `200`, `ETag` actualizado y `BuyerPurchaseRequestDraftView`.
`skuId` y `quantity` son obligatorios; se aceptan de 1 a 100 líneas. Destino
usa `{"addressId":"<UUID>"}`; preferencias usan
`{"paymentPreference":"<valor vigente>","requestedDeliveryDate":"2026-11-02"}`.
`GET .../{draftId}/review` responde `BuyerPurchaseRequestReviewView` con
`productsComplete`, `destinationComplete`, `routeValidated`,
`commercialReviewComplete`, `readyToSubmit` y `missing`. Ruta previa y submit
devuelven `200`, `ETag` y `BuyerPurchaseRequestDraftView`; submit exige
`If-Match` y `Idempotency-Key`. Los errores usan `NexaProblemDetail` según la
tabla REST/OpenAPI.

La prueba de OpenAPI verifica schemas Buyer separados de los drafts manuales
de Operations, campos requeridos del request y headers ETag de las mutaciones
Buyer. El snapshot anterior reutilizaba incorrectamente `DraftView` y
`ReviewView` del modelo manual; comparar snapshot con runtime no detectaba esa
colisión porque ambos se generaban con la misma configuración.

### Operations Android / Platform

Operations Android usa `PLATFORM` + `NATIVE`. Platform Web usa `PLATFORM` +
cookie. La membresía seleccionada y permisos del servidor determinan
Warehouse/Driver; el cliente no selecciona Tenant por header.

- Admin: `/api/v1/workspaces`, `/api/v1/workspace-memberships`, roles,
  invitations, settings y Tenant configuration, según permisos explícitos.
- Configuración operativa por Workspace: `GET/PATCH
  /api/v1/workspaces/{workspaceId}/operational-settings`. El `PATCH` requiere
  `If-Match`; la política comercial de fecha mínima no forma parte de estos
  ajustes y se mantiene global conforme a `docs/integration/buyer-delivery-lead-policy.md`.
- Warehouse: `/api/v1/warehouses`, zonas, access grants, serviceability,
  selection policy, profile, ubicación y horas. Los aliases viejos están
  marcados deprecated.
- Inventory: `/api/v1/inventory/lots`, `/api/v1/inventory/inbound-receipts`,
  `/api/v1/inventory/adjustments`, `/api/v1/inventory/transfers`, cycle counts,
  HOLD/quarantine/disposition, physical-allocation scans, reservations y
  FEFO. Las mutaciones versionadas usan `If-Match`; las operaciones
  reintentables llevan `Idempotency-Key`.
- Temperatura por lote (solo recepción/almacén): `GET
  /api/v1/inventory/lots/{lotId}/temperature-readings?page=0&size=25` devuelve
  la lectura registrada al recibir y evaluaciones `RECEIVING` paginadas
  (máximo 100). Warehouse requiere permiso `warehouse.read` y grant activo del
  almacén. BOM requiere rol `BUSINESS_OPERATIONS_MANAGER`, superficie
  `PLATFORM` y permiso `inventory.temperature.audit.read`; no necesita grant
  por Warehouse. El endpoint limita Tenant/Workspace/lote, es read-only y no
  mezcla eventos de Fulfillment.
- Operations sales: `/api/v1/purchase-requests`, sus líneas, submissions,
  material-changes, aprobación/rechazo y conversiones; Sales Order manual usa
  `/api/v1/sales-orders/manual-drafts` y submission. La fecha solicitada se
  valida contra el mínimo configurado en el Workspace; el error tipado
  `VALIDATION_ERROR` incluye fecha pedida, primera fecha permitida y mínimo.
- Fulfillment y dispatch: `/api/v1/fulfillments`, assignments, staging,
  picking, packing, outbound checks, dispatch windows, `/api/v1/dispatch` y
  `/api/v1/deliveries`.
- Driver asignado: `/api/v1/driver/workdays`, `/api/v1/driver/loads`,
  `/api/v1/driver/deliveries`, location, attempts, outcome, POD, evidence,
  incidents e instrucciones. Operaciones de Driver validan asignación activa.

La credencial `PLATFORM` no sustituye la autorización de cada acción: el
servidor usa membresía y permisos vigentes del Tenant/Workspace, grant explícito
de Warehouse cuando corresponde y asignación activa para acciones Driver.
`SessionContext.permissions` es una proyección para navegación; cada comando
vuelve a autorizar en backend y valida objeto/scope. OpenAPI no publica una
capacidad RBAC uniforme por cada una de las 465 operaciones. Las rutas de
consola interna y activación usan credenciales de operador distintas de la
membresía de un Tenant.

### Portal, finanzas y comunicación

- Platform/Portal autorizados acceden a receivables, credit account,
  `payments`, conciliación y ajustes bajo el scope de Tenant/Workspace y
  permisos. Los Buyer solo usan sus rutas de wallet/orden.
- Documentos: `/api/v1/business-documents`, generaciones, versiones,
  reemplazos, eventos, evidencias y `/downloads`; no usar URL de objeto pública.
- Inbox: `/api/v1/notifications`, unread, preferencias, read/unread y push
  subscription. Las mutaciones de lectura/unregister responden sin body (`204`)
  en el contrato corregido.
- Business Traceability está en `/api/v1/audit-logs`. No equivale a Security
  Audit ni a un timeline global de todos los objetos.

## REST/OpenAPI y compatibilidad

El snapshot OpenAPI (395 paths, 465 operaciones) fue generado y comparado con
la salida runtime mediante `OpenApiContractIT`. Antes de las correcciones, 41
operaciones que emiten `201` estaban documentadas como `200`, cinco `202`
estaban documentadas como `200`, faltaban cinco `204` incondicionales y cinco
`204` condicionales, y faltaban 22 headers `ETag` en respuestas `201`. También
se detectaron 93 operaciones cuyo servicio exige `Idempotency-Key` aunque
OpenAPI la marcaba opcional; nueve comandos de Purchase Request/Sales Order sí
aceptan no enviarla. La revisión encontró además 15 operaciones autenticadas
sin `security` publicado: siete requieren Bearer, dos la credencial de operador
de sistema y seis ambas cabeceras de operador interno. Se publicaron esos
requisitos; sign-out documenta alternativa Bearer o anónima solo para limpiar
cookie. Las tres operaciones de draft de onboarding ahora publican
`X-Resume-Token` obligatorio y su alias de compatibilidad. Las mutaciones y
lecturas de Buyer draft exponen sus ETags observados. El grant inicial de
Warehouse acepta creación sin `If-Match`; solo reactivar un grant revocado
requiere el ETag actual.

Rutas ya marcadas deprecated al cierre del diff:

- `POST/GET /tenant-management/organization-registrations[/{registrationId}]`
  — flujo one-shot; nuevos clientes usan el draft de seis pasos. El API y
  pruebas existentes aún usan el flujo viejo; el consumo externo no se midió.
- `GET /plan-usage` y `GET /plan-comparison` — metadatos sin cuotas ni tiers
  aceptados en V1. Una prueba llama `plan-usage`; no hay evidencia de uso real.
- Warehouse: `GET/PATCH /warehouses/{id}/operational-profile` y
  `GET/PATCH /warehouses/{id}/locations` delegan a `/profile` y `/location`.
  El alias de ruta no aparece en pruebas de ejecución; el consumo externo no
  se midió.
- Dispatch: `POST /dispatch-orders/{id}/failed-delivery-attempts` y
  `/delivery-partials` delegan a `/delivery-attempts` y `/partial-deliveries`.
  Las pruebas usan las rutas canónicas; el consumo externo no se midió.
- `GET /catalog/products/{productId}/prices` es proyección por producto;
  existen consultas por SKU, pero no se verificó equivalencia de semántica.
- `POST /purchase-requests/{id}/cancellations` ejecuta cancelación Sales y
  tiene prueba de runtime. No es alias mecánico de la retirada Buyer
  (`/withdrawals`), que registra otra transición. Su reemplazo requiere decisión
  de dominio y migración de consumidores.

`OpenApiContractIT` protege `deprecated=true` para las 12 rutas identificadas.
No se inspeccionó código Flutter/Android/Portal/Platform ni telemetría; que un
alias no aparezca en pruebas API no demuestra que un cliente externo no lo use.
No retirar rutas hasta tener evidencia de tráfico/consumidores y compatibilidad.
El endpoint `GET /api/v1/access-matrix` conserva `permissions` como proyección
legacy de `PermissionPolicy` y agrega `permissionKeys` con las claves tipadas
de `PermissionCatalog` para los roles built-in. El primero difiere del segundo,
por ejemplo entrega cero claves legacy para
`BUSINESS_OPERATIONS_MANAGER`; el segundo es un catálogo declarativo, tampoco
calcula autoridad efectiva por Tenant, superficie, objeto, grant Warehouse o
rol custom. `PermissionPolicy` aún participa en guards legacy, así que queda
una brecha de convergencia que requiere migrar/auditar esos guards antes de
presentar una matriz como acceso efectivo. No existe `MembershipRole.DRIVER`:
Driver opera como membresía `LOGISTICS` con permiso y asignación vigentes.

No se eliminan rutas por suposición: no hay telemetría de consumo para demostrar
que los aliases o los pares `GET /notifications/unread` y
`GET /notifications?unread=true` dejaron de usarse. También requieren análisis
de consumidor las proyecciones `/inventory` frente a `/inventory/lots` y el
historial de precios SKU/producto. `NexaProblemDetail` exige `code`,
`category`, `correlationId` y `retryable`; el cliente debe usar código/estado
tipados, no texto libre. Semántica general publicada: `400` request/validación,
`401` autenticación, `403` acceso denegado, `404` recurso no encontrado/no
visible, `409` conflicto de estado o idempotencia, `412` ETag obsoleto, `428`
falta `If-Match` en rutas que lo exigen, `429` límite excedido con
`Retry-After`, y `500/502/503/504` fallos técnicos. Los códigos de dominio
exactos son los que devuelve cada operación; no se deducen del texto `detail`.

## Decisiones abiertas y evidencia no medida

1. Aceptar política de contraseña, expiración/configuración por superficie y
   enlace de recuperación nativo; definir throttle de consumo inválido.
2. Elegir política de refresh ante respuesta perdida, reintento concurrente y
   replay; decidir qué metadatos de dispositivo registrar y paginación de
   sesiones.
3. Aceptar o rechazar passkeys y definir quién puede enrolar/revocar.
4. Proveer registro de versiones publicadas de términos y texto legal; validar
   aceptación contra una versión vigente sin fabricar contenido.
5. Definir ciclo de vida Tenant, exportación/cierre y autoridad legal,
   retención y borrado.
6. Aprobar onboarding/provisionamiento físico por Tenant, secret manager,
   capacidad de pools, backup/restore, jobs, ensayo de migración y gates de
   cutover. El estado actual no es producción aislada por Tenant.
7. Definir si V1/V2 tendrá planes, precios, cuotas, subscription/entitlement y
   cómo se asignan. Hasta entonces no usar la API de planes.
8. Definir relación/invitación/transferencia Buyer y consentimiento de
   contactos alternos.
9. Definir permisos, límites y evidencia de refund/correction de pagos.
10. Identificar consumidores y tráfico de rutas deprecated/duplicadas antes
    de retirar contratos.
11. Verificar en los entornos desplegados catálogo RLS, grants de roles,
    `CONNECT` heredado de `PUBLIC`, secrets, object storage, ClamAV, provider de
    pagos/email/push, telemetría y consumo de API. Todo ello está **NOT
    MEASURED** en esta auditoría.
12. Definir y verificar política de retención, legal hold, borrado y
    anonimización. Blueprint las mantiene como gates de Producción/Legal; no se
    encontró una clase de retención implementada ni medida en este alcance.

## Evidencia de repositorio y gates

- Blueprint fija como TARGET la base central de IAM/gobierno y una base física
  de negocio por Tenant; shared PostgreSQL/RLS es AS-IS. Fuente consultada:
  `blueprint@27e7ea8f7d8a671500039d33e1a4bbab62464691`.
- V84 incluye unicidad de Workspace por Tenant; V169 protege el rol runtime y
  permisos de colas. V169 es aditivo y no reescribe migraciones publicadas.
- La base de routing describe expresamente su estado opt-in en
  [tenant-database-routing-foundation.md](./architecture/tenant-database-routing-foundation.md).
- Los tests PostgreSQL son fixtures desechables; no son evidencia de una
  instalación productiva ni de aceptación de Producto, UX, sistema o release.
- Los detalles de payload, respuestas y headers se mantienen en el
  [snapshot OpenAPI](./openapi/openapi.json). El test de contrato debe generarlo
  y compararlo en cada cambio de esta auditoría.
