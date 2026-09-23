package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Segunda tanda de {@link CurrencyRateService}: la caché de 5 minutos, el formateo con el locale del
 * QUE MIRA (no el de la moneda) y las operaciones de administración (activar, sobreescribir tasa y
 * sincronización masiva del proveedor).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov02CurrencyRateAdminTest {

    @Mock
    private CurrencyRateRepository repository;

    @InjectMocks
    private CurrencyRateService service;

    private List<CurrencyRateEntity> enBd;

    private static CurrencyRateEntity rate(String code, String symbol, String locale, String rateVsUsd,
            boolean active) {
        return CurrencyRateEntity.builder().code(code).name(code).symbol(symbol).locale(locale)
                .rateVsUsd(new BigDecimal(rateVsUsd)).active(active).build();
    }

    @BeforeEach
    void setUp() {
        enBd = new ArrayList<>(List.of(rate("USD", "$", "en-US", "1.00000000", true),
                rate("EUR", "€", "es-ES", "0.90000000", true), rate("CNY", "¥", "zh-CN", "7.20000000", true)));
        when(repository.findAll()).thenAnswer(inv -> enBd);
    }

    /* ============ caché ============ */

    @Test
    void elCalentadoDeArranqueDejaLaCacheListaSinTocarLaBdEnLaPrimeraLectura() {
        service.warm();

        assertThat(service.listActive()).hasSize(3);
        // Una sola consulta: la del arranque. Si la caché no se llenara, cada lectura iría a la BD.
        verify(repository, times(1)).findAll();
    }

    @Test
    void dentroDelTtlLasLecturasNoVuelvenAConsultarLaBd() {
        service.listActive();
        service.listAll();
        service.find("EUR");

        verify(repository, times(1)).findAll();
    }

    @Test
    void pasadoElTtlLaSiguienteLecturaRefrescaLaCache() {
        // El precio de venta se convierte con estas tasas: si la caché no caducara, un cambio de tasa
        // tardaría en llegar al escaparate hasta el reinicio.
        service.listActive();
        ReflectionTestUtils.setField(service, "cacheStamp", Instant.now().minus(Duration.ofMinutes(6)));

        service.listActive();

        verify(repository, times(2)).findAll();
    }

    /* ============ formatIn (locale del visor) ============ */

    @Test
    void formatInUsaLosSeparadoresDelVisorYElSimboloDeLaMoneda() {
        // Un usuario en español ve el dólar con coma decimal y punto de miles, como en Stripe.
        String enEspanol = service.formatIn(new BigDecimal("1234.56"), "USD", "es-ES");

        assertThat(enEspanol).contains("1.234,56");
    }

    @Test
    void formatInSinLocaleDelVisorCaeEnElDeLaMoneda() {
        assertThat(service.formatIn(new BigDecimal("32.56"), "USD", null)).isEqualTo("$32.56");
        assertThat(service.formatIn(new BigDecimal("32.56"), "USD", "  ")).isEqualTo("$32.56");
    }

    @Test
    void formatInConMonedaNoIsoCaeEnSimboloMasNumeroPlano() {
        // USDT no existe en el catálogo ISO de Java: no puede reventar el pintado del precio.
        assertThat(service.formatIn(new BigDecimal("12.50"), "USDT", "en-US")).isEqualTo("$ 12.50");
    }

    @Test
    void formatInConImporteOMonedaNulosDevuelveNulo() {
        assertThat(service.formatIn(null, "EUR", "es-ES")).isNull();
        assertThat(service.formatIn(new BigDecimal("1"), null, "es-ES")).isNull();
    }

    /* ============ administración ============ */

    @Test
    void activarUnaMonedaLaPersisteYRefrescaLaCache() {
        CurrencyRateEntity gbp = rate("GBP", "£", "en-GB", "0.80000000", false);
        when(repository.findByCodeIgnoreCase("GBP")).thenReturn(Optional.of(gbp));
        enBd.add(gbp);

        CurrencyRateEntity resultado = service.setActive("GBP", true);

        assertThat(resultado.isActive()).isTrue();
        verify(repository).save(gbp);
        // Tras el cambio la lectura ya la ve activa: si no se refrescara, el selector de divisas mentiría.
        assertThat(service.listActive()).extracting(CurrencyRateEntity::getCode).contains("GBP");
    }

    @Test
    void activarUnaMonedaInexistenteFalla() {
        when(repository.findByCodeIgnoreCase("XXX")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setActive("XXX", true)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sobreescribirLaTasaGuardaLaFechaDeSincronizacion() {
        CurrencyRateEntity eur = enBd.get(1);
        when(repository.findByCodeIgnoreCase("EUR")).thenReturn(Optional.of(eur));

        CurrencyRateEntity resultado = service.overrideRate("EUR", new BigDecimal("0.95"));

        assertThat(resultado.getRateVsUsd()).isEqualByComparingTo("0.95");
        // Sin marca de sincronización no se sabría si la tasa manual es reciente o de hace meses.
        assertThat(resultado.getLastSyncedAt()).isNotNull();
        verify(repository).save(eur);
        assertThat(service.usdTo(new BigDecimal("100"), "EUR")).isEqualByComparingTo("95.00");
    }

    @Test
    void sobreescribirLaTasaDeUnaMonedaInexistenteFalla() {
        when(repository.findByCodeIgnoreCase("XXX")).thenReturn(Optional.empty());
        BigDecimal nueva = new BigDecimal("1.5");

        assertThatThrownBy(() -> service.overrideRate("XXX", nueva)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sincronizacionVaciaConservaLasTasasActuales() {
        // Si el proveedor devuelve nada, machacar la tabla dejaría todos los precios a cero.
        service.applyBulkSync(Map.of());
        service.applyBulkSync(null);

        verify(repository, never()).save(any(CurrencyRateEntity.class));
    }

    @Test
    void sincronizacionActualizaLasConocidasYCreaLasNuevasComoInactivas() {
        when(repository.findByCodeIgnoreCase("EUR")).thenReturn(Optional.of(enBd.get(1)));
        when(repository.findByCodeIgnoreCase("JPY")).thenReturn(Optional.empty());
        Map<String, BigDecimal> delProveedor = new HashMap<>();
        delProveedor.put("eur", new BigDecimal("0.95"));
        delProveedor.put("JPY", new BigDecimal("150"));
        ArgumentCaptor<CurrencyRateEntity> captor = ArgumentCaptor.forClass(CurrencyRateEntity.class);

        service.applyBulkSync(delProveedor);

        verify(repository, times(2)).save(captor.capture());
        CurrencyRateEntity nueva = captor.getAllValues().stream().filter(c -> "JPY".equals(c.getCode())).findFirst()
                .orElseThrow();
        // Una moneda nueva del proveedor NO se activa sola: solo las que la tienda usa deben ofrecerse.
        assertThat(nueva.isActive()).isFalse();
        assertThat(nueva.getLastSyncedAt()).isNotNull();
        assertThat(enBd.get(1).getRateVsUsd()).isEqualByComparingTo("0.95");
    }

    @Test
    void sincronizacionIgnoraLosCodigosQueNoSonIsoDeTresLetras() {
        // El proveedor mezcla metales (XAU) y cripto con nombres largos; solo entran códigos ISO de 3.
        Map<String, BigDecimal> delProveedor = new HashMap<>();
        delProveedor.put("BITCOIN", new BigDecimal("1"));
        delProveedor.put("XX", new BigDecimal("1"));

        service.applyBulkSync(delProveedor);

        verify(repository, never()).save(any(CurrencyRateEntity.class));
    }
}
