package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryRegionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryTaxRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryRegionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryTaxRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryTaxServiceTest {

    @Mock
    CountryTaxRateRepository repository;
    @Mock
    CountryRegionRepository regionRepository;
    @InjectMocks
    CountryTaxService service;

    private static CountryTaxRateEntity rate(String code, int bps, boolean active) {
        return CountryTaxRateEntity.builder().countryCode(code).rateBps(bps).active(active).build();
    }

    private static CountryRegionEntity region(String country, String code, Integer bps, boolean active) {
        return CountryRegionEntity.builder().countryCode(country).regionCode(code).regionName(code).rateBps(bps)
                .active(active).build();
    }

    // ===== IVA resuelto por REGIÓN (estado/provincia) =====

    @Test
    void rateByRegion_regionWithOwnRateOverridesNational() {
        lenient().when(repository.findByCountryCodeIgnoreCase("US")).thenReturn(Optional.of(rate("US", 0, true)));
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "CA"))
                .thenReturn(Optional.of(region("US", "CA", 725, true)));
        assertThat(service.rateBpsFor("US", "CA")).isEqualTo(725); // California 7.25%
    }

    @Test
    void rateByRegion_regionWithoutOwnRateFallsBackToNational() {
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(rate("ES", 2100, true)));
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("ES", "MD"))
                .thenReturn(Optional.of(region("ES", "MD", null, true)));
        assertThat(service.rateBpsFor("ES", "MD")).isEqualTo(2100); // Madrid -> nacional 21%
    }

    @Test
    void rateByRegion_inactiveRegionFallsBackToNational() {
        when(repository.findByCountryCodeIgnoreCase("US")).thenReturn(Optional.of(rate("US", 0, true)));
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "CA"))
                .thenReturn(Optional.of(region("US", "CA", 725, false)));
        assertThat(service.rateBpsFor("US", "CA")).isZero(); // región desactivada -> nacional US 0%
    }

    @Test
    void rateByRegion_noRegionUsesNational() {
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(rate("ES", 2100, true)));
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("ES", "ZZ"))
                .thenReturn(Optional.empty());
        assertThat(service.rateBpsFor("ES", "ZZ")).isEqualTo(2100);
    }

    @Test
    void rateByRegion_nullRegionUsesNational() {
        when(repository.findByCountryCodeIgnoreCase("IL")).thenReturn(Optional.of(rate("IL", 1800, true)));
        assertThat(service.rateBpsFor("IL", null)).isEqualTo(1800); // Israel 18%
    }

    @Test
    void taxCentsFor_byRegion_usesResolvedRate() {
        lenient().when(repository.findByCountryCodeIgnoreCase("US")).thenReturn(Optional.of(rate("US", 0, true)));
        lenient().when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "CA"))
                .thenReturn(Optional.of(region("US", "CA", 725, true)));
        // 10000 céntimos (100,00) * 7.25% = 725 céntimos
        assertThat(service.taxCentsFor("US", "CA", 10000)).isEqualTo(725);
        // sin región -> nacional US 0%
        assertThat(service.taxCentsFor("US", null, 10000)).isZero();
    }

    @Test
    void rateBpsFor_returnsZeroForNullOrBlank() {
        assertThat(service.rateBpsFor(null)).isZero();
        assertThat(service.rateBpsFor("   ")).isZero();
        verify(repository, never()).findByCountryCodeIgnoreCase(anyString());
    }

    @Test
    void rateBpsFor_returnsZeroWhenNotFoundOrInactive() {
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.empty());
        assertThat(service.rateBpsFor("ES")).isZero();

        when(repository.findByCountryCodeIgnoreCase("FR")).thenReturn(Optional.of(rate("FR", 2000, false)));
        assertThat(service.rateBpsFor("FR")).isZero();
    }

    @Test
    void rateBpsFor_trimsAndReturnsActiveRate() {
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(rate("ES", 2100, true)));
        assertThat(service.rateBpsFor("  ES  ")).isEqualTo(2100);
    }

    @Test
    void taxCentsFor_zeroWhenNoRateOrNonPositiveBase() {
        lenient().when(repository.findByCountryCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        assertThat(service.taxCentsFor("ES", 1000)).isZero(); // sin tasa
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(rate("ES", 2100, true)));
        assertThat(service.taxCentsFor("ES", 0)).isZero();
        assertThat(service.taxCentsFor("ES", -50)).isZero();
    }

    @Test
    void taxCentsFor_appliesBpsWithHalfUpRounding() {
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(rate("ES", 2100, true)));
        // 1462 * 2100 / 10000 = 307.02 -> 307
        assertThat(service.taxCentsFor("ES", 1462)).isEqualTo(307);

        when(repository.findByCountryCodeIgnoreCase("DE")).thenReturn(Optional.of(rate("DE", 2150, true)));
        // 100 * 2150 / 10000 = 21.5 -> HALF_UP -> 22
        assertThat(service.taxCentsFor("DE", 100)).isEqualTo(22);
    }

    @Test
    void upsert_uppercasesCodeClampsNegativeRateAndSaves() {
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(CountryTaxRateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CountryTaxRateEntity saved = service.upsert(" es ", "IVA", -5, true);

        assertThat(saved.getCountryCode()).isEqualTo("ES");
        assertThat(saved.getRateBps()).isZero(); // clamp de negativos
        assertThat(saved.getLabel()).isEqualTo("IVA");
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void delete_throwsWhenMissing() {
        when(repository.findByCountryCodeIgnoreCase("XX")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete("XX")).isInstanceOf(NotFoundException.class);
    }
}
