package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Peso FACTURABLE y {@code PackageType} de YunExpress.
 *
 * <p>El transportista tarifa por el mayor entre peso real y volumétrico ({@code L×W×H cm / 6000}) y solo
 * aplica el volumétrico a partir de cierto volumen. Si la cotización no lo repercute, el carrier repesa en
 * almacén y factura la diferencia después del cobro al cliente, contra el margen.
 */
class YunExpressChargeableWeightTest {

    private YunExpressFulfillmentService service;

    @BeforeEach
    void setUp() {
        service = new YunExpressFulfillmentService(null, null, null, null, null);
        ReflectionTestUtils.setField(service, "volumetricDivisor", 6000.0);
        ReflectionTestUtils.setField(service, "volumetricMinCm3", 6000.0);
    }

    @Test
    void bultoPequenoFacturaPorPesoReal() {
        // Camiseta en bolsa: 32 x 24 x 5 cm = 3840 cm³, por debajo del umbral -> peso real.
        ParcelSpec camiseta = new ParcelSpec(200, 320, 240, 50, false);

        assertThat(service.chargeableWeightGrams(camiseta)).isEqualTo(200);
    }

    @Test
    void bultoVoluminosoFacturaPorPesoVolumetrico() {
        // Chaqueta: 38 x 30 x 10 cm = 11400 cm³ -> 11400/6000 = 1,9 kg, más que los 800 g reales.
        ParcelSpec chaqueta = new ParcelSpec(800, 380, 300, 100, false);

        assertThat(service.chargeableWeightGrams(chaqueta)).isEqualTo(1900);
    }

    @Test
    void bultoPesadoYVoluminosoMantieneElPesoReal() {
        // Si el real supera al volumétrico, manda el real: 3 kg > 11400/6000 = 1,9 kg.
        ParcelSpec pesado = new ParcelSpec(3000, 380, 300, 100, false);

        assertThat(service.chargeableWeightGrams(pesado)).isEqualTo(3000);
    }

    @Test
    void sinDimensionesFacturaPorPesoReal() {
        assertThat(service.chargeableWeightGrams(ParcelSpec.ofWeight(450))).isEqualTo(450);
    }

    @Test
    void pesoNuncaEsCero() {
        assertThat(service.chargeableWeightGrams(ParcelSpec.ofWeight(0))).isEqualTo(1);
    }

    @Test
    void packageTypeDistingueMercanciaConBateria() {
        // E = 带电 (con batería), C = 普货 (carga general). Determina qué canales pueden cotizar el bulto.
        assertThat(YunExpressFulfillmentService.packageType(new ParcelSpec(120, 120, 100, 80, true))).isEqualTo("E");
        assertThat(YunExpressFulfillmentService.packageType(new ParcelSpec(120, 120, 100, 80, false))).isEqualTo("C");
    }
}
