package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryRegionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryTaxRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryRegionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryTaxRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CountryRegionRepository regionRepository;

    /** Tasa nacional en puntos básicos para el país (0 si no hay tasa activa configurada). */
    @Transactional(readOnly = true)
    public int rateBpsFor(String country) {
        return nationalRateBps(country);
    }

    /**
     * Tasa en bps resolviendo por REGIÓN: si el estado/provincia tiene tasa propia (US/CA/BR), se usa esa;
     * si no, la tasa nacional del país. Así el IVA se calcula según el estado de la dirección.
     */
    @Transactional(readOnly = true)
    public int rateBpsFor(String country, String region) {
        return regionalRateBps(country, region);
    }

    /** Impuesto en céntimos sobre {@code taxableBaseCents} (subtotal + envío), redondeado HALF_UP. */
    @Transactional(readOnly = true)
    public int taxCentsFor(String country, int taxableBaseCents) {
        return computeTaxCents(country, null, taxableBaseCents);
    }

    /** Igual que {@link #taxCentsFor(String, int)} pero resolviendo la tasa por región (estado/provincia). */
    @Transactional(readOnly = true)
    public int taxCentsFor(String country, String region, int taxableBaseCents) {
        return computeTaxCents(country, region, taxableBaseCents);
    }

    /*
     * Los cuatro métodos públicos se llamaban entre sí con `this`, así que el proxy de Spring no
     * intervenía y su @Transactional(readOnly) era una promesa que nadie cumplía en las llamadas
     * internas. El cálculo vive ahora en estos privados sin anotar y la transacción queda solo en el
     * punto de entrada, que es donde de verdad se abre.
     */

    private int nationalRateBps(String country) {
        if (country == null || country.isBlank()) {
            return 0;
        }
        return repository.findByCountryCodeIgnoreCase(country.trim()).filter(CountryTaxRateEntity::isActive)
                .map(CountryTaxRateEntity::getRateBps).orElse(0);
    }

    private int regionalRateBps(String country, String region) {
        if (country != null && !country.isBlank() && region != null && !region.isBlank()) {
            Integer regionBps = regionRepository
                    .findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase(country.trim(), region.trim())
                    .filter(CountryRegionEntity::isActive).map(CountryRegionEntity::getRateBps).orElse(null);
            if (regionBps != null) {
                return Math.max(0, regionBps);
            }
        }
        return nationalRateBps(country);
    }

    private int computeTaxCents(String country, String region, int taxableBaseCents) {
        int bps = regionalRateBps(country, region);
        if (bps <= 0 || taxableBaseCents <= 0) {
            return 0;
        }
        return BigDecimal.valueOf((long) taxableBaseCents * bps)
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).intValue();
    }

    /** Regiones (estado/provincia) activas de un país para el dropdown del checkout. */
    @Transactional(readOnly = true)
    public List<CountryRegionEntity> regionsFor(String country) {
        if (country == null || country.isBlank()) {
            return List.of();
        }
        return regionRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByPositionAscRegionNameAsc(country.trim());
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

    /* ============ Admin CRUD de regiones (estado/provincia) ============ */

    /** Todas las regiones de un país (incluidas inactivas) para el admin. */
    @Transactional(readOnly = true)
    public List<CountryRegionEntity> listRegionsAdmin(String country) {
        if (country == null || country.isBlank()) {
            return List.of();
        }
        return regionRepository.findByCountryCodeIgnoreCaseOrderByPositionAscRegionNameAsc(country.trim());
    }

    /** Crea o actualiza una región (estado/provincia). {@code rateBps} null = usa la tasa nacional. */
    @Transactional
    public CountryRegionEntity regionUpsert(String country, String code, String name, Integer rateBps, boolean active,
            Integer position) {
        String cc = country.trim().toUpperCase();
        String rc = code.trim().toUpperCase();
        CountryRegionEntity e = regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase(cc, rc)
                .orElseGet(() -> CountryRegionEntity.builder().countryCode(cc).regionCode(rc).build());
        e.setCountryCode(cc);
        e.setRegionCode(rc);
        e.setRegionName(name);
        e.setRateBps(rateBps == null ? null : Math.max(0, rateBps));
        e.setActive(active);
        if (position != null) {
            e.setPosition(position);
        }
        return regionRepository.save(e);
    }

    @Transactional
    public void regionDelete(String country, String code) {
        CountryRegionEntity e = regionRepository
                .findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase(country.trim(), code.trim())
                .orElseThrow(() -> new NotFoundException("Region " + country + "/" + code));
        regionRepository.delete(e);
    }
}
