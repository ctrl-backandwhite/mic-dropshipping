# NX036 Dropshipping — Backend (`mic-dropshipping`)

Spring Boot 4.x / Java 21. Catálogo, pedidos, pagos, fulfillment y BFF de autenticación
(Bearer JWT). Este README documenta la **configuración de integraciones externas**; empieza por
la de **Cainiao** (logística), que es la más reciente.

---

## Integración con Cainiao (fulfillment internacional)

Conector de la red logística de Cainiao (菜鸶, Alibaba) para **crear envíos** y **rastrearlos** en
pedidos transfronterizos. Producto contratado: **Cainiao Cross-border E-commerce Export**.

- **Mock-first**: con `CAINIAO_ENABLED=false` (por defecto) funciona todo con datos simulados
  (tarifa real por país desde `cainiao_shipping_zone` + tracking que avanza con el tiempo). No
  rompe nada sin credenciales.
- **Real**: con `CAINIAO_ENABLED=true` + credenciales, llama al **gateway Link**.
- **Firma del gateway**: `data_digest = Base64( MD5( logistics_interface + appSecret ) )`.
- **Entornos (`CAINIAO_BASE_URL`)**:
  - Sandbox/Daily: `https://linkdaily.tbsandbox.com/gateway/link.do`
  - Pre-producción: `https://prelink.cainiao.com/gateway/link.do`
  - Producción: `https://link.cainiao.com/gateway/link.do`

### Dónde está en el código
| Pieza | Fichero |
|---|---|
| Transporte + firma del gateway | `infrastructure/integration/fulfillment/CainiaoLinkClient.java` |
| Crear envío / tracking / tarifa / cobertura + `msg_type` | `infrastructure/integration/fulfillment/CainiaoFulfillmentService.java` |
| Orquestación sobre el pedido (timeline, estados) | `application/service/FulfillmentService.java` |
| Webhook de push entrante (fase 2) | `api/controller/CainiaoWebhookController.java` → `POST /api/webhooks/cainiao` |

### `msg_type` usados
| Función | API (`msg_type`) | Tipo |
|---|---|---|
| Crear envío | `CAINIAO_GLOBAL_TAKING_ORDER` | Subscribe (llamas tú) |
| Consultar tracking | `LOGISTICS_DETAIL_QUERY` | Subscribe |
| Cancelar envío | `CAINIAO_GLOBAL_CANCEL_ORDER` | Subscribe |
| Validar dirección | `CAINIAO_GLOBAL_ADDRESS_ATTRIBUTE_QUERY` | Subscribe |
| Datos de etiqueta | `CAINIAO_GLOBAL_CLOUD_PRINT_QUERY` | Subscribe |
| URL de etiqueta (PDF) | `CAINIAO_GLOBAL_QUERY_WAYBILL_URL` | Subscribe |
| Push de tracking | `TRACEPUSH` | Register (push → tu webhook) |
| Sync de estado de fulfillment | `CAINIAO_GLOBAL_FULFILL_STATUS_SYNC` | Register (push → tu webhook) |

---

## Alta en la Open Platform de Cainiao (repetir para PROD en otra cuenta)

> Portal de **desarrolladores**: `https://open.cainiao.com/` (NO es `i.cainiao.com`, que es la cuenta
> de consumidor "Cainiao Guoguo" y no tiene credenciales de API). Las APIs de logística suelen exigir
> **verificación de empresa**.

### Paso 1 — Crear la aplicación
`Application management → Application list → New application`
- **Business Type**: `Cainiao Cross-border E-commerce Export`
- **Qualification Type**: `General user qualifications`
- **Application name**: p. ej. `nx036dropshipping`
- **Application description**: describe el caso (export transfronterizo + tracking, origen 1688).

### Paso 2 — Obtener credenciales
`Application Basic information` → copia:
- **AppKey** (= `logistic_provider_id` del gateway) → `CAINIAO_APP_KEY`
- **appSecret** (firma; **secreto**) → `CAINIAO_APP_SECRET`
- **Resource Code** (identificador del recurso de la app)

### Paso 3 — Enlazar APIs
`Application Configuration → Binding API → Select Scene` y marca:
- **Subscribe (las llama el backend)**: `CAINIAO_GLOBAL_TAKING_ORDER`, `LOGISTICS_DETAIL_QUERY`,
  `CAINIAO_GLOBAL_CANCEL_ORDER`, `CAINIAO_GLOBAL_ADDRESS_ATTRIBUTE_QUERY`,
  `CAINIAO_GLOBAL_CLOUD_PRINT_QUERY`, `CAINIAO_GLOBAL_QUERY_WAYBILL_URL`.
- **Register/Push (fase 2, llaman a tu webhook)**: `TRACEPUSH`, `CAINIAO_GLOBAL_FULFILL_STATUS_SYNC`.
- **NO necesarias** (salvo casos concretos): aduanas (`GLOBAL_CUSTOMS_*`, `CAINIAO_GLOBAL_CCEX/CCIM_*`),
  almacén (`CONSO_WAREHOUSE_*`), self-pickup (`PICKUPPOINT/POPSTATION`), callbacks granulares de
  red (`LINEHAUL/LASTMILE/SORTINGCENTER/HTCK_*`).

### Paso 4 — Configurar cada Register API (webhook)
Para `CAINIAO_GLOBAL_FULFILL_STATUS_SYNC` (y `TRACEPUSH` si pide dirección) → `Modify configuration`:
| Campo | Valor |
|---|---|
| Encoding format | `UTF-8` |
| Message format | `JSON` |
| Timeout (ms) | `5000` |
| Content-Type | `application/x-www-form-urlencoded;charset=UTF-8` |
| Request address | `https://<HOST_BACKEND>/api/webhooks/cainiao` |
| Interchange Request Address | `https://<HOST_BACKEND>/api/webhooks/cainiao` (la misma) |
| HTTP header Extension Parameters | *(vacío)* |
| Sensitive data / 字段脱敏 | *(vacío)* |

`<HOST_BACKEND>` por entorno:
- **Desarrollo**: `back-dropshipping-des.up.railway.app`
- **Producción**: el dominio del backend de prod
- **Local**: `http://localhost:18082/api/webhooks/cainiao` (Cainiao no llega a localhost → usa un
  túnel público tipo **ngrok** para pruebas reales de push).

Un único endpoint atiende todos los `msg_type` (dispatcha por `msg_type`); pon la misma URL en todas.

### Paso 5 — Resource Code / Desensitization Audit
En el asistente (paso "Resource Code configuration"), el diálogo "Desensitization Audit" → **Skip**.
Solo hace falta el audit si necesitas **leer PII desenmascarada** de las respuestas de Cainiao; en
este flujo el backend ENVÍA la dirección (la tiene del pedido), no la lee de vuelta.

### Paso 6 — Enviar a revisión
Completa el asistente (preview → audit) y **envía la app a revisión**. Mientras tanto, el **sandbox**
(`linkdaily.tbsandbox.com`) y `Online joint debugging` permiten probar.

---

## Configuración del backend (variables de entorno)

`application.yml` lee estas variables (todas con prefijo `CAINIAO_`):

| Variable | Valor |
|---|---|
| `CAINIAO_ENABLED` | `false` hasta que la app esté aprobada **y** el mapeo de campos del payload/respuesta esté hecho; luego `true`. |
| `CAINIAO_APP_KEY` | AppKey de la consola |
| `CAINIAO_APP_SECRET` | appSecret (**solo por entorno; NUNCA en el repo ni en capturas**) |
| `CAINIAO_BASE_URL` | sandbox `https://linkdaily.tbsandbox.com/gateway/link.do` · prod `https://link.cainiao.com/gateway/link.do` |

**Dónde se ponen:**
- **Local**: `infra/docker/.env` (gitignored) + passthrough ya presente en `infra/docker/docker-compose.yml`
  (ojo: ese compose está gitignored, es config local).
- **Railway (desarrollo)**: variables del servicio `mic-dropshipping`
  (`railway variables --service mic-dropshipping --set "CAINIAO_...=..."`).
- **Producción**: las mismas variables en el entorno de prod, con el **appKey/appSecret de la cuenta de
  prod** y `CAINIAO_BASE_URL` = gateway de **producción**.

### Activación final
Cuando (1) la app esté **aprobada** por Cainiao y (2) estén mapeados los **campos reales** del payload
y la respuesta de `CAINIAO_GLOBAL_TAKING_ORDER` y `LOGISTICS_DETAIL_QUERY` (hoy marcados `TODO(real)`
en `CainiaoFulfillmentService` / `FulfillmentService.applyPush`): pon `CAINIAO_ENABLED=true` y reinicia.

---

## Checklist para PRODUCCIÓN (cuenta nueva)
1. Crear app en `open.cainiao.com` con la cuenta de prod (Pasos 1–2) → nuevo `appKey`/`appSecret`.
2. Enlazar las mismas APIs (Paso 3) y enviar a revisión (Pasos 4–6).
3. En el webhook (Paso 4) usar el **dominio del backend de prod**.
4. Variables en el entorno de prod: `CAINIAO_APP_KEY/SECRET` de prod, `CAINIAO_BASE_URL` = gateway de
   **producción**, `CAINIAO_ENABLED=true`.
5. Verificar: crear un pedido de prueba → se crea el envío → llega tracking (pull) y/o push al webhook.
