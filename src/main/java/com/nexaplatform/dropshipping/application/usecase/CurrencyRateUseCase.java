package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.CurrencyRate;
import com.nexaplatform.dropshipping.domain.model.CurrencySyncResult;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Use-case port for the CRUD/sync side of currency rates; operates on the
 * {@link CurrencyRate} domain model. The conversion side stays in
 * {@code CurrencyRateService} (shared cache, hot path of price conversion).
 */
public interface CurrencyRateUseCase extends BaseUseCase<CurrencyRate, CurrencyRate, UUID> {

    /** Lists active currency rates ordered by code. */
    java.util.List<CurrencyRate> listActive();

    /** Lists ALL currencies (active and inactive) ordered by code — for the admin screen. */
    java.util.List<CurrencyRate> listAll();

    /** Toggles the active flag of a currency without touching its rate. */
    CurrencyRate setActive(String code, boolean active);

    /** Activates/deactivates several currencies at once; returns how many changed. */
    int bulkSetActive(java.util.List<String> codes, boolean active);

    /** Gets a single currency by its ISO code (throws if unknown). */
    CurrencyRate one(String code);

    /** Overrides a rate and optionally toggles its active flag. */
    CurrencyRate updateRate(String code, BigDecimal rateVsUsd, Boolean active);

    /** Syncs rates from the external provider and reports the outcome. */
    CurrencySyncResult sync();
}
