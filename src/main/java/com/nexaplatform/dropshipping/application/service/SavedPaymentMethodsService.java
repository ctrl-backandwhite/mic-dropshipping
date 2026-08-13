package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
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
import java.util.List;
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

    /** Lista todos los métodos guardados del usuario (tarjetas + PayPal) con el predeterminado marcado. */
    @Transactional(readOnly = true, noRollbackFor = StripeException.class)
    public List<PaymentMethodDtoOut> list(UUID userId) throws StripeException {
        List<PaymentMethodDtoOut> out = new ArrayList<>();
        // Tarjetas de Stripe.
        UserEntity user = loadUser(userId);
        String customerId = user.getStripeCustomerId();
        if (stripeService.isEnabled() && customerId != null && !customerId.isBlank()) {
            for (PaymentMethod pm : stripeService.listCards(customerId)) {
                PaymentMethod.Card c = pm.getCard();
                out.add(PaymentMethodDtoOut.builder().id(pm.getId()).type("CARD")
                        .brand(c != null ? c.getBrand() : null).last4(c != null ? c.getLast4() : null)
                        .expMonth(c != null ? c.getExpMonth() : null).expYear(c != null ? c.getExpYear() : null)
                        .build());
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
    public void delete(UUID userId, String ref) throws StripeException {
        assertOwned(userId, ref);
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
                .orElseGet(() -> methods.size() == 1 ? methods.get(0).getId() : null);
        if (def != null) {
            methods.forEach(m -> m.setDefault(def.equals(m.getId())));
        }
    }

    private String soleMethodRef(UUID userId) {
        try {
            List<PaymentMethodDtoOut> all = list(userId);
            return all.size() == 1 ? all.get(0).getId() : null;
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
        boolean owned = customerId != null && stripeService.listCards(customerId).stream()
                .anyMatch(pm -> pm.getId().equals(ref));
        if (!owned) {
            throw new NotFoundException("Método de pago no encontrado");
        }
    }

    private void saveDefault(UUID userId, String ref) {
        defaultRepository.save(UserDefaultPaymentEntity.builder().userId(userId).ref(ref)
                .updatedAt(Instant.now()).build());
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
