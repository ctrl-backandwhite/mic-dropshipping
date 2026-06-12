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

    @PostConstruct
    public void init() {
        if (enabled && secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe enabled");
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
                .build();
        return Session.create(params);
    }

    public boolean isEnabled() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }
}
