package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads currency rates from the DB with a small in-memory cache (5 min TTL) to keep the
 * hot path of price conversion zero-IO. All amounts in the system are persisted in USD;
 * this service converts to the user-selected currency at serve time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyRateService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final CurrencyRateRepository repository;

    private volatile Instant cacheStamp = Instant.EPOCH;
    private final Map<String, CurrencyRateEntity> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void warm() {
        refreshCache();
    }

    /* ============ Reads ============ */

    public List<CurrencyRateEntity> listActive() {
        ensureFresh();
        return cache.values().stream()
                .filter(CurrencyRateEntity::isActive)
                .sorted((a, b) -> a.getCode().compareTo(b.getCode()))
                .toList();
    }

    public Optional<CurrencyRateEntity> find(String code) {
        if (code == null) return Optional.empty();
        ensureFresh();
        return Optional.ofNullable(cache.get(code.toUpperCase(Locale.ROOT)));
    }

    public CurrencyRateEntity require(String code) {
        return find(code).orElseThrow(() -> new NotFoundException("Currency not found: " + code));
    }

    /* ============ Conversion ============ */

    /** Convert an amount in USD to the active display currency (from CurrencyHolder). */
    public BigDecimal usdToDisplay(BigDecimal amountUsd) {
        return usdTo(amountUsd, CurrencyHolder.get());
    }

    public BigDecimal usdTo(BigDecimal amountUsd, String targetCode) {
        if (amountUsd == null) return null;
        if ("USD".equalsIgnoreCase(targetCode)) {
            return amountUsd.setScale(2, RoundingMode.HALF_UP);
        }
        return find(targetCode)
                .map(r -> amountUsd.multiply(r.getRateVsUsd()).setScale(2, RoundingMode.HALF_UP))
                .orElse(amountUsd.setScale(2, RoundingMode.HALF_UP));
    }

    /** Convert an amount in any source currency to USD (used at order creation to fix USD canonical). */
    public BigDecimal toUsd(BigDecimal amount, String sourceCode) {
        if (amount == null) return null;
        if ("USD".equalsIgnoreCase(sourceCode)) return amount.setScale(4, RoundingMode.HALF_UP);
        return find(sourceCode)
                .map(r -> amount.divide(r.getRateVsUsd(), 4, RoundingMode.HALF_UP))
                .orElseThrow(() -> new NotFoundException("Unknown source currency: " + sourceCode));
    }

    public String symbolOf(String code) {
        return find(code).map(CurrencyRateEntity::getSymbol).orElse("$");
    }

    public String localeOf(String code) {
        return find(code).map(CurrencyRateEntity::getLocale).orElse("en-US");
    }

    /* ============ Admin ============ */

    @Transactional
    public CurrencyRateEntity setActive(String code, boolean active) {
        CurrencyRateEntity r = repository.findByCodeIgnoreCase(code).orElseThrow(() -> new NotFoundException(code));
        r.setActive(active);
        repository.save(r);
        refreshCache();
        return r;
    }

    @Transactional
    public CurrencyRateEntity overrideRate(String code, BigDecimal newRate) {
        CurrencyRateEntity r = repository.findByCodeIgnoreCase(code).orElseThrow(() -> new NotFoundException(code));
        r.setRateVsUsd(newRate);
        r.setLastSyncedAt(Instant.now());
        repository.save(r);
        refreshCache();
        return r;
    }

    @Transactional
    public void applyBulkSync(Map<String, BigDecimal> ratesFromProvider) {
        if (ratesFromProvider == null || ratesFromProvider.isEmpty()) {
            log.warn("Currency sync: empty payload, keeping current cache");
            return;
        }
        Instant now = Instant.now();
        int updated = 0;
        for (var entry : ratesFromProvider.entrySet()) {
            var existing = repository.findByCodeIgnoreCase(entry.getKey()).orElse(null);
            if (existing == null) continue;
            existing.setRateVsUsd(entry.getValue());
            existing.setLastSyncedAt(now);
            repository.save(existing);
            updated++;
        }
        refreshCache();
        log.info("Currency sync: updated {} rates from provider", updated);
    }

    /* ============ cache plumbing ============ */

    private synchronized void refreshCache() {
        cache.clear();
        repository.findAll().forEach(r -> cache.put(r.getCode().toUpperCase(Locale.ROOT), r));
        cacheStamp = Instant.now();
    }

    private void ensureFresh() {
        if (Duration.between(cacheStamp, Instant.now()).compareTo(CACHE_TTL) >= 0) {
            refreshCache();
        }
    }

    /** Quick helper for formatting in admin endpoints (best-effort). */
    public BigDecimal asPercentage(BigDecimal value) {
        return value == null ? null : value.divide(HUNDRED, 4, RoundingMode.HALF_UP);
    }
}
