package com.nexaplatform.dropshipping.infrastructure.integration.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeService {

    @Value("${nexadrop.stripe.enabled:false}")
    private boolean enabled;

    @Value("${nexadrop.stripe.secret-key:}")
    private String secretKey;

    /**
     * Identificador de plataforma que se adjunta como metadata a cada pago/suscripción. Permite filtrar
     * en el dashboard de Stripe los cobros originados en la plataforma de Dropshipping cuando varias
     * plataformas comparten la misma cuenta Stripe.
     */
    @Value("${nexadrop.stripe.platform-id:nexadrop-dropshipping}")
    private String platformId;

    @Value("${nexadrop.stripe.platform-env:dev}")
    private String platformEnv;

    @PostConstruct
    public void init() {
        if (enabled && secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe enabled (platform={}, env={})", platformId, platformEnv);
        } else {
            log.info("Stripe disabled (test mode); checkout will return mock URLs");
        }
    }

    public Session createCheckoutSession(String customerEmail, String stripePriceId, String successUrl,
            String cancelUrl) throws StripeException {
        SessionCreateParams params = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(successUrl + "?session_id={CHECKOUT_SESSION_ID}").setCancelUrl(cancelUrl)
                .setCustomerEmail(customerEmail)
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(stripePriceId).setQuantity(1L).build())
                // Identifica el origen del pago en Stripe (metadata en la sesión y, como mode=SUBSCRIPTION,
                // también en la suscripción resultante → la metadata viaja a sus facturas/cargos).
                .putMetadata("platform", platformId).putMetadata("platform_env", platformEnv)
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("platform", platformId).putMetadata("platform_env", platformEnv).build())
                .build();
        return Session.create(params);
    }

    public boolean isEnabled() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }
}
