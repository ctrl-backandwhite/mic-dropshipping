package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Un importe se redondea a la unidad más pequeña que EXISTE en su moneda.
 *
 * <p>Se fijaban siempre dos decimales, tuviera la moneda o no. El yen y el peso chileno no tienen
 * céntimos: el precio se guardaba como 2775,53 ¥ y al pintarlo salía «2.776 ¥», así que en el carrito
 * el cliente leía 2.776 ¥ la unidad y 11.102 ¥ por cuatro. Dos yenes que no cuadran por más que
 * multiplique, y con ellos la sensación de que las cuentas de la tienda no son de fiar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Los importes se redondean a los decimales de cada moneda")
class CurrencyDecimalsMatchTheCurrencyTest {

    @Mock
    private CurrencyRateRepository repo;

    private CurrencyRateService service;

    @BeforeEach
    void setUp() {
        service = new CurrencyRateService(repo);
    }

    /** Deja una única tasa en la caché del servicio, que es de donde lee la conversión. */
    private void tasa(String code, String rate) {
        CurrencyRateEntity e = new CurrencyRateEntity();
        e.setCode(code);
        e.setRateVsUsd(new BigDecimal(rate));
        when(repo.findAll()).thenReturn(List.of(e));
        service.warm();
    }

    @ParameterizedTest(name = "{0}: {2} decimales")
    @CsvSource({
            // moneda, tasa frente al dólar, decimales que admite
            "JPY, 163.508502, 0", "CLP, 934.879702, 0", "KRW, 1445.619838, 0", "EUR, 0.87717, 2", "GBP, 0.751900, 2",
            "MXN, 17.450000, 2",})
    void cadaMonedaSeRedondeaASusDecimales(String moneda, String tasa, int decimales) {
        tasa(moneda, tasa);

        BigDecimal importe = service.usdTo(new BigDecimal("16.98"), moneda);

        assertThat(importe.scale()).isEqualTo(decimales);
    }

    @Test
    void enYenesLaLineaEsUnMultiploExactoDeLaUnidadQueSeEnsena() {
        tasa("JPY", "163.508502");

        BigDecimal unidad = service.usdTo(new BigDecimal("16.98"), "JPY");
        BigDecimal linea = unidad.multiply(BigDecimal.valueOf(4));

        // La línea del carrito se calcula multiplicando la unidad YA convertida (así lo hace cartQuote),
        // no convirtiendo el total: por eso lo que se enseña cuadra al multiplicarlo. Lo que fallaba era
        // que la unidad se guardaba con céntimos que el yen no tiene (2775,53) y al pintarla salía 2.776,
        // mientras la línea seguía calculándose con los 2775,53 → 11.102 en vez de 11.104.
        assertThat(unidad.scale()).isZero();
        assertThat(unidad).isEqualByComparingTo(new BigDecimal("2776"));
        assertThat(linea).isEqualByComparingTo(new BigDecimal("11104"));
    }

    @Test
    void enPesosChilenosTampocoHayCentimos() {
        tasa("CLP", "934.879702");

        BigDecimal unidad = service.usdTo(new BigDecimal("16.98"), "CLP");

        assertThat(unidad.scale()).isZero();
        assertThat(unidad.multiply(BigDecimal.valueOf(4)).stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
    }

    @Test
    void enEurosSiguenSiendoDosDecimales() {
        tasa("EUR", "0.87717");

        assertThat(service.usdTo(new BigDecimal("16.98"), "EUR")).isEqualByComparingTo(new BigDecimal("14.89"));
    }

    @Test
    void elDolarNoNecesitaTasaYConservaSusDosDecimales() {
        assertThat(service.usdTo(new BigDecimal("16.985"), "USD")).isEqualByComparingTo(new BigDecimal("16.99"));
    }

    @Test
    void unaMonedaDesconocidaSeTrataComoDeDosDecimales() {
        // Ante un código que la JDK no reconoce se asumen dos decimales, que es lo más común y lo que se
        // aplicaba antes a todas.
        when(repo.findAll()).thenReturn(List.of());
        service.warm();

        assertThat(service.usdTo(new BigDecimal("16.985"), "XXX").scale()).isEqualTo(2);
    }
}
