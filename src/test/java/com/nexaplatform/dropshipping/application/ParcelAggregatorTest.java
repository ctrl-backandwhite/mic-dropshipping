package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.ParcelAggregator;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Armado del bulto que se manda a cotizar: peso, medidas del paquete y batería.
 *
 * <p>Lo usan la vista previa del checkout y el cobro del pedido, así que un fallo aquí es una diferencia
 * entre el envío que ve el cliente y el que se le cobra.
 */
class ParcelAggregatorTest {

    private static ProductEntity product(Integer weight, Integer l, Integer w, Integer h, String battery) {
        return ProductEntity.builder().weightGrams(weight).lengthMm(l).widthMm(w).heightMm(h)
                .batteryType(battery).build();
    }

    @Test
    void tomaLasMedidasDelPaqueteDelProducto() {
        ParcelAggregator agg = new ParcelAggregator();
        agg.add(product(200, 320, 240, 50, "NONE"), null, 1);

        ParcelSpec parcel = agg.build();

        assertThat(parcel.weightGrams()).isEqualTo(200);
        assertThat(parcel.lengthMm()).isEqualTo(320);
        assertThat(parcel.widthMm()).isEqualTo(240);
        assertThat(parcel.heightMm()).isEqualTo(50);
        assertThat(parcel.volumeCm3()).isEqualTo(32.0 * 24.0 * 5.0);
    }

    @Test
    void apilaLasAlturasYConservaLargoYAnchoMayores() {
        ParcelAggregator agg = new ParcelAggregator();
        agg.add(product(200, 320, 240, 50, "NONE"), null, 2);   // dos camisetas apiladas
        agg.add(product(450, 280, 200, 110, "NONE"), null, 1);  // un bolso

        ParcelSpec parcel = agg.build();

        assertThat(parcel.weightGrams()).isEqualTo(200 * 2 + 450);
        assertThat(parcel.lengthMm()).isEqualTo(320);
        assertThat(parcel.widthMm()).isEqualTo(240);
        assertThat(parcel.heightMm()).isEqualTo(50 * 2 + 110);
    }

    @Test
    void unSoloArticuloConBateriaMarcaTodoElBulto() {
        ParcelAggregator agg = new ParcelAggregator();
        agg.add(product(200, 320, 240, 50, "NONE"), null, 1);
        agg.add(product(120, 120, 100, 80, "BUILT_IN"), null, 1);   // reloj de cuarzo

        assertThat(agg.build().withBattery()).isTrue();
    }

    @Test
    void sinBateriaElBultoVaComoCargaGeneral() {
        ParcelAggregator agg = new ParcelAggregator();
        agg.add(product(200, 320, 240, 50, "NONE"), null, 1);
        agg.add(product(450, 280, 200, 110, null), null, 1);

        assertThat(agg.build().withBattery()).isFalse();
    }

    @Test
    void elPesoDeLaVarianteTienePrioridadSobreElDelProducto() {
        ProductVariantEntity variant = ProductVariantEntity.builder().weightGrams(98).build();

        ParcelAggregator agg = new ParcelAggregator();
        agg.add(product(300, 280, 200, 30, "NONE"), variant, 1);

        assertThat(agg.build().weightGrams()).isEqualTo(98);
    }

    @Test
    void sinVarianteElegidaUsaLaVarianteMasPesadaDelProducto() {
        // El peso del catálogo está en las variantes: al cotizar antes de elegir color no debe caer al
        // valor por defecto de 500 g, que infravaloraría o inflaría el flete según el producto.
        ProductEntity conVariantes = product(null, 280, 200, 30, "NONE");
        conVariantes.setVariants(java.util.List.of(
                ProductVariantEntity.builder().weightGrams(98).build(),
                ProductVariantEntity.builder().weightGrams(130).build()));

        ParcelAggregator agg = new ParcelAggregator();
        agg.add(conVariantes, null, 1);

        assertThat(agg.build().weightGrams()).isEqualTo(130);
    }

    @Test
    void lineaSinProductoResuelveConElPesoPorDefecto() {
        ParcelAggregator agg = new ParcelAggregator();
        agg.addUnknown(2);

        assertThat(agg.build().weightGrams()).isEqualTo(1000);
    }

    @Test
    void bultoVacioNoTieneDimensionesNiPesoCero() {
        ParcelSpec parcel = new ParcelAggregator().build();

        assertThat(parcel.weightGrams()).isEqualTo(1);
        assertThat(parcel.hasDimensions()).isFalse();
        assertThat(parcel.volumeCm3()).isZero();
    }
}
