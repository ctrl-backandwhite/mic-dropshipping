# Diseño — Cobro externo de afiliados (transferencia bancaria y PayPal)

Fecha: 2026-07-25
Ámbito: backend (`mic-dropshipping`) + frontend (`front-dropshipping`)

## Objetivo

Permitir que un afiliado cobre sus comisiones **APPROVED** por tres vías a elección:
**wallet** (crédito de tienda, ya existente), **transferencia bancaria** o **PayPal**.
La ejecución del pago externo es **manual por un ADMIN** (no hay API automática de pagos):
el afiliado guarda sus datos de cobro, solicita el pago, y el ADMIN realiza la
transferencia/PayPal por su cuenta y marca el payout como PAGADO con una referencia.

## Decisiones tomadas (brainstorming)

1. Ejecución del pago externo: **manual por el operador/ADMIN** (sin PayPal/Wise API).
2. Métodos: el afiliado **elige entre wallet / banco / PayPal** al solicitar el pago.
3. Datos de cobro: **guardados en el perfil de afiliado** (reutilizables, editables).
4. Aprobación/rechazo de payouts: **solo ADMIN** (panel `/api/admin/affiliate`).
5. Cambiar datos de cobro exige **confirmar la contraseña** (anti-secuestro de cuenta).

## Estado actual (punto de partida)

- Comisión: `PENDING → APPROVED` (tras `returnPeriodDays = 14`) `→ PAID`.
- `AffiliateProgramService.requestPayout(userId)`: suma comisiones APPROVED, exige
  `minPayoutCents = 5000` (50 €, moneda EUR), crea `AffiliatePayoutEntity` con
  `method = "WALLET"`, estado `REQUESTED`; notifica al staff.
- `approvePayout(payoutId)`: `walletUseCase.adminTopup(...)` abona a la wallet del
  afiliado y marca las comisiones `PAID`. Idempotente (no paga dos veces).
- La wallet **no** tiene retiro externo; solo recarga (entrada) y gasto en pedidos.

## Modelo de datos (migración Liquibase `schema-v87` + master include)

> Siguiente versión libre = **v87** (la última es `schema-v86`).

### `affiliate` — perfil de cobro (columnas nuevas, todas nullable)
- `payout_method` VARCHAR — método preferido (`WALLET` | `BANK` | `PAYPAL`), default `WALLET`.
- `bank_holder` VARCHAR — titular de la cuenta.
- `bank_iban` VARCHAR — IBAN.
- `bank_bic` VARCHAR — BIC/SWIFT (opcional).
- `paypal_email` VARCHAR — email de PayPal.

### `affiliate_payout` — snapshot inmutable del destino + auditoría (columnas nuevas)
- `method` ya existe; ahora acepta `WALLET | BANK | PAYPAL`.
- `dest_holder`, `dest_iban`, `dest_bic`, `dest_paypal_email` VARCHAR — **snapshot** de
  los datos de cobro tomados en el momento de solicitar (inmutable aunque el afiliado
  edite luego su perfil). Para WALLET quedan NULL.
- `paid_reference` VARCHAR — referencia de la transferencia/pago PayPal que introduce
  el ADMIN al marcar PAID.
- `paid_by` UUID — id del ADMIN que aprobó (auditoría).

## Flujo / máquina de estados

Comisión `PENDING → APPROVED → PAID` **sin cambios**.

### Afiliado
- **Guardar datos de cobro** — `updatePayoutProfile(userId, bankHolder, iban, bic, paypalEmail, preferredMethod, password)`:
  - Reautentica con la contraseña del usuario (mismo verificador que login).
  - Valida IBAN (mod-97 + longitud) si se envía banco; valida formato de email si se
    envía PayPal. Persiste en `affiliate`.
- **Solicitar pago** — `requestPayout(userId, method)`:
  - `method ∈ {WALLET, BANK, PAYPAL}`.
  - Si `BANK`: exige `bank_holder` + `bank_iban` guardados; si `PAYPAL`: exige `paypal_email`.
    Si faltan → error de negocio "Configura tus datos de cobro".
  - No puede haber otra solicitud en estado `REQUESTED`.
  - Suma comisiones `APPROVED`; exige `≥ minPayoutCents` (50 €).
  - Crea `AffiliatePayoutEntity` con `method` + **snapshot** del destino, estado `REQUESTED`;
    notifica al staff.

### ADMIN
- **Aprobar** — `approvePayout(payoutId, adminUserId, reference)`:
  - `WALLET` → comportamiento actual: `adminTopup` a la wallet del afiliado.
  - `BANK | PAYPAL` → **no** toca la wallet; el ADMIN ya hizo el pago fuera. Marca el
    payout `PAID` con `paid_reference = reference`, `paid_by = adminUserId`, `processed_at`;
    las comisiones asociadas → `PAID` (con `payout_id`). `payoutUsdCents` acumulado += total.
  - Idempotente para `PAID`; error si `REJECTED`.
- **Rechazar** — `rejectPayout(payoutId, adminUserId, reason)`: estado `REJECTED` + nota.
  Las comisiones **nunca dejan de estar `APPROVED`** al solicitar (la solicitud solo suma;
  no bloquea comisiones), así que rechazar simplemente cancela la solicitud y el afiliado
  puede volver a pedir el pago. El importe pagado se recalcula sobre las `APPROVED` **en el
  momento de aprobar** (comportamiento actual), no sobre el total del instante de solicitud.

## API

### Storefront (afiliado autenticado)
- `GET  /api/affiliate/payout-profile` → datos de cobro guardados (IBAN parcialmente
  enmascarado en respuesta de lectura, p. ej. `****1234`).
- `PUT  /api/affiliate/payout-profile` → body `{ bankHolder, iban, bic, paypalEmail, preferredMethod, password }`.
- `POST /api/affiliate/payout` → body `{ method }` (extiende el `requestPayout` actual).
- El dashboard de afiliado ya lista los payouts; se añade `method` y estado.

### Admin (solo ADMIN, `/api/admin/affiliate`)
- `GET  /api/admin/affiliate/payouts?status=REQUESTED` → lista con destino a la vista
  (IBAN/BIC/titular o email PayPal) para ejecutarlo manualmente.
- `POST /api/admin/affiliate/payouts/{id}/approve` → body `{ reference }`.
- `POST /api/admin/affiliate/payouts/{id}/reject` → body `{ reason }`.

## Frontend

### Área de afiliado (extiende la página existente)
- Sección **"Datos de cobro"**: form con titular + IBAN + BIC (opcional) + email PayPal +
  método preferido; al guardar pide la **contraseña**. IBAN mostrado enmascarado al releer.
- **"Solicitar pago"**: saldo APPROVED, mínimo 50 €, selector de método (deshabilita
  banco/PayPal si no hay datos guardados), botón. Historial con método + estado.

### Panel admin de afiliados
- Tabla de payouts (filtro `REQUESTED`) mostrando afiliado, importe, método y **destino**
  (IBAN/BIC/titular o email PayPal). Acciones **Aprobar** (campo `referencia`) y **Rechazar**
  (campo `motivo`), con confirmación asíncrona (dialog, no `window.confirm`).

## Detalles transversales

- **Seguridad**: cambiar datos de cobro exige contraseña; el destino se **congela** en el
  payout al solicitar (inmutable); IBAN enmascarado en lecturas; solo ADMIN aprueba/rechaza.
- **Validación**: IBAN mod-97 + longitud; email PayPal; errores al usuario vía
  `ErrorMessages.humanize` y, si aplica constraint nueva, enum `ConstraintMessage`.
- **i18n**: todas las claves nuevas en los **8 idiomas** (`translations.ts`); `t()` devuelve
  la clave si falta, así que se añaden siempre.
- **Dinero**: comisiones/pagos en céntimos EUR; importes formateados en backend
  (`displayFormatted`), el front solo pinta. El pago externo **no** mueve wallet.
- **Convención Java**: imports (sin FQN inline), `var` no, enums para constantes de mensajes.

## Fuera de alcance (YAGNI)

- API automática de PayPal Payouts / transferencias (Stripe/Wise).
- Multi-divisa / conversión en el pago.
- Varias cuentas bancarias por afiliado.
- Formularios/retención fiscal.

## Tests

- `requestPayout`: por cada método; sin datos de cobro (BANK/PAYPAL); bajo mínimo;
  solicitud duplicada (REQUESTED existente).
- `approvePayout`: WALLET (abona wallet) vs BANK/PAYPAL (no toca wallet, marca PAID +
  referencia); idempotencia; rechazo devuelve comisiones a APPROVED.
- `updatePayoutProfile`: contraseña incorrecta rechaza; IBAN inválido rechaza.
