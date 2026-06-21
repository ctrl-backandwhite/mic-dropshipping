package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeBillingApi;
import com.nexaplatform.dropshipping.api.dto.out.BillingConfigDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SetupIntentDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Billing del usuario autenticado: config de Stripe + gestión de tarjetas guardadas (Stripe Elements).
 * Implementación pura de {@link MeBillingApi}: resuelve el usuario del principal, delega en el caso de
 * uso y mapea los records a DtoOut. Sin lógica de negocio.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeBillingController implements MeBillingApi {

    private final CustomerSubscriptionUseCase useCase;

    @Override
    public ResponseEntity<BillingConfigDtoOut> billingConfig() {
        CustomerSubscriptionUseCase.BillingConfigInfo c = useCase.billingConfig();
        return ResponseEntity.ok(BillingConfigDtoOut.builder()
                .publishableKey(c.publishableKey()).enabled(c.enabled()).build());
    }

    @Override
    public ResponseEntity<SetupIntentDtoOut> createSetupIntent(Authentication auth) throws Exception {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(SetupIntentDtoOut.builder()
                .clientSecret(useCase.createSetupIntentSecret(userId)).build());
    }

    @Override
    public ResponseEntity<List<PaymentMethodDtoOut>> listPaymentMethods(Authentication auth) throws Exception {
        UUID userId = UUID.fromString(auth.getName());
        List<PaymentMethodDtoOut> out = useCase.listCards(userId).stream()
                .map(c -> PaymentMethodDtoOut.builder().id(c.id()).brand(c.brand()).last4(c.last4())
                        .expMonth(c.expMonth()).expYear(c.expYear()).isDefault(c.isDefault()).build())
                .toList();
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<Void> setDefault(Authentication auth, String id) throws Exception {
        useCase.setDefaultCard(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> delete(Authentication auth, String id) throws Exception {
        useCase.deleteCard(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }
}
