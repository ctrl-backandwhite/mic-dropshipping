package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.application.service.BulkProductFields;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El recargo que fija el administrador tiene que sobrevivir a una reimportación.
 *
 * <p>El recargo «lo fija el dueño desde el panel, nunca la extracción» — así está escrito tanto en
 * el scraper como en el backend. Pero el catálogo se reimporta constantemente: ahora mismo hay
 * 10.157 productos en cola para recargarse. Si cada pasada lo devuelve a cero, el trabajo del panel
 * dura hasta la siguiente extracción y nadie se entera, porque no hay ningún error: sólo un precio
 * que vuelve a ser el de antes.
 *
 * <p>Es la misma regla que el propio cambio del 23-sep-2026 declara para los tramos: «nulo NO es
 * cero». Un bulk que no habla del recargo no está pidiendo que se borre.
 */
class RecargoSobreviveALaReimportacionTest {

    @Test
    @DisplayName("un bulk que no trae recargo no borra el que ya tenía el producto")
    void unBulkSinRecargoNoBorraElQueYaHabia() {
        ProductEntity p = new ProductEntity();
        p.setSurchargeCny(new BigDecimal("5.00"));

        BulkProductFields.applySurcharge(p, filaSinRecargo());

        assertThat(p.getSurchargeCny()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("un producto nuevo sin recargo en el bulk entra a cero, no a nulo")
    void unProductoNuevoEntraACero() {
        // La columna es NOT NULL desde la v157: dejarlo a nulo tumbaba el alta entera con un 23502
        // y el producto no se creaba. Cero es el valor correcto para lo que no existía todavía.
        ProductEntity p = new ProductEntity();

        BulkProductFields.applySurcharge(p, filaSinRecargo());

        assertThat(p.getSurchargeCny()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un recargo explícito en el bulk sí manda")
    void unRecargoExplicitoMandaSobreElAnterior() {
        // EL control: sin él, «conservar lo que había» pasaría por arreglo y haría imposible
        // cambiar el recargo desde el bulk, que es una vía legítima.
        ProductEntity p = new ProductEntity();
        p.setSurchargeCny(new BigDecimal("5.00"));
        BulkProductDtoIn fila = filaSinRecargo();
        fila.setSurchargeCny(new BigDecimal("2.00"));

        BulkProductFields.applySurcharge(p, fila);

        assertThat(p.getSurchargeCny()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("un cero explícito en el bulk también manda: es un importe, no una ausencia")
    void unCeroExplicitoBorraElRecargo() {
        ProductEntity p = new ProductEntity();
        p.setSurchargeCny(new BigDecimal("5.00"));
        BulkProductDtoIn fila = filaSinRecargo();
        fila.setSurchargeCny(BigDecimal.ZERO);

        BulkProductFields.applySurcharge(p, fila);

        assertThat(p.getSurchargeCny()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el recargo de un TRAMO sobrevive a la reimportación que lo recrea")
    void elRecargoDeUnTramoSobrevive() {
        // `replacePriceTiers` BORRA y RECREA los tramos en cada importación. El scraper no manda
        // recargo de tramo —y hace bien, lo fija el panel— así que el valor llegaba nulo y el
        // tramo renacía sin él. Es peor que el del producto: nadie mira los tramos uno a uno.
        java.util.Map<Integer, java.math.BigDecimal> anteriores = java.util.Map.of(1, new BigDecimal("0.80"), 100,
                new BigDecimal("0.20"));

        assertThat(BulkProductFields.tierSurcharge(anteriores, 1, null)).isEqualByComparingTo("0.80");
        assertThat(BulkProductFields.tierSurcharge(anteriores, 100, null)).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("un tramo nuevo sin recargo se queda a nulo: hereda el del producto")
    void unTramoNuevoSeQuedaANulo() {
        // Aquí nulo SÍ es el valor correcto, al revés que en el producto: la columna del tramo es
        // NULLABLE a propósito y nulo significa «usa el del producto». Ponerlo a cero le daría a
        // cada tramo nuevo un recargo de cero que nadie decidió.
        assertThat(BulkProductFields.tierSurcharge(java.util.Map.of(), 1, null)).isNull();
    }

    @Test
    @DisplayName("lo que el bulk declara para el tramo manda sobre lo que había")
    void loQueElBulkDeclaraParaElTramoManda() {
        java.util.Map<Integer, java.math.BigDecimal> anteriores = java.util.Map.of(1, new BigDecimal("0.80"));

        assertThat(BulkProductFields.tierSurcharge(anteriores, 1, new BigDecimal("0.10"))).isEqualByComparingTo("0.10");
    }

    private static BulkProductDtoIn filaSinRecargo() {
        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setExternalId("X");
        return r;
    }
}
