package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethodEmailLabel;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PayPalPaymentMethodEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserDefaultPaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PayPalPaymentMethodRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserDefaultPaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Métodos de pago guardados UNIFICADOS: tarjetas (Stripe) + cuentas PayPal (tabla local, correo cifrado),
 * con un "predeterminado" único ({@code user_default_payment}). Reglas:
 * <ul>
 *   <li>El correo de PayPal se guarda CIFRADO (AES-256-GCM); nunca se devuelve en claro (solo enmascarado).</li>
 *   <li>Predeterminado: el que apunte {@code user_default_payment}; si no hay puntero pero el usuario tiene
 *       UN SOLO método, ese es el predeterminado de facto.</li>
 *   <li>La referencia unificada de un método es el {@code pm_...} de Stripe (tarjeta) o {@code paypal:<id>}.</li>
 * </ul>
 * PayPal aquí es para el CHECKOUT (one-off); los planes recurrentes se cobran solo con tarjeta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedPaymentMethodsService {

    public static final String PAYPAL_PREFIX = "paypal:";

    private final StripeService stripeService;
    private final PayPalPaymentMethodRepository paypalRepository;
    private final UserDefaultPaymentRepository defaultRepository;
    private final UserRepository userRepository;
    private final TokenCryptoService crypto;
    private final com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService emailQueue;
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    /** Lista todos los métodos guardados del usuario (tarjetas + PayPal) con el predeterminado marcado. */
    @Transactional(readOnly = true, noRollbackFor = StripeException.class)
    public List<PaymentMethodDtoOut> list(UUID userId) throws StripeException {
        List<PaymentMethodDtoOut> out = new ArrayList<>();
        // Tarjetas de Stripe.
        UserEntity user = loadUser(userId);
        String customerId = user.getStripeCustomerId();
        if (stripeService.isEnabled() && customerId != null && !customerId.isBlank()) {
            // Unicidad de tarjeta: Stripe permite adjuntar la MISMA tarjeta varias veces (pm_ distintos, mismo
            // fingerprint). Se conserva una sola por fingerprint (la más reciente va primero) y las duplicadas
            // se desvinculan para no dejar métodos repetidos.
            java.util.Set<String> seenFingerprints = new java.util.HashSet<>();
            for (PaymentMethod pm : stripeService.listCards(customerId)) {
                PaymentMethod.Card c = pm.getCard();
                String fingerprint = c != null ? c.getFingerprint() : null;
                if (fingerprint != null && !seenFingerprints.add(fingerprint)) {
                    try {
                        stripeService.detachPaymentMethod(pm.getId());
                    } catch (StripeException e) {
                        log.warn("::> [BILLING] no se pudo desvincular tarjeta duplicada {}: {}", pm.getId(),
                                e.getMessage());
                    }
                    continue;
                }
                out.add(PaymentMethodDtoOut.builder().id(pm.getId()).type("CARD").brand(c != null ? c.getBrand() : null)
                        .last4(c != null ? c.getLast4() : null).expMonth(c != null ? c.getExpMonth() : null)
                        .expYear(c != null ? c.getExpYear() : null).build());
            }
        }
        // Cuentas PayPal (correo descifrado solo para enmascarar).
        for (PayPalPaymentMethodEntity pp : paypalRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            out.add(PaymentMethodDtoOut.builder().id(PAYPAL_PREFIX + pp.getId()).type("PAYPAL")
                    .paypalEmail(mask(safeDecrypt(pp.getPaypalEmailEnc()))).build());
        }
        markDefault(userId, out);
        return out;
    }

    /** Guarda una cuenta PayPal (correo CIFRADO). Si queda como único método, pasa a ser el predeterminado. */
    @Transactional
    public void addPayPal(UUID userId, String email) {
        loadUser(userId);
        if (email == null || email.isBlank()) {
            throw new BusinessException("Email de PayPal requerido");
        }
        // Unicidad por usuario: no se puede guardar la MISMA cuenta de PayPal dos veces (correo cifrado con
        // GCM no determinista → se compara descifrando los que ya tiene).
        String normalized = email.trim().toLowerCase();
        boolean already = paypalRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .anyMatch(pp -> normalized.equalsIgnoreCase(safeDecrypt(pp.getPaypalEmailEnc())));
        if (already) {
            throw new BusinessException("PAYPAL_ALREADY_SAVED", "Esa cuenta de PayPal ya está guardada.");
        }
        PayPalPaymentMethodEntity row = PayPalPaymentMethodEntity.builder().id(UUID.randomUUID()).userId(userId)
                .paypalEmailEnc(crypto.encrypt(email.trim())).createdAt(Instant.now()).build();
        paypalRepository.save(row);
        // Si es el primer/único método del usuario, se marca como predeterminado automáticamente.
        if (defaultRepository.findById(userId).isEmpty() && totalMethods(userId) == 1) {
            saveDefault(userId, PAYPAL_PREFIX + row.getId());
        }
        log.info("::> [BILLING] PayPal guardado user={} id={}", userId, row.getId());
    }

    /** Fija el método predeterminado (tarjeta o PayPal). Si es tarjeta, también lo fija en Stripe. */
    @Transactional(noRollbackFor = StripeException.class)
    public void setDefault(UUID userId, String ref) throws StripeException {
        assertOwned(userId, ref);
        if (!isPayPal(ref)) {
            // Tarjeta: fijar también el default de Stripe (lo usan las renovaciones de suscripción).
            String customerId = loadUser(userId).getStripeCustomerId();
            if (customerId != null) {
                stripeService.setDefaultPaymentMethod(customerId, ref);
            }
        }
        saveDefault(userId, ref);
    }

    /** Borra un método guardado. Si era el predeterminado, se limpia el puntero (queda el de facto). */
    @Transactional(noRollbackFor = StripeException.class)
    /**
     * Paso 1 de la eliminación: genera un código de 6 dígitos (caduca en 15 min), lo guarda junto a la
     * referencia del método y lo envía por correo en el idioma del usuario. No borra nada todavía.
     */
    public void requestDelete(UUID userId, String ref) throws StripeException {
        assertOwned(userId, ref);
        UserEntity user = loadUser(userId);
        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        user.setPmDeleteCode(code);
        user.setPmDeleteRef(ref);
        user.setPmDeleteCodeAt(Instant.now());
        userRepository.save(user);
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            String lang = user.getLanguage();
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", PaymentMethodEmailLabel.TITLE.of(lang));
            vars.put("preheader", PaymentMethodEmailLabel.TITLE.of(lang));
            vars.put("bodyHtml", PaymentMethodEmailLabel.BODY.of(lang).replace("{code}", code));
            vars.put("footer", "NX036");
            emailQueue.enqueue(user.getEmail(), PaymentMethodEmailLabel.SUBJECT.of(lang), "emails/notification", vars);
        }
        log.info("::> [BILLING] Código de borrado de método enviado user={} ref={}", userId, ref);
    }

    /**
     * Paso 2: elimina el método SOLO si el código coincide con el enviado, no ha caducado (15 min) y es para
     * esa misma referencia. Limpia el código tras usarlo.
     */
    public void delete(UUID userId, String ref, String code) throws StripeException {
        assertOwned(userId, ref);
        UserEntity user = loadUser(userId);
        boolean refOk = ref.equals(user.getPmDeleteRef());
        boolean fresh = user.getPmDeleteCodeAt() != null
                && user.getPmDeleteCodeAt().isAfter(Instant.now().minusSeconds(900));
        // Comparación en tiempo CONSTANTE del código (evita timing side-channels).
        boolean codeOk = code != null && !code.isBlank() && user.getPmDeleteCode() != null
                && java.security.MessageDigest.isEqual(code.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        user.getPmDeleteCode().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!(refOk && fresh && codeOk)) {
            // Un intento con el código ERRÓNEO (con ref y ventana válidas) QUEMA el código: sin esto, el
            // código de 6 dígitos se podía forzar por fuerza bruta durante los 15 min de validez. Hay que
            // pedir uno nuevo. Un ref distinto o caducado no consume el código vigente.
            if (refOk && fresh && !codeOk) {
                user.setPmDeleteCode(null);
                user.setPmDeleteRef(null);
                user.setPmDeleteCodeAt(null);
                userRepository.save(user);
            }
            throw new BusinessException("PM_DELETE_CODE_INVALID", "El código no es válido o ha caducado.");
        }
        if (isPayPal(ref)) {
            paypalRepository.deleteById(paypalId(ref));
        } else {
            stripeService.detachPaymentMethod(ref);
        }
        defaultRepository.findById(userId).ifPresent(d -> {
            if (d.getRef().equals(ref)) {
                defaultRepository.deleteById(userId);
            }
        });
        user.setPmDeleteCode(null);
        user.setPmDeleteRef(null);
        user.setPmDeleteCodeAt(null);
        userRepository.save(user);
        log.info("::> [BILLING] Método borrado user={} ref={}", userId, ref);
    }

    /** Referencia del método predeterminado del usuario (puntero explícito o el único método), o null. */
    @Transactional(readOnly = true, noRollbackFor = StripeException.class)
    public String defaultRef(UUID userId) throws StripeException {
        return defaultRepository.findById(userId).map(UserDefaultPaymentEntity::getRef)
                .orElseGet(() -> soleMethodRef(userId));
    }

    /* ================= internos ================= */

    private void markDefault(UUID userId, List<PaymentMethodDtoOut> methods) {
        String def = defaultRepository.findById(userId).map(UserDefaultPaymentEntity::getRef)
                .orElseGet(() -> implicitDefault(methods));
        if (def != null) {
            methods.forEach(m -> m.setDefault(def.equals(m.getId())));
        }
    }

    /**
     * Predeterminado DE FACTO cuando el usuario no ha fijado uno a mano: la TARJETA (aunque también haya una
     * cuenta PayPal). Solo cambia si el usuario elige otro método manualmente. Si no hay tarjeta, el primero.
     */
    private static String implicitDefault(List<PaymentMethodDtoOut> methods) {
        return methods.stream().filter(m -> "CARD".equalsIgnoreCase(m.getType())).map(PaymentMethodDtoOut::getId)
                .findFirst().orElseGet(() -> methods.isEmpty() ? null : methods.get(0).getId());
    }

    private String soleMethodRef(UUID userId) {
        try {
            // Sin default explícito, el de facto para cobrar es la tarjeta (misma regla que markDefault).
            return implicitDefault(list(userId));
        } catch (StripeException e) {
            return null;
        }
    }

    private void assertOwned(UUID userId, String ref) throws StripeException {
        if (isPayPal(ref)) {
            PayPalPaymentMethodEntity pp = paypalRepository.findById(paypalId(ref)).orElse(null);
            if (pp == null || !userId.equals(pp.getUserId())) {
                throw new NotFoundException("Método de pago no encontrado");
            }
            return;
        }
        String customerId = loadUser(userId).getStripeCustomerId();
        boolean owned = customerId != null
                && stripeService.listCards(customerId).stream().anyMatch(pm -> pm.getId().equals(ref));
        if (!owned) {
            throw new NotFoundException("Método de pago no encontrado");
        }
    }

    private void saveDefault(UUID userId, String ref) {
        defaultRepository
                .save(UserDefaultPaymentEntity.builder().userId(userId).ref(ref).updatedAt(Instant.now()).build());
    }

    private long totalMethods(UUID userId) {
        long paypal = paypalRepository.countByUserId(userId);
        long cards = 0;
        try {
            String customerId = loadUser(userId).getStripeCustomerId();
            if (stripeService.isEnabled() && customerId != null && !customerId.isBlank()) {
                cards = stripeService.listCards(customerId).size();
            }
        } catch (StripeException e) {
            log.debug("No se pudieron contar las tarjetas de Stripe: {}", e.getMessage());
        }
        return paypal + cards;
    }

    private static boolean isPayPal(String ref) {
        return ref != null && ref.startsWith(PAYPAL_PREFIX);
    }

    private static UUID paypalId(String ref) {
        return UUID.fromString(ref.substring(PAYPAL_PREFIX.length()));
    }

    private String safeDecrypt(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return crypto.decrypt(enc);
        } catch (RuntimeException e) {
            log.warn("No se pudo descifrar el correo de PayPal: {}", e.getMessage());
            return null;
        }
    }

    /** Enmascara el correo: {@code john.doe@example.com} → {@code j***@example.com}. */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String head = local.substring(0, 1);
        return head + "***" + domain;
    }

    private UserEntity loadUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }
}
