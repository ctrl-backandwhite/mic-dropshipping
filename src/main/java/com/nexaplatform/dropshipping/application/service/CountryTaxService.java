package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryTaxRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryTaxRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Impuesto (IVA/sales tax) por país de envío. La tasa se configura en {@code country_tax_rate} (puntos
 * básicos) y se aplica sobre la base imponible (subtotal + envío) al crear el pedido. Si el país no tiene
 * tasa activa, el impuesto es 0 (el cobro solo incluye productos + envío).
 */
@Service
@RequiredArgsConstructor
public class CountryTaxService {

    private final CountryTaxRateRepository repository;

    /** Tasa en puntos básicos para el país (0 si no hay tasa activa configurada). */
    @Transactional(readOnly = true)
    public int rateBpsFor(String country) {
        if (country == null || country.isBlank()) {
            return 0;
        }
        return repository.findByCountryCodeIgnoreCase(country.trim()).filter(CountryTaxRateEntity::isActive)
                .map(CountryTaxRateEntity::getRateBps).orElse(0);
    }

    /** Impuesto en céntimos sobre {@code taxableBaseCents} (subtotal + envío), redondeado HALF_UP. */
    public int taxCentsFor(String country, int taxableBaseCents) {
        int bps = rateBpsFor(country);
        if (bps <= 0 || taxableBaseCents <= 0) {
            return 0;
        }
        return BigDecimal.valueOf((long) taxableBaseCents * bps).divide(BigDecimal.valueOf(10000), 0,
                RoundingMode.HALF_UP).intValue();
    }

    /* ============ Admin CRUD ============ */

    @Transactional(readOnly = true)
    public List<CountryTaxRateEntity> listAll() {
        return repository.findAllByOrderByCountryCodeAsc();
    }

    /** Crea o actualiza la tasa de un país (country_code en mayúsculas). */
    @Transactional
    public CountryTaxRateEntity upsert(String country, String label, int rateBps, boolean active) {
        String code = country.trim().toUpperCase();
        CountryTaxRateEntity e = repository.findByCountryCodeIgnoreCase(code)
                .orElseGet(() -> CountryTaxRateEntity.builder().countryCode(code).build());
        e.setLabel(label);
        e.setRateBps(Math.max(0, rateBps));
        e.setActive(active);
        return repository.save(e);
    }

    @Transactional
    public void delete(String country) {
        CountryTaxRateEntity e = repository.findByCountryCodeIgnoreCase(country.trim())
                .orElseThrow(() -> new NotFoundException("Tax rate for " + country));
        repository.delete(e);
    }
}
