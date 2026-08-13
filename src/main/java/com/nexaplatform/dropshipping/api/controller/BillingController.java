package com.nexaplatform.dropshipping.api.controller;

import com.stripe.exception.StripeException;
import com.nexaplatform.dropshipping.api.BillingApi;
import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeDtoOut;
import com.nexaplatform.dropshipping.api.mapper.BillingDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Storefront billing controller. Pure implementation of {@link BillingApi}:
 * injects the {@link BillingDtoMapper} + {@link CustomerSubscriptionUseCase};
 * maps the use-case results to DtoOut; no business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController implements BillingApi {

    private final BillingDtoMapper mapper;
    private final CustomerSubscriptionUseCase useCase;
    private final CurrencyRateService currencyService;

    @Override
    public ResponseEntity<List<BillingPlanDtoOut>> listPlans() {
        List<BillingPlanDtoOut> plans = mapper.toPlanDtoOutList(useCase.listPublicPlans());
        // Precio del plan en CNY (moneda de 1688) → moneda de display del usuario (X-Currency), igual que
        // los productos. El backend deja el importe formateado listo; el front solo lo pinta.
        String displayCode = CurrencyHolder.get();
        boolean eur = "EUR".equalsIgnoreCase(displayCode);
        for (BillingPlanDtoOut p : plans) {
            String src = p.getCurrency() != null && !p.getCurrency().isBlank() ? p.getCurrency() : "CNY";
            BigDecimal monthly;
            BigDecimal yearly;
            if (eur && p.getPriceMonthlyEurCents() > 0) {
                // UE: ancla FIJA en EUR (p. ej. 50 €), no una conversión del importe USD.
                monthly = BigDecimal.valueOf(p.getPriceMonthlyEurCents()).movePointLeft(2);
                yearly = BigDecimal.valueOf(p.getPriceYearlyEurCents()).movePointLeft(2);
            } else {
                // Resto del mundo: ancla en USD; en otras divisas se convierte con la tasa del día.
                monthly = currencyService.usdToDisplay(
                        currencyService.toUsd(BigDecimal.valueOf(p.getPriceMonthlyCents()).movePointLeft(2), src));
                yearly = currencyService.usdToDisplay(
                        currencyService.toUsd(BigDecimal.valueOf(p.getPriceYearlyCents()).movePointLeft(2), src));
            }
            // Precio de plan REDONDEADO a entero (HALF_UP) — se muestra "25 €", no "25,28 €".
            p.setDisplayCurrency(displayCode);
            p.setDisplayMonthly(monthly.setScale(0, RoundingMode.HALF_UP));
            p.setDisplayYearly(yearly.setScale(0, RoundingMode.HALF_UP));
            p.setDisplayMonthlyFormatted(currencyService.formatDisplayRounded(monthly, displayCode));
            p.setDisplayYearlyFormatted(currencyService.formatDisplayRounded(yearly, displayCode));
        }
        return ResponseEntity.ok(plans);
    }

    @Override
    public ResponseEntity<SubscribeDtoOut> subscribe(UserDetails principal, SubscribeDtoIn req) throws StripeException {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity
                .ok(mapper.toSubscribeDtoOut(useCase.subscribe(userId, req.getPlanCode(), req.getPeriod())));
    }
}
