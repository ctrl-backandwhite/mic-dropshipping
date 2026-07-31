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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Administración del impuesto por país y por región (estado/provincia).
 *
 * <p>El desplegable del checkout se rellena con estas regiones y el IVA cobrado depende de cuál elija el
 * comprador: una región de más (inactiva) o un código sin normalizar hace que se cobre el impuesto de
 * otro sitio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov03CountryRegionAdminTest {

    @Mock
    CountryTaxRateRepository repository;
    @Mock
    CountryRegionRepository regionRepository;

    @InjectMocks
    CountryTaxService service;

    private static CountryRegionEntity region(String pais, String codigo, Integer bps, boolean activa) {
        return CountryRegionEntity.builder().countryCode(pais).regionCode(codigo).regionName(codigo).rateBps(bps)
                .active(activa).build();
    }

    /* ==================== regiones para el checkout ==================== */

    @Test
    void elDesplegableDelCheckoutSoloPideLasRegionesActivas() {
        CountryRegionEntity ca = region("US", "CA", 725, true);
        when(regionRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByPositionAscRegionNameAsc("US"))
                .thenReturn(List.of(ca));

        assertThat(service.regionsFor("  US  ")).containsExactly(ca);
    }

    @Test
    void sinPaisNoHayRegionesQueOfrecerYNoSeConsultaLaBaseDeDatos() {
        assertThat(service.regionsFor(null)).isEmpty();
        assertThat(service.regionsFor("   ")).isEmpty();
        verify(regionRepository, never())
                .findByCountryCodeIgnoreCaseAndActiveTrueOrderByPositionAscRegionNameAsc(anyString());
    }

    /* ==================== administración de regiones ==================== */

    @Test
    void elAdminVeTambienLasRegionesDesactivadasParaPoderReactivarlas() {
        CountryRegionEntity inactiva = region("US", "NY", 400, false);
        when(regionRepository.findByCountryCodeIgnoreCaseOrderByPositionAscRegionNameAsc("US"))
                .thenReturn(List.of(inactiva));

        assertThat(service.listRegionsAdmin("US")).containsExactly(inactiva);
        assertThat(service.listRegionsAdmin(null)).isEmpty();
        assertThat(service.listRegionsAdmin("  ")).isEmpty();
    }

    @Test
    void unaRegionNuevaSeGuardaConLosCodigosNormalizadosAMayusculas() {
        // Si el código se guardara como lo escribe el administrador, la dirección "us/ca" no encontraría
        // su tasa y se cobraría el IVA nacional.
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "CA"))
                .thenReturn(Optional.empty());
        when(regionRepository.save(any(CountryRegionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CountryRegionEntity guardada = service.regionUpsert(" us ", " ca ", "California", 725, true, 3);

        assertThat(guardada.getCountryCode()).isEqualTo("US");
        assertThat(guardada.getRegionCode()).isEqualTo("CA");
        assertThat(guardada.getRegionName()).isEqualTo("California");
        assertThat(guardada.getRateBps()).isEqualTo(725);
        assertThat(guardada.getPosition()).isEqualTo(3);
    }

    @Test
    void unaRegionSinTasaPropiaSeGuardaConTasaNulaParaHeredarLaNacional() {
        // Nulo y cero NO son lo mismo: cero significa "aquí no se cobra impuesto" y nulo "usa el del país".
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("ES", "MD"))
                .thenReturn(Optional.empty());
        when(regionRepository.save(any(CountryRegionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.regionUpsert("ES", "MD", "Madrid", null, true, null).getRateBps()).isNull();
    }

    @Test
    void unaTasaDeRegionNegativaSeCorrigeACeroYNoDevuelveDinero() {
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "OR"))
                .thenReturn(Optional.empty());
        when(regionRepository.save(any(CountryRegionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.regionUpsert("US", "OR", "Oregon", -500, true, null).getRateBps()).isZero();
    }

    @Test
    void editarUnaRegionExistenteNoCreaOtraNiPierdeSuPosicion() {
        CountryRegionEntity existente = region("US", "CA", 725, true);
        existente.setPosition(7);
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "CA"))
                .thenReturn(Optional.of(existente));
        when(regionRepository.save(any(CountryRegionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CountryRegionEntity guardada = service.regionUpsert("US", "CA", "California", 800, false, null);

        assertThat(guardada).isSameAs(existente);
        assertThat(guardada.getRateBps()).isEqualTo(800);
        assertThat(guardada.isActive()).isFalse();
        assertThat(guardada.getPosition()).isEqualTo(7); // sin posición indicada se conserva la que tenía
    }

    @Test
    void borrarUnaRegionQueNoExisteDaNoEncontradoEnLugarDeNoHacerNada() {
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "ZZ"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regionDelete("US", "ZZ")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void borrarUnaRegionExistenteLaEliminaDeVerdad() {
        CountryRegionEntity ca = region("US", "CA", 725, true);
        when(regionRepository.findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase("US", "CA"))
                .thenReturn(Optional.of(ca));

        service.regionDelete(" US ", " CA ");

        verify(regionRepository).delete(ca);
    }

    /* ==================== administración de tasas nacionales ==================== */

    @Test
    void elListadoDeTasasSaleOrdenadoPorCodigoDePais() {
        CountryTaxRateEntity es = CountryTaxRateEntity.builder().countryCode("ES").rateBps(2100).active(true).build();
        when(repository.findAllByOrderByCountryCodeAsc()).thenReturn(List.of(es));

        assertThat(service.listAll()).containsExactly(es);
    }

    @Test
    void editarLaTasaDeUnPaisReutilizaSuFilaYNoDuplicaElPais() {
        // Dos filas para el mismo país harían que el IVA aplicado dependiera de cuál se leyera primero.
        CountryTaxRateEntity existente = CountryTaxRateEntity.builder().countryCode("ES").rateBps(2100).active(true)
                .build();
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(existente));
        when(repository.save(any(CountryTaxRateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CountryTaxRateEntity guardada = service.upsert("es", "IVA reducido", 1000, false);

        assertThat(guardada).isSameAs(existente);
        assertThat(guardada.getRateBps()).isEqualTo(1000);
        assertThat(guardada.isActive()).isFalse();
    }

    @Test
    void borrarLaTasaDeUnPaisExistenteLaEliminaDeVerdad() {
        CountryTaxRateEntity es = CountryTaxRateEntity.builder().countryCode("ES").rateBps(2100).active(true).build();
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(es));

        service.delete("  ES ");

        verify(repository).delete(es);
    }
}
