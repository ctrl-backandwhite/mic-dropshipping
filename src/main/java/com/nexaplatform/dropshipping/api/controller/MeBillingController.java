package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeBillingApi;
import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingConfigDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MySubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SetupIntentDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeStatusDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
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

    @Override
    public ResponseEntity<SubscribeStatusDtoOut> subscribe(Authentication auth, SubscribeDtoIn req) throws Exception {
        CustomerSubscriptionUseCase.SubscribeOutcome outcome = useCase
                .subscribeWithSavedCard(UUID.fromString(auth.getName()), req.getPlanCode(), req.getPeriod());
        return ResponseEntity.ok(SubscribeStatusDtoOut.builder().subscriptionId(outcome.subscriptionId())
                .status(outcome.status()).build());
    }

    @Override
    public ResponseEntity<MySubscriptionDtoOut> currentSubscription(Authentication auth) {
        CustomerSubscription s = useCase.currentSubscription(UUID.fromString(auth.getName()));
        if (s == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(MySubscriptionDtoOut.builder()
                .planId(s.getPlanId() != null ? s.getPlanId().toString() : null)
                .status(s.getStatus() != null ? s.getStatus().name() : null).billingPeriod(s.getBillingPeriod())
                .currentPeriodEnd(s.getCurrentPeriodEnd()).cancelAt(s.getCancelAt()).build());
    }

    @Override
    public ResponseEntity<Void> cancelSubscription(Authentication auth) throws Exception {
        useCase.cancelMySubscription(UUID.fromString(auth.getName()));
        return ResponseEntity.noContent().build();
    }
}
