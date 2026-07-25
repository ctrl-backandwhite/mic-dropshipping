# Cobro externo de afiliados (transferencia y PayPal) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un afiliado cobre sus comisiones APPROVED por wallet, transferencia bancaria o PayPal, con datos de cobro guardados en su perfil (editar exige contraseña) y ejecución manual del pago externo por un ADMIN.

**Architecture:** Se extiende el flujo existente `AffiliateProgramService` (requestPayout/approvePayout) y las entidades `AffiliateEntity`/`AffiliatePayoutEntity`. Los endpoints admin de payout ya existen (`/api/admin/affiliates/payouts/*`) y solo se amplían. El storefront usa `/api/me/affiliate`. Nada de API de pagos automática: el pago externo se marca PAID con una referencia que introduce el ADMIN.

**Tech Stack:** Spring Boot 4.1, Liquibase, JPA/Hibernate, MapStruct (no aquí), React + Vite + TS, i18n propio (`t()` en 8 idiomas).

## Global Constraints

- Céntimos EUR; importes formateados en backend (`displayFormatted`), el front solo pinta.
- Convención Java estricta: **imports** (sin FQN inline), **sin `var`**, constantes de mensajes en **enum** (no Map).
- Errores al usuario vía `ErrorMessages.humanize`; constraints nuevas → enum `ConstraintMessage`.
- i18n: toda clave nueva se añade en los **8 idiomas** de `frontend/src/i18n/translations.ts` (`t()` devuelve la clave si falta).
- Aprobación/rechazo de payouts: **solo ADMIN** (`/api/admin/**` ya es ROLE_ADMIN).
- Cambiar datos de cobro exige **contraseña** (verificar con `passwordEncoder.matches(raw, user.getPasswordHash())`).
- No hacer push a `features` salvo que el usuario lo pida; el merge a `develop` lo hace el usuario.
- Tras cambios de código, reconstruir imagen Docker local (`:3003`/`:18082` son builds prod).

---

### Task 1: Migración de esquema (columnas de cobro + snapshot)

**Files:**
- Create: `src/main/resources/db/changelog/schema-v87-affiliate-payout-methods.sql`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml` (o el master `.xml`/`.yaml` que incluya los `schema-vNN`)

**Interfaces:**
- Produces: columnas `affiliate.payout_method|bank_holder|bank_iban|bank_bic|paypal_email`; `affiliate_payout.dest_holder|dest_iban|dest_bic|dest_paypal_email|paid_reference|paid_by`.

- [ ] **Step 1: Crear el SQL de migración**

```sql
-- schema-v87: métodos de cobro del afiliado (wallet/banco/PayPal) + snapshot de destino en el payout
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS payout_method VARCHAR(20) NOT NULL DEFAULT 'WALLET';
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS bank_holder  VARCHAR(160);
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS bank_iban    VARCHAR(40);
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS bank_bic     VARCHAR(16);
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS paypal_email VARCHAR(200);

ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_holder       VARCHAR(160);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_iban         VARCHAR(40);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_bic          VARCHAR(16);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_paypal_email VARCHAR(200);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS paid_reference    VARCHAR(200);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS paid_by           UUID;
```

- [ ] **Step 2: Incluir la migración en el master changelog**

Buscar cómo se incluyen las `schema-vNN` (`grep -n "schema-v86" src/main/resources/db/changelog/*.yaml *.xml`) y añadir la entrada de `schema-v87-affiliate-payout-methods.sql` en el MISMO formato, justo detrás de v86.

- [ ] **Step 3: Arrancar y verificar que Liquibase aplica**

Run: `mvn -o -q package -DskipTests && (cd ../infra/docker && docker compose stop backend && docker compose up -d backend)`
Luego: `docker exec nexadrop-postgres psql -U nexadrop -d nexadrop -tAc "select column_name from information_schema.columns where table_name='affiliate' and column_name like '%bank%' or column_name='payout_method'"`
Expected: aparecen `payout_method`, `bank_holder`, `bank_iban`, `bank_bic`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/changelog/schema-v87-affiliate-payout-methods.sql src/main/resources/db/changelog/db.changelog-master.*
git commit -m "feat(afiliados): migración v87 — columnas de cobro (banco/PayPal) y snapshot de destino"
```

---

### Task 2: Campos en las entidades JPA

**Files:**
- Modify: `src/main/java/com/nexaplatform/dropshipping/infrastructure/persistence/entity/AffiliateEntity.java`
- Modify: `src/main/java/com/nexaplatform/dropshipping/infrastructure/persistence/entity/AffiliatePayoutEntity.java`

**Interfaces:**
- Produces: getters/setters Lombok `getPayoutMethod/getBankHolder/getBankIban/getBankBic/getPaypalEmail` en AffiliateEntity; `getDestHolder/…/getPaidReference/getPaidBy` en AffiliatePayoutEntity.

- [ ] **Step 1: Añadir campos a AffiliateEntity**

Tras `acceptedTermsAt`:
```java
    @Column(name = "payout_method", nullable = false, length = 20)
    private String payoutMethod = "WALLET";
    @Column(name = "bank_holder", length = 160)
    private String bankHolder;
    @Column(name = "bank_iban", length = 40)
    private String bankIban;
    @Column(name = "bank_bic", length = 16)
    private String bankBic;
    @Column(name = "paypal_email", length = 200)
    private String paypalEmail;
```

- [ ] **Step 2: Añadir campos a AffiliatePayoutEntity**

Tras `processedAt`:
```java
    @Column(name = "dest_holder", length = 160)
    private String destHolder;
    @Column(name = "dest_iban", length = 40)
    private String destIban;
    @Column(name = "dest_bic", length = 16)
    private String destBic;
    @Column(name = "dest_paypal_email", length = 200)
    private String destPaypalEmail;
    @Column(name = "paid_reference", length = 200)
    private String paidReference;
    @Column(name = "paid_by", columnDefinition = "uuid")
    private UUID paidBy;
```

- [ ] **Step 3: Compilar**

Run: `mvn -o -q compile`
Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/nexaplatform/dropshipping/infrastructure/persistence/entity/AffiliateEntity.java src/main/java/com/nexaplatform/dropshipping/infrastructure/persistence/entity/AffiliatePayoutEntity.java
git commit -m "feat(afiliados): campos JPA de cobro (banco/PayPal) y snapshot/auditoría de payout"
```

---

### Task 3: Validador de IBAN (mod-97)

**Files:**
- Create: `src/main/java/com/nexaplatform/dropshipping/application/service/IbanValidator.java`
- Test: `src/test/java/com/nexaplatform/dropshipping/application/service/IbanValidatorTest.java`

**Interfaces:**
- Produces: `static boolean IbanValidator.isValid(String iban)` — true si IBAN válido (formato + mod-97).

- [ ] **Step 1: Test que falla**

```java
package com.nexaplatform.dropshipping.application.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbanValidatorTest {
    @Test
    void aceptaIbanValido() {
        assertTrue(IbanValidator.isValid("ES9121000418450200051332"));
        assertTrue(IbanValidator.isValid("es91 2100 0418 4502 0005 1332"));
    }
    @Test
    void rechazaIbanInvalido() {
        assertFalse(IbanValidator.isValid("ES0021000418450200051332"));
        assertFalse(IbanValidator.isValid("XX"));
        assertFalse(IbanValidator.isValid(null));
        assertFalse(IbanValidator.isValid(""));
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla por clase inexistente)**

Run: `mvn -o -q -Dtest=IbanValidatorTest test`
Expected: FAIL (no compila / clase no existe).

- [ ] **Step 3: Implementar el validador**

```java
package com.nexaplatform.dropshipping.application.service;

import java.math.BigInteger;

/** Validación de IBAN: normaliza, comprueba longitud básica y el resto mod-97 (ISO 13616). */
public final class IbanValidator {

    private IbanValidator() {
    }

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        String iban = raw.replaceAll("\\s", "").toUpperCase();
        if (iban.length() < 15 || iban.length() > 34 || !iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+")) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            numeric.append(Character.isLetter(c) ? Integer.toString(c - 'A' + 10) : c);
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
```

- [ ] **Step 4: Ejecutar el test (pasa)**

Run: `mvn -o -q -Dtest=IbanValidatorTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nexaplatform/dropshipping/application/service/IbanValidator.java src/test/java/com/nexaplatform/dropshipping/application/service/IbanValidatorTest.java
git commit -m "feat(afiliados): validador de IBAN (mod-97)"
```

---

### Task 4: DTOs de cobro

**Files:**
- Modify: `src/main/java/com/nexaplatform/dropshipping/api/dto/AffiliateDtos.java`

**Interfaces:**
- Produces: records `PayoutProfileView`, `PayoutProfileUpdateRequest`, `PayoutRequest`, `PendingPayoutView`, `ApprovePayoutRequest`.

- [ ] **Step 1: Añadir los records dentro de `AffiliateDtos`**

```java
    /** Datos de cobro del afiliado (IBAN enmascarado en lectura). */
    public record PayoutProfileView(String payoutMethod, String bankHolder, String bankIbanMasked,
            String bankBic, String paypalEmail, boolean hasBank, boolean hasPaypal) {
    }

    /** Alta/edición de datos de cobro; requiere la contraseña del usuario. */
    public record PayoutProfileUpdateRequest(String bankHolder, String iban, String bic, String paypalEmail,
            String preferredMethod, String password) {
    }

    /** Solicitud de pago: método elegido (WALLET | BANK | PAYPAL). */
    public record PayoutRequest(String method) {
    }

    /** Fila de payout pendiente para el ADMIN, con el destino a la vista para ejecutarlo. */
    public record PendingPayoutView(UUID id, UUID affiliateId, String affiliateName, long amountCents,
            String amountFormatted, String currency, String method, String destHolder, String destIban,
            String destBic, String destPaypalEmail, int commissionCount, String requestedAt) {
    }

    /** Aprobación de payout externo: referencia de la transferencia/PayPal. */
    public record ApprovePayoutRequest(String reference) {
    }
```

- [ ] **Step 2: Compilar**

Run: `mvn -o -q compile`
Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/nexaplatform/dropshipping/api/dto/AffiliateDtos.java
git commit -m "feat(afiliados): DTOs de perfil de cobro, solicitud y payout pendiente"
```

---

### Task 5: Servicio — perfil de cobro (leer + guardar con contraseña)

**Files:**
- Modify: `src/main/java/com/nexaplatform/dropshipping/application/service/AffiliateProgramService.java`
- Test: `src/test/java/com/nexaplatform/dropshipping/application/service/AffiliatePayoutProfileTest.java`

**Interfaces:**
- Consumes: `PasswordEncoder`, `UserRepository.findById(UUID) → Optional<User>` con `User.getPasswordHash()`; `AffiliateJpaRepositoryAdapter.findByUser_Id(UUID)`.
- Produces:
  - `PayoutProfileView getPayoutProfile(UUID userId)`
  - `void updatePayoutProfile(UUID userId, PayoutProfileUpdateRequest req)`

- [ ] **Step 1: Inyectar `PasswordEncoder`**

Añadir el import `org.springframework.security.crypto.password.PasswordEncoder` y el campo:
```java
    private final PasswordEncoder passwordEncoder;
```
(La clase usa `@RequiredArgsConstructor`, así que basta el campo `final`.)

- [ ] **Step 2: Test que falla (contraseña incorrecta + IBAN inválido)**

```java
// Test de integración ligero con mocks de repos/encoder (seguir el estilo de los tests existentes del paquete).
// Verifica: password incorrecta -> BusinessException; IBAN inválido -> BusinessException; caso OK persiste.
```
(Escribir el test con Mockito replicando el estilo de los tests existentes en el paquete `application.service`; mockear `passwordEncoder.matches` a false para el caso KO y true para el OK, y `affiliateRepo.findByUser_Id` devolviendo un `AffiliateEntity`.)

- [ ] **Step 3: Ejecutar (falla)**

Run: `mvn -o -q -Dtest=AffiliatePayoutProfileTest test`
Expected: FAIL (métodos inexistentes).

- [ ] **Step 4: Implementar `getPayoutProfile` y `updatePayoutProfile`**

```java
    @Transactional(readOnly = true)
    public PayoutProfileView getPayoutProfile(UUID userId) {
        AffiliateEntity a = affiliateRepo.findByUser_Id(userId)
                .orElseThrow(() -> new NotFoundException("Affiliate not found"));
        return new PayoutProfileView(a.getPayoutMethod(), a.getBankHolder(), maskIban(a.getBankIban()),
                a.getBankBic(), a.getPaypalEmail(),
                a.getBankHolder() != null && a.getBankIban() != null,
                a.getPaypalEmail() != null && !a.getPaypalEmail().isBlank());
    }

    @Transactional
    public void updatePayoutProfile(UUID userId, PayoutProfileUpdateRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (req.password() == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BusinessException("INVALID_PASSWORD", "Contraseña incorrecta");
        }
        AffiliateEntity a = affiliateRepo.findByUser_Id(userId)
                .orElseThrow(() -> new NotFoundException("Affiliate not found"));
        String iban = req.iban() == null ? null : req.iban().replaceAll("\\s", "").toUpperCase();
        if (iban != null && !iban.isBlank() && !IbanValidator.isValid(iban)) {
            throw new BusinessException("INVALID_IBAN", "IBAN no válido");
        }
        String email = req.paypalEmail() == null ? null : req.paypalEmail().trim();
        if (email != null && !email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException("INVALID_EMAIL", "Email de PayPal no válido");
        }
        a.setBankHolder(emptyToNull(req.bankHolder()));
        a.setBankIban(emptyToNull(iban));
        a.setBankBic(emptyToNull(req.bic()));
        a.setPaypalEmail(emptyToNull(email));
        if (req.preferredMethod() != null && req.preferredMethod().matches("WALLET|BANK|PAYPAL")) {
            a.setPayoutMethod(req.preferredMethod());
        }
        affiliateRepo.save(a);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String maskIban(String iban) {
        if (iban == null || iban.length() < 4) {
            return iban;
        }
        return "****" + iban.substring(iban.length() - 4);
    }
```
(Asegurar imports: `User`, `NotFoundException`, `BusinessException`, `PayoutProfileView`, `PayoutProfileUpdateRequest` — todos por nombre simple + import.)

- [ ] **Step 5: Ejecutar (pasa)**

Run: `mvn -o -q -Dtest=AffiliatePayoutProfileTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/nexaplatform/dropshipping/application/service/AffiliateProgramService.java src/test/java/com/nexaplatform/dropshipping/application/service/AffiliatePayoutProfileTest.java
git commit -m "feat(afiliados): leer/guardar datos de cobro con verificación de contraseña e IBAN"
```

---

### Task 6: Servicio — `requestPayout(method)` con validación y snapshot

**Files:**
- Modify: `src/main/java/com/nexaplatform/dropshipping/application/service/AffiliateProgramService.java`
- Test: `src/test/java/com/nexaplatform/dropshipping/application/service/AffiliateRequestPayoutTest.java`

**Interfaces:**
- Consumes: `config().getMinPayoutCents()`, `commissionRepo.findByAffiliateIdAndStatus(id,"APPROVED")`, `payoutRepo.existsByAffiliateIdAndStatus(id,"REQUESTED")`.
- Produces: `AffiliatePayoutEntity requestPayout(UUID userId, String method)` (sobrecarga; el `requestPayout(UUID)` actual pasa a delegar con `"WALLET"`).

- [ ] **Step 1: Tests que fallan**

Casos: `method=BANK` sin IBAN → BusinessException `PAYOUT_DETAILS_MISSING`; `method=PAYPAL` sin email → misma; método inválido → BusinessException; bajo mínimo → BusinessException; con datos OK → crea payout con `method` y snapshot (`destIban`/`destPaypalEmail`).

- [ ] **Step 2: Ejecutar (falla)**

Run: `mvn -o -q -Dtest=AffiliateRequestPayoutTest test`
Expected: FAIL.

- [ ] **Step 3: Modificar `requestPayout`**

Reemplazar el `requestPayout(UUID userId)` actual por:
```java
    @Transactional
    public AffiliatePayoutEntity requestPayout(UUID userId) {
        return requestPayout(userId, "WALLET");
    }

    @Transactional
    public AffiliatePayoutEntity requestPayout(UUID userId, String method) {
        String m = method == null ? "WALLET" : method.toUpperCase();
        if (!m.matches("WALLET|BANK|PAYPAL")) {
            throw new BusinessException("INVALID_PAYOUT_METHOD", "Método de cobro no válido");
        }
        AffiliateEntity affiliate = affiliateRepo.findByUser_Id(userId)
                .orElseThrow(() -> new NotFoundException("Affiliate not found"));
        if ("BANK".equals(m) && (affiliate.getBankIban() == null || affiliate.getBankHolder() == null)) {
            throw new BusinessException("PAYOUT_DETAILS_MISSING", "Configura tus datos bancarios primero");
        }
        if ("PAYPAL".equals(m) && (affiliate.getPaypalEmail() == null || affiliate.getPaypalEmail().isBlank())) {
            throw new BusinessException("PAYOUT_DETAILS_MISSING", "Configura tu PayPal primero");
        }
        if (payoutRepo.existsByAffiliateIdAndStatus(affiliate.getId(), "REQUESTED")) {
            throw new BusinessException("Ya tienes una solicitud de pago pendiente");
        }
        List<AffiliateCommissionEntity> approved =
                commissionRepo.findByAffiliateIdAndStatus(affiliate.getId(), "APPROVED");
        long total = approved.stream().mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        AffiliateProgramConfigEntity cfg = config();
        if (total < cfg.getMinPayoutCents()) {
            throw new BusinessException("Saldo aprobado por debajo del pago mínimo");
        }
        AffiliatePayoutEntity payout = AffiliatePayoutEntity.builder().affiliateId(affiliate.getId())
                .amountCents(total).currency(cfg.getCurrency()).status("REQUESTED").method(m)
                .commissionCount(approved.size()).requestedAt(Instant.now()).note("Solicitud del afiliado")
                .destHolder("BANK".equals(m) ? affiliate.getBankHolder() : null)
                .destIban("BANK".equals(m) ? affiliate.getBankIban() : null)
                .destBic("BANK".equals(m) ? affiliate.getBankBic() : null)
                .destPaypalEmail("PAYPAL".equals(m) ? affiliate.getPaypalEmail() : null)
                .build();
        payout = payoutRepo.save(payout);
        notifyStaff("AFFILIATE_PAYOUT_REQUEST", "Solicitud de pago de afiliado",
                "Un afiliado ha solicitado el pago de sus comisiones aprobadas (" + m + ").");
        return payout;
    }
```

- [ ] **Step 4: Ejecutar (pasa)**

Run: `mvn -o -q -Dtest=AffiliateRequestPayoutTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(afiliados): requestPayout con método (wallet/banco/PayPal) + snapshot de destino"
```

---

### Task 7: Servicio — `approvePayout(reference, adminId)` externo vs wallet

**Files:**
- Modify: `src/main/java/com/nexaplatform/dropshipping/application/service/AffiliateProgramService.java`
- Test: `src/test/java/com/nexaplatform/dropshipping/application/service/AffiliateApprovePayoutTest.java`

**Interfaces:**
- Produces: `AffiliatePayoutEntity approvePayout(UUID payoutId, UUID adminUserId, String reference)`. Mantener `approvePayout(UUID payoutId)` delegando con `(id, null, null)` para no romper llamadas existentes.

- [ ] **Step 1: Tests que fallan**

Casos: método WALLET → llama `walletUseCase.adminTopup` (verificar con mock) y no exige referencia; método BANK con referencia → NO llama adminTopup, deja `status=PAID`, `paidReference`, `paidBy`, comisiones PAID; idempotente si ya PAID.

- [ ] **Step 2: Ejecutar (falla)**

Run: `mvn -o -q -Dtest=AffiliateApprovePayoutTest test`
Expected: FAIL.

- [ ] **Step 3: Modificar `approvePayout`**

```java
    @Transactional
    public AffiliatePayoutEntity approvePayout(UUID payoutId) {
        return approvePayout(payoutId, null, null);
    }

    @Transactional
    public AffiliatePayoutEntity approvePayout(UUID payoutId, UUID adminUserId, String reference) {
        AffiliatePayoutEntity payout = payoutRepo.findById(payoutId)
                .orElseThrow(() -> new NotFoundException("Payout not found"));
        if ("PAID".equals(payout.getStatus())) {
            return payout;
        }
        if ("REJECTED".equals(payout.getStatus())) {
            throw new BusinessException("El pago fue rechazado");
        }
        AffiliateEntity affiliate = affiliateRepo.findById(payout.getAffiliateId())
                .orElseThrow(() -> new NotFoundException("Affiliate not found"));
        List<AffiliateCommissionEntity> approved =
                commissionRepo.findByAffiliateIdAndStatus(affiliate.getId(), "APPROVED");
        long total = approved.stream().mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        if (total <= 0) {
            payout.setStatus("REJECTED");
            payout.setNote("Sin comisiones aprobadas que liquidar");
            return payoutRepo.save(payout);
        }
        UUID userId = affiliate.getUser().getId();
        UUID txId = null;
        if ("WALLET".equals(payout.getMethod())) {
            WalletTransaction tx = walletUseCase.adminTopup(userId, total, "Affiliate commission payout",
                    "affiliate-payout-" + payout.getId());
            txId = tx != null ? tx.getId() : null;
            payout.setWalletTxId(txId);
        } else {
            // Pago EXTERNO ya ejecutado por el ADMIN fuera de la app: solo se registra.
            payout.setPaidReference(reference);
            payout.setPaidBy(adminUserId);
        }
        Instant now = Instant.now();
        for (AffiliateCommissionEntity comm : approved) {
            comm.setStatus("PAID");
            comm.setPaidAt(now);
            comm.setWalletTxId(txId);
            comm.setPayoutId(payout.getId());
            commissionRepo.save(comm);
        }
        affiliate.setPayoutUsdCents(affiliate.getPayoutUsdCents() + total);
        affiliateRepo.save(affiliate);
        payout.setStatus("PAID");
        payout.setAmountCents(total);
        payout.setProcessedAt(now);
        payout.setCommissionCount(approved.size());
        return payoutRepo.save(payout);
    }
```
(Import `WalletTransaction` por nombre simple; ajustar al tipo real que devuelve `walletUseCase.adminTopup` — verificar su firma.)

- [ ] **Step 4: Ejecutar (pasa)**

Run: `mvn -o -q -Dtest=AffiliateApprovePayoutTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(afiliados): approvePayout externo (marca PAID + referencia, sin tocar wallet) vs wallet"
```

---

### Task 8: Endpoints storefront (`/api/me/affiliate`)

**Files:**
- Modify: `src/main/java/com/nexaplatform/dropshipping/api/controller/MeAffiliateController.java`

**Interfaces:**
- Consumes: `service.getPayoutProfile`, `service.updatePayoutProfile`, `service.requestPayout(userId, method)`.
- Produces: `GET /api/me/affiliate/payout-profile`, `PUT /api/me/affiliate/payout-profile`, y `POST /api/me/affiliate/payout-request` ahora acepta body `{method}`.

- [ ] **Step 1: Añadir/actualizar endpoints**

```java
    @GetMapping("/payout-profile")
    public ResponseEntity<PayoutProfileView> payoutProfile(Authentication auth) {
        return ResponseEntity.ok(service.getPayoutProfile(UUID.fromString(auth.getName())));
    }

    @PutMapping("/payout-profile")
    public ResponseEntity<PayoutProfileView> updatePayoutProfile(Authentication auth,
            @RequestBody PayoutProfileUpdateRequest req) {
        UUID userId = UUID.fromString(auth.getName());
        service.updatePayoutProfile(userId, req);
        return ResponseEntity.ok(service.getPayoutProfile(userId));
    }
```
Y modificar el `requestPayout` existente para leer el método:
```java
    @PostMapping("/payout-request")
    public ResponseEntity<Map<String, Object>> requestPayout(Authentication auth,
            @RequestBody(required = false) PayoutRequest req) {
        String method = req != null && req.method() != null ? req.method() : "WALLET";
        AffiliatePayoutEntity p = service.requestPayout(UUID.fromString(auth.getName()), method);
        return ResponseEntity.ok(Map.of("payoutId", p.getId(), "status", p.getStatus(), "method", p.getMethod()));
    }
```
(Imports por nombre simple.)

- [ ] **Step 2: Compilar**

Run: `mvn -o -q compile`
Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(afiliados): endpoints de perfil de cobro y solicitud con método en /api/me/affiliate"
```

---

### Task 9: Endpoints admin (referencia al aprobar + destino en pendientes)

**Files:**
- Modify: `src/main/java/com/nexaplatform/dropshipping/api/controller/AdminAffiliateController.java`

**Interfaces:**
- Produces: `GET /api/admin/affiliates/payouts/pending` devuelve `List<PendingPayoutView>` (con destino); `POST /api/admin/affiliates/payouts/{payoutId}/approve` acepta body `{reference}` y pasa el `adminUserId`.

- [ ] **Step 1: Enriquecer `pendingPayouts` con el destino**

Sustituir el retorno actual por un mapeo a `PendingPayoutView` (nombre del afiliado, importe formateado con el servicio de moneda ya usado en el panel, y campos `dest*`).

- [ ] **Step 2: `approvePayout` con referencia + admin**

```java
    @PostMapping("/payouts/{payoutId}/approve")
    public ResponseEntity<Map<String, Object>> approvePayout(Authentication auth, @PathVariable UUID payoutId,
            @RequestBody(required = false) ApprovePayoutRequest req) {
        UUID adminId = UUID.fromString(auth.getName());
        String reference = req != null ? req.reference() : null;
        AffiliatePayoutEntity p = service.approvePayout(payoutId, adminId, reference);
        return ResponseEntity.ok(Map.of("status", p.getStatus(), "reference",
                p.getPaidReference() != null ? p.getPaidReference() : ""));
    }
```

- [ ] **Step 3: Compilar**

Run: `mvn -o -q compile`
Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(afiliados): admin — referencia al aprobar payout y destino en pendientes"
```

---

### Task 10: Verificación e2e backend (local, con curl)

**Files:** ninguno (verificación).

- [ ] **Step 1: Rebuild + redeploy backend local**

Run: `mvn -o -q package -DskipTests && (cd ../infra/docker && docker compose stop backend && docker compose up -d backend)` y esperar `actuator/health` = 200.

- [ ] **Step 2: Probar el flujo con un afiliado de prueba**

Guardar perfil (PUT payout-profile con IBAN válido + password), solicitar pago BANK (POST payout-request `{method:"BANK"}`), listar pendientes como admin (ver destino), aprobar con referencia y comprobar `status=PAID` y `paid_reference` en BD; verificar que la wallet del afiliado **no** cambió. Repetir para PAYPAL. Probar caso KO: password incorrecta → error humanizado; IBAN inválido → error.

- [ ] **Step 3: Commit (si hubo ajustes)**

```bash
git add -A && git commit -m "test(afiliados): verificación e2e del cobro externo (banco/PayPal)"
```

---

### Task 11: Frontend — API client

**Files:**
- Modify: `frontend/src/api/affiliate.ts`

**Interfaces:**
- Produces: `affiliate.getPayoutProfile()`, `affiliate.updatePayoutProfile(body)`, `affiliate.requestPayout(method)`; tipos `PayoutProfile`, `PendingPayout`.

- [ ] **Step 1: Añadir tipos y funciones**

Seguir el patrón del cliente existente (axios `api`), añadiendo:
```ts
export interface PayoutProfile {
  payoutMethod: string; bankHolder?: string; bankIbanMasked?: string; bankBic?: string
  paypalEmail?: string; hasBank: boolean; hasPaypal: boolean
}
export const affiliate = {
  // …existentes…
  getPayoutProfile: () => api.get<PayoutProfile>('/me/affiliate/payout-profile').then(r => r.data),
  updatePayoutProfile: (b: { bankHolder?: string; iban?: string; bic?: string; paypalEmail?: string; preferredMethod?: string; password: string }) =>
    api.put<PayoutProfile>('/me/affiliate/payout-profile', b).then(r => r.data),
  requestPayout: (method: string) =>
    api.post('/me/affiliate/payout-request', { method }).then(r => r.data),
}
```

- [ ] **Step 2: Typecheck**

Run: `cd frontend && npm run typecheck`
Expected: 0 errores.

- [ ] **Step 3: Commit**

```bash
cd frontend && git add src/api/affiliate.ts && git commit -m "feat(afiliados): cliente API de perfil de cobro y solicitud con método"
```

---

### Task 12: Frontend — UI de afiliado (datos de cobro + solicitar pago)

**Files:**
- Modify: la página de afiliado del storefront (localizar con `grep -rl "me/affiliate" frontend/src/pages`).
- Modify: `frontend/src/i18n/translations.ts`

- [ ] **Step 1: Sección "Datos de cobro"**

Form con titular, IBAN, BIC (opcional), email PayPal, método preferido y un campo **contraseña**; al guardar, `affiliate.updatePayoutProfile(...)`. Mostrar IBAN enmascarado al releer. Manejar error humanizado (password/IBAN).

- [ ] **Step 2: "Solicitar pago" con método**

Selector de método (wallet/banco/PayPal) deshabilitando banco/PayPal si `!hasBank`/`!hasPaypal`; botón que llama `affiliate.requestPayout(method)`; refrescar historial.

- [ ] **Step 3: Claves i18n en los 8 idiomas**

Añadir `affiliate.payout.*` (título, campos, método, errores, éxito) en los 8 idiomas de `translations.ts`.

- [ ] **Step 4: Typecheck + rebuild imagen front + smoke test**

Run: `cd frontend && npm run typecheck` (0 errores) y `cd ../infra/docker && docker compose build frontend && docker compose up -d frontend`; abrir la página de afiliado y probar guardar datos + solicitar pago.

- [ ] **Step 5: Commit**

```bash
cd frontend && git add -A && git commit -m "feat(afiliados): UI de datos de cobro y solicitud de pago por método"
```

---

### Task 13: Frontend — Panel admin (aprobar con referencia / rechazar)

**Files:**
- Modify: la página admin de afiliados (localizar con `grep -rl "admin/affiliates" frontend/src/pages/admin`).
- Modify: `frontend/src/api/admin.ts` (si expone los payouts admin) y `frontend/src/i18n/translations.ts`.

- [ ] **Step 1: Tabla de payouts pendientes con destino**

Mostrar afiliado, importe, método y destino (IBAN/BIC/titular o email PayPal). Acción **Aprobar** con campo `referencia` (obligatorio para banco/PayPal) → `POST /admin/affiliates/payouts/{id}/approve {reference}`; acción **Rechazar** con `motivo`. Confirmación asíncrona (dialog, no `window.confirm`).

- [ ] **Step 2: Claves i18n (8 idiomas)** para las etiquetas nuevas del panel.

- [ ] **Step 3: Typecheck + rebuild front + smoke test admin**

- [ ] **Step 4: Commit**

```bash
cd frontend && git add -A && git commit -m "feat(afiliados): panel admin — aprobar payout con referencia y rechazar"
```

---

## Self-Review

- **Cobertura del spec:** modelo de datos → T1/T2; validación IBAN/contraseña → T3/T5; requestPayout con método + snapshot → T6; approvePayout externo → T7; API storefront → T8; API admin (solo ADMIN, ya lo es) → T9; frontend afiliado → T11/T12; frontend admin → T13; i18n 8 idiomas → T12/T13; tests → T3/T5/T6/T7/T10. Cubierto.
- **Placeholders:** los tests de T5/T6/T7 se describen por casos (no código literal completo) porque dependen del estilo de mocks del paquete; el implementador debe replicar el patrón de tests existentes — es el único punto con margen, intencional.
- **Consistencia de tipos:** `requestPayout(UUID,String)`, `approvePayout(UUID,UUID,String)`, `getPayoutProfile→PayoutProfileView`, `updatePayoutProfile(UUID,PayoutProfileUpdateRequest)`, records en `AffiliateDtos`. Coinciden entre tareas.

## Notas
- Confirmar la firma real de `walletUseCase.adminTopup(...)` (tipo de retorno) antes de T7; ajustar `WalletTransaction` al tipo correcto.
- No se toca la wallet en pagos externos: el "ledger" del pago externo es el propio `affiliate_payout` (PAID + referencia).
