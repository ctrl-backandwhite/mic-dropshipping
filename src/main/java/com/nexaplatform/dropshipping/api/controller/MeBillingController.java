package com.nexaplatform.dropshipping.api.controller;

import com.stripe.exception.StripeException;
import com.nexaplatform.dropshipping.api.MeBillingApi;
import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingConfigDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.BillingInvoiceDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MySubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SetupIntentDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeStatusDtoOut;
import com.nexaplatform.dropshipping.api.dto.in.SavePayPalDtoIn;
import com.nexaplatform.dropshipping.api.mapper.BillingInvoiceDtoMapper;
import com.nexaplatform.dropshipping.application.service.SavedPaymentMethodsService;
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
    private final SavedPaymentMethodsService savedMethods;
    private final BillingInvoiceDtoMapper invoiceMapper;

    @Override
    public ResponseEntity<BillingConfigDtoOut> billingConfig(Authentication auth) {
        CustomerSubscriptionUseCase.BillingConfigInfo c = useCase.billingConfig(UUID.fromString(auth.getName()));
        return ResponseEntity.ok(BillingConfigDtoOut.builder()
                .publishableKey(c.publishableKey()).enabled(c.enabled()).freeTrialUsed(c.freeTrialUsed()).build());
    }

    @Override
    public ResponseEntity<SetupIntentDtoOut> createSetupIntent(Authentication auth) throws StripeException {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(SetupIntentDtoOut.builder()
                .clientSecret(useCase.createSetupIntentSecret(userId)).build());
    }

    @Override
    public ResponseEntity<List<PaymentMethodDtoOut>> listPaymentMethods(Authentication auth) throws StripeException {
        // Lista UNIFICADA: tarjetas (Stripe) + cuentas PayPal (locales), con el predeterminado marcado.
        return ResponseEntity.ok(savedMethods.list(UUID.fromString(auth.getName())));
    }

    @Override
    public ResponseEntity<Void> savePayPal(Authentication auth, SavePayPalDtoIn req) {
        savedMethods.addPayPal(UUID.fromString(auth.getName()), req.getEmail());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> setDefault(Authentication auth, String id) throws StripeException {
        // 'id' es la referencia unificada: pm_... (tarjeta) o 'paypal:<uuid>'.
        savedMethods.setDefault(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> requestDeleteCode(Authentication auth, String id) throws StripeException {
        savedMethods.requestDelete(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> delete(Authentication auth, String id, String code) throws StripeException {
        savedMethods.delete(UUID.fromString(auth.getName()), id, code);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SubscribeStatusDtoOut> subscribe(Authentication auth, SubscribeDtoIn req) throws StripeException {
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
                .currentPeriodEnd(s.getCurrentPeriodEnd()).cancelAt(s.getCancelAt())
                .pendingPlanCode(s.getPendingPlanCode()).pendingPlanAt(s.getPendingPlanAt()).build());
    }

    @Override
    public ResponseEntity<Void> cancelSubscription(Authentication auth) throws StripeException {
        useCase.cancelMySubscription(UUID.fromString(auth.getName()));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<BillingInvoiceDtoOut>> invoices(Authentication auth) throws StripeException {
        // El importe formateado lo pone el mapper con el servicio central de divisas: el navegador ya no
        // hace cuentas con el total, que en las divisas sin céntimos venía cien veces mal.
        return ResponseEntity.ok(invoiceMapper.toDtoOutList(useCase.listInvoices(UUID.fromString(auth.getName()))));
    }
}
